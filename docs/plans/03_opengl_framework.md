# Plan 03 — OpenGL ES Rendering Framework

**Phase:** 1c
**Depends on:** Plan 01 (Android project)
**Blocks:** Plans 06, 07, 08, 09, 11, 12 (all rendering plans)

---

## Goals

Establish the OpenGL ES 3.0 rendering infrastructure used by all three display modes:
- Shared EGL context across all `GLSurfaceView`s in the same mode
- Shader loading and compilation from `.glsl` assets
- VAO/VBO geometry helpers
- Texture atlas and font atlas management
- Day/night theme uniform system
- OpenGL ES 2.0 graceful fallback
- 60fps render loop with 4ms budget enforcement

Requirements covered: §1.1 (all), NFR-P01, NFR-P06.

---

## 1. EGL Context Strategy

### Shared context per panel mode

Each display mode (Steam Gauges, G1000 PFD, G1000 MFD) uses one or more `GLSurfaceView` instances that share a single EGL context. This allows texture sharing (dial faces, font atlas, aviation symbol sprites).

```kotlin
// rendering/gl/EglContextManager.kt

object EglContextManager {
    private var sharedContext: EGLContext? = null
    private val eglDisplay: EGLDisplay by lazy { ... }

    /**
     * Call this once per panel mode before creating any GLSurfaceView.
     * Returns the shared context to pass as EGLContext.EMPTY_CONTEXT
     * override in GLSurfaceView.setEGLContextFactory().
     */
    fun getOrCreateSharedContext(eglConfig: EGLConfig): EGLContext { ... }

    fun releaseSharedContext() { ... }
}
```

### GLSurfaceView configuration

All gauge views extend `BaseGlSurfaceView`:
```kotlin
abstract class BaseGlSurfaceView(context: Context) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setEGLContextFactory(SharedContextFactory(EglContextManager))
        preserveEGLContextOnPause = true   // don't destroy textures on pause
    }

    override fun onPause() {
        super.onPause()
        // Renderer paused — stop frame loop, release wake lock
    }
}
```

### Fallback to ES 2.0
```kotlin
class SharedContextFactory(private val manager: EglContextManager) : EGLContextFactory {
    override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
        return try {
            manager.getOrCreateSharedContext(config)  // tries ES 3.0 first
        } catch (e: EglException) {
            // Fall back to ES 2.0 — log warning, set a CapabilityFlags.ES2_ONLY flag
            createEs2Context(egl, display, config)
        }
    }
}
```

---

## 2. Shader Infrastructure

### Asset layout
```
assets/shaders/
  common/
    common_uniforms.glsl     // #version 300 es header + common uniforms include
  gauges/
    gauge_base.vert
    gauge_base.frag
    needle.vert
    arc_segment.vert
    arc_segment.frag
    text_glyph.vert
    text_glyph.frag
  g1000/
    tape.vert
    tape.frag
    attitude.vert
    attitude.frag
    hsi.vert
    hsi.frag
  map/
    tile.vert
    tile.frag
    route_line.vert
    route_line.frag
    terrain_taws.frag
```

### ShaderManager
```kotlin
// rendering/gl/ShaderManager.kt

class ShaderManager(private val assets: AssetManager) {
    private val programCache = mutableMapOf<String, Int>()  // key → GL program ID

    /**
     * Load, compile, and link a shader program.
     * Caches by (vertPath, fragPath) key.
     * Must be called from the GL thread.
     */
    fun getProgram(vertPath: String, fragPath: String): Int { ... }

    private fun loadSource(path: String): String =
        assets.open(path).bufferedReader().readText()

    private fun compileShader(type: Int, source: String): Int { ... }  // logs errors
    private fun linkProgram(vert: Int, frag: Int): Int { ... }

    fun release() { programCache.values.forEach { glDeleteProgram(it) } }
}
```

### Common uniforms (in `common_uniforms.glsl`)
Every shader includes:
```glsl
#version 300 es
precision highp float;

uniform mat4 u_mvp;             // model-view-projection matrix
uniform float u_theme;          // 0.0=day, 1.0=night, 2.0=red-cockpit
uniform float u_time_sec;       // for animations (marker beacon flash, etc.)
```

---

## 3. VAO/VBO Geometry Helpers

```kotlin
// rendering/gl/GlBuffer.kt

class GlBuffer(private val target: Int = GL_ARRAY_BUFFER) {
    val id: Int = IntArray(1).also { glGenBuffers(1, it, 0) }[0]

    fun upload(data: FloatArray, usage: Int = GL_STATIC_DRAW) {
        glBindBuffer(target, id)
        glBufferData(target, data.size * 4, FloatBuffer.wrap(data), usage)
    }

    fun uploadDynamic(data: FloatArray) = upload(data, GL_DYNAMIC_DRAW)
}

class GlVao {
    val id: Int = IntArray(1).also { glGenVertexArrays(1, it, 0) }[0]

    fun bind() = glBindVertexArray(id)
    fun unbind() = glBindVertexArray(0)
}

/** Builds a unit circle as a triangle strip (for gauge dials, range rings). */
fun buildCircleStrip(segments: Int = 64): FloatArray { ... }

/** Builds an arc segment between startAngleDeg and endAngleDeg as a triangle strip. */
fun buildArcStrip(innerRadius: Float, outerRadius: Float,
                  startDeg: Float, endDeg: Float, segments: Int = 32): FloatArray { ... }

/** Builds a unit quad (two triangles). Used for textured panels, tapes, needles. */
fun buildQuad(): FloatArray = floatArrayOf(
    -0.5f, -0.5f,  0f, 0f,
     0.5f, -0.5f,  1f, 0f,
    -0.5f,  0.5f,  0f, 1f,
     0.5f,  0.5f,  1f, 1f,
)
```

