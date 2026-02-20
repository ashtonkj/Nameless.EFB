plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

val lwjglVersion = "3.3.4"

dependencies {
    // Real JVM libraries (not Android)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
    implementation(libs.kotlinx.serialization.json)

    // LWJGL: EGL + OpenGL ES 3.0 bindings
    implementation("org.lwjgl:lwjgl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-egl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-opengles:$lwjglVersion")
    // Native JNI bridges (loads system libGLESv2.so and libEGL.so)
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-opengles:$lwjglVersion:natives-linux")
}

// ── Sync a curated subset of app renderer sources into the build dir ─────────
//
// We share source files directly instead of creating a library dependency so
// the renderers compile against our Android API shims rather than the real SDK.
// Files that drag in Android-only dependencies (MapRenderer, ViewModel, etc.)
// are replaced by minimal stubs in src/main/kotlin.
val syncRendererSources by tasks.registering(Sync::class) {
    from("${rootProject.projectDir}/app/src/main/java") {
        include(
            // Connectivity / data
            "com/nameless/efb/data/connectivity/SimSnapshot.kt",

            // Domain — gauge (pure Kotlin / kotlinx-serialization only)
            "com/nameless/efb/domain/gauge/AircraftProfile.kt",
            "com/nameless/efb/domain/gauge/AlertType.kt",
            "com/nameless/efb/domain/gauge/FuelType.kt",
            "com/nameless/efb/domain/gauge/GaugeMath.kt",
            "com/nameless/efb/domain/gauge/SpringDamper.kt",
            "com/nameless/efb/domain/gauge/CustomDatarefBinding.kt",
            "com/nameless/efb/domain/gauge/GaugeLayoutItem.kt",
            "com/nameless/efb/domain/gauge/GaugeParameter.kt",
            "com/nameless/efb/domain/gauge/GaugeSizeClass.kt",
            "com/nameless/efb/domain/gauge/GaugeType.kt",

            // GL framework
            "com/nameless/efb/rendering/gl/BaseRenderer.kt",
            "com/nameless/efb/rendering/gl/FontAtlas.kt",
            "com/nameless/efb/rendering/gl/GaugeTextureAtlas.kt",
            "com/nameless/efb/rendering/gl/GlBuffer.kt",
            "com/nameless/efb/rendering/gl/GlCapabilities.kt",
            "com/nameless/efb/rendering/gl/ShaderManager.kt",
            "com/nameless/efb/rendering/gl/Theme.kt",

            // Steam gauge panel
            "com/nameless/efb/rendering/gauge/GaugePanelLayout.kt",
            "com/nameless/efb/rendering/gauge/GlViewport.kt",
            "com/nameless/efb/rendering/gauge/SteamGaugePanelRenderer.kt",

            // G1000 PFD (PfdInsetMap.kt is replaced by a stub — avoids MapRenderer dep)
            "com/nameless/efb/rendering/g1000/BaroUnit.kt",
            "com/nameless/efb/rendering/g1000/G1000Colours.kt",
            "com/nameless/efb/rendering/g1000/G1000PfdLayout.kt",
            "com/nameless/efb/rendering/g1000/G1000PfdMath.kt",
            "com/nameless/efb/rendering/g1000/G1000PfdRenderer.kt",
            "com/nameless/efb/rendering/g1000/HsiMode.kt",
            "com/nameless/efb/rendering/g1000/WindMode.kt",
        )
    }
    into(layout.buildDirectory.dir("generated/appSources"))
}

sourceSets {
    main {
        kotlin.srcDirs(
            "src/main/kotlin",                                            // shims, stubs, test runner
            layout.buildDirectory.dir("generated/appSources"),            // synced app sources
        )
    }
}

tasks.compileKotlin {
    dependsOn(syncRendererSources)
}

// ── Run task — sets EGL_PLATFORM=surfaceless for headless Mesa rendering ─────
tasks.register<JavaExec>("runVisualTests") {
    group = "verification"
    description = "Renders OpenGL screenshots to visual-tests/output/ using Mesa EGL (no display needed)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.nameless.efb.visualtest.VisualTestRunnerKt")
    // Run from project root so relative paths (app/src/main/assets) resolve correctly.
    workingDir = rootProject.projectDir
    // Force Mesa's EGL vendor over NVIDIA's (NVIDIA surfaceless pbuffer is unreliable).
    // __EGL_VENDOR_LIBRARY_FILENAMES overrides GLVND's default vendor discovery.
    environment("EGL_PLATFORM", "surfaceless")
    environment("__EGL_VENDOR_LIBRARY_FILENAMES", "/usr/share/glvnd/egl_vendor.d/50_mesa.json")
    // Use Mesa's LLVM-accelerated software rasterizer (no GPU driver required).
    environment("GALLIUM_DRIVER", "llvmpipe")
    val outputDir = "${rootProject.projectDir}/visual-tests/output"
    args(outputDir)
    dependsOn(tasks.classes)
}