---

## 4. Texture Atlas Management

### Dial face pre-rendering
Gauge dial faces are rendered once at startup (and on theme change) to off-screen textures:
```kotlin
// rendering/gl/TextureAtlas.kt

class GaugeTextureAtlas(private val shaderManager: ShaderManager) {
    private val textureIds = mutableMapOf<GaugeType, Int>()

    /**
     * Renders all static gauge backgrounds to FBOs.
     * Must be called from GL thread at startup.
     * On theme change, call again — textures are redrawn.
     */
    fun buildAll(theme: Theme) { ... }

    fun getTexture(type: GaugeType): Int = textureIds.getValue(type)

    fun release() { ... }
}
```

### Font atlas
Glyphs for the OpenGL text renderer (used by G1000 softkey labels, gauge callouts):
```kotlin
class FontAtlas(private val typeface: Typeface, private val glyphSizePx: Int) {
    val textureId: Int
    val glyphUvMap: Map<Char, RectF>   // character → UV rect in atlas

    init {
        // Render ASCII 32–126 to a 512×512 Android Bitmap using Canvas
        // Upload to GL texture
        // Build UV map
    }
}
```

---

## 5. Render Thread Architecture

Each `GLSurfaceView.Renderer` implementation:
```kotlin
abstract class BaseRenderer : GLSurfaceView.Renderer {
    protected lateinit var shaderManager: ShaderManager
    protected lateinit var gaugeAtlas: GaugeTextureAtlas

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_DEPTH_TEST)
        shaderManager = ShaderManager(assets)
        gaugeAtlas = GaugeTextureAtlas(shaderManager)
        gaugeAtlas.buildAll(currentTheme)
        onGlReady()
    }

    override fun onDrawFrame(gl: GL10) {
        val startNs = System.nanoTime()
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        drawFrame()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        if (elapsedMs > 4.0) {
            Log.w("EFB", "Frame overrun: %.1f ms".format(elapsedMs))
        }
    }

    abstract fun onGlReady()
    abstract fun drawFrame()
}
```

### Frame data flow
```
SimDataViewModel (StateFlow on main thread)
        │
        │ collect() in Renderer.onDrawFrame()
        ↓
    snapshot: SimSnapshot   (captured at frame start)
        │
    drawFrame() passes snapshot to each gauge/layer
        │
    GL draw calls (uniforms, VAO draw)
```

Since `StateFlow` is thread-safe, the render thread can read the latest snapshot without locking. The snapshot is an immutable data class captured by value at frame start.

---

## 6. MSAA Configuration

For G1000 PFD (G-02 attitude indicator, tape anti-aliasing):
```kotlin
setEGLConfigChooser(8, 8, 8, 8, 16, 4)  // last param = 4x MSAA samples
```

For Steam Gauges (smooth arc edges per SG-01 through SG-06):
Same config. On ES 2.0 fallback: use `(8, 8, 8, 8, 16, 0)` — no MSAA.

---

## 7. Capability Flags

```kotlin
// rendering/gl/GlCapabilities.kt

object GlCapabilities {
    var isEs3: Boolean = false
        private set
    var maxTextureSize: Int = 2048
        private set

    fun detect(gl: GL10) {
        val versionStr = GLES30.glGetString(GL_VERSION) ?: ""
        isEs3 = versionStr.contains("OpenGL ES 3")
        maxTextureSize = IntArray(1).also {
            GLES30.glGetIntegerv(GL_MAX_TEXTURE_SIZE, it, 0)
        }[0]
    }
}
```

All renderers check `GlCapabilities.isEs3` before using ES 3.0-only features (VAOs, MSAA, etc.) and fall back to simpler equivalents if false.

---

## 8. Tests

JVM unit tests (no GL — pure math):
```kotlin
// Test matrix math, arc geometry building, UV calculations
@Test fun buildArcStrip_correctVertexCount() { ... }
@Test fun buildCircleStrip_closesCorrectly() { ... }
@Test fun fontAtlasUvMap_coversAsciiRange() { ... }  // stub — UV values mocked
```

Instrumented tests (requires device/emulator with GPU):
- `GlSurfaceRenderTest` — creates a `GLSurfaceView` offscreen, renders one frame, reads back pixel buffer, asserts non-black output.

---

## Acceptance Criteria Mapping

| Req | Satisfied by |
|---|---|
| §1.1.2 (shared EGL context) | `EglContextManager` |
| §1.1.2 (GLSL uniforms for transforms) | `common_uniforms.glsl`, per-gauge uniforms |
| §1.1.2 (pre-rendered dial faces) | `GaugeTextureAtlas.buildAll()` |
| §1.1.2 (60fps / 4ms budget) | `BaseRenderer.onDrawFrame()` timing |
| §1.1.2 (ES 2.0 fallback) | `SharedContextFactory` fallback, `GlCapabilities` |
| NFR-P01 (60fps) | Frame time logging; CI perf test on device |
| NFR-P06 (< 512MB) | Texture atlas size budgeting |
