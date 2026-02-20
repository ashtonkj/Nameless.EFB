package com.nameless.efb.visualtest

import android.content.res.AssetManager
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.rendering.g1000.BaroUnit
import com.nameless.efb.rendering.g1000.G1000PfdRenderer
import com.nameless.efb.rendering.g1000.HsiMode
import com.nameless.efb.rendering.gauge.SteamGaugePanelRenderer
import com.nameless.efb.rendering.gl.Theme
import com.nameless.efb.ui.steam.GaugeState
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10Stub
import kotlinx.coroutines.flow.MutableStateFlow

// ── Render dimensions ─────────────────────────────────────────────────────────

private const val W = 1280
private const val H = 800

// ── Test scenarios ────────────────────────────────────────────────────────────

private data class Scenario(val filename: String, val render: () -> Unit)

fun main(args: Array<String>) {
    val outputDir = File(args.firstOrNull() ?: "visual-tests/output")
    outputDir.mkdirs()

    println("=== Nameless EFB — Visual Test Suite ===")
    println("Output: ${outputDir.absolutePath}")
    println("Resolution: ${W}×${H}")
    println()

    val assetRoot = File("app/src/main/assets")
    require(assetRoot.exists()) {
        "Asset directory not found: ${assetRoot.absolutePath}\n" +
        "Run from project root: ./gradlew :visual-tests:runVisualTests"
    }
    val assets = AssetManager(assetRoot)

    val gl      = GL10Stub
    val eglConf = EGLConfig()

    GlContext.create(W, H)

    val scenarios = buildScenarios(assets, gl, eglConf)
    var passed = 0
    var failed = 0

    for (scenario in scenarios) {
        print("  [${scenario.filename}] ")
        try {
            scenario.render()
            Readback.capture(W, H, File(outputDir, "${scenario.filename}.png"))
            passed++
        } catch (e: Exception) {
            println("FAILED: ${e.message}")
            e.printStackTrace()
            failed++
        }
    }

    GlContext.destroy()

    println()
    println("$passed passed, $failed failed")
    if (failed > 0) System.exit(1)
}

// ── Scenario builders ─────────────────────────────────────────────────────────

private fun buildScenarios(
    assets: AssetManager,
    gl: GL10Stub,
    eglConf: EGLConfig,
): List<Scenario> = buildList {

    // ── Steam gauge panel — C172 cruise ───────────────────────────────────────

    add(Scenario("01_steam_cruise") {
        val renderer = SteamGaugePanelRenderer(assets, theme = Theme.DAY)
        renderer.gaugeState = GaugeState(
            airspeedKts     = 120f,
            pitchDeg        = 2f,
            rollDeg         = 0f,
            altFt           = 6500f,
            headingDeg      = 270f,
            vsiFpm          = 0f,
            displayedVsiFpm = 0f,
            rpmEng0         = 2350f,
            mapInhg         = 23.8f,
            oilTempDegC     = 95f,
            oilPressPsi     = 75f,
            fuelQtyKg       = floatArrayOf(45f, 46f),
            egtDegC         = FloatArray(6) { 680f },
            busVolts        = 28.1f,
            suctionInhg     = 5.0f,
        )
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, W, H)
        renderer.onDrawFrame(gl)
    })

    // ── Steam gauge panel — approach (low, slow) ──────────────────────────────

    add(Scenario("02_steam_approach") {
        val renderer = SteamGaugePanelRenderer(assets, theme = Theme.DAY)
        renderer.gaugeState = GaugeState(
            airspeedKts     = 80f,
            pitchDeg        = -2f,
            rollDeg         = 5f,
            altFt           = 1200f,
            headingDeg      = 180f,
            vsiFpm          = -500f,
            displayedVsiFpm = -480f,
            rpmEng0         = 1800f,
            oilTempDegC     = 88f,
            oilPressPsi     = 65f,
            fuelQtyKg       = floatArrayOf(22f, 22f),
            egtDegC         = FloatArray(6) { 640f },
            busVolts        = 27.8f,
            suctionInhg     = 4.9f,
        )
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, W, H)
        renderer.onDrawFrame(gl)
    })

    // ── Steam gauge panel — night theme, banked ───────────────────────────────

    add(Scenario("03_steam_night") {
        val renderer = SteamGaugePanelRenderer(assets, theme = Theme.NIGHT)
        renderer.gaugeState = GaugeState(
            airspeedKts     = 150f,
            pitchDeg        = 0f,
            rollDeg         = -15f,
            altFt           = 8000f,
            headingDeg      = 90f,
            vsiFpm          = 200f,
            displayedVsiFpm = 190f,
            rpmEng0         = 2500f,
            oilTempDegC     = 100f,
            oilPressPsi     = 80f,
            fuelQtyKg       = floatArrayOf(55f, 54f),
            egtDegC         = FloatArray(6) { 710f },
            busVolts        = 28.3f,
            suctionInhg     = 5.1f,
        )
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, W, H)
        renderer.onDrawFrame(gl)
    })

    // ── Common cruise snapshot for PFD tests ──────────────────────────────────
    // SimSnapshot uses elevationM (metres), not altFt.

    val cruiseSnap = SimSnapshot(
        latitude        = -26.1392,
        longitude       = 28.2460,
        elevationM      = 12000 * 0.3048,   // FL120 ≈ 3658 m
        iasKts          = 180f,
        tasKts          = 196f,
        pitchDeg        = 2f,
        rollDeg         = 0f,
        magHeadingDeg   = 270f,
        groundTrackDeg  = 271f,
        groundspeedMs   = (180f * 1852f / 3600f),
        vviFpm          = 0f,
        oatDegc         = -5f,
        barometerInhg   = 29.92f,
        windDirDeg      = 250f,
        windSpeedKt     = 25f,
        apHeadingBugDeg = 270f,
        apAltitudeFt    = 12000f,
        com1ActiveHz    = 118_100_000,
        com1StandbyHz   = 121_500_000,
        nav1ActiveHz    = 108_100_000,
        nav1ObsDeg      = 270f,
        transponderCode = 7000,
        transponderMode = 4,
    )

    // ── G1000 PFD — cruise, ARC HSI, hPa baro ────────────────────────────────

    add(Scenario("04_pfd_cruise_arc") {
        val simData  = MutableStateFlow<SimSnapshot?>(cruiseSnap)
        val renderer = G1000PfdRenderer(assets, simData, insetMap = null, theme = Theme.DAY)
        renderer.hsiMode  = HsiMode.ARC
        renderer.baroUnit = BaroUnit.HPA
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, W, H)
        renderer.onDrawFrame(gl)
    })

    // ── G1000 PFD — full rose HSI ─────────────────────────────────────────────

    add(Scenario("05_pfd_cruise_rose") {
        val simData  = MutableStateFlow<SimSnapshot?>(cruiseSnap)
        val renderer = G1000PfdRenderer(assets, simData, insetMap = null, theme = Theme.DAY)
        renderer.hsiMode  = HsiMode.FULL_360
        renderer.baroUnit = BaroUnit.HPA
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, W, H)
        renderer.onDrawFrame(gl)
    })

    // ── G1000 PFD — ILS approach, banked, CDI deflected ──────────────────────

    add(Scenario("06_pfd_approach") {
        val approachSnap = cruiseSnap.copy(
            elevationM      = 2000 * 0.3048,   // 2000 ft
            iasKts          = 100f,
            pitchDeg        = -3f,
            rollDeg         = 15f,
            magHeadingDeg   = 90f,
            vviFpm          = -700f,
            barometerInhg   = 30.01f,
            nav1HdefDot     = 0.5f,
            nav1VdefDot     = -0.3f,
            windDirDeg      = 100f,
            windSpeedKt     = 10f,
        )
        val simData  = MutableStateFlow<SimSnapshot?>(approachSnap)
        val renderer = G1000PfdRenderer(assets, simData, insetMap = null, theme = Theme.DAY)
        renderer.hsiMode  = HsiMode.ARC
        renderer.baroUnit = BaroUnit.HPA
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, W, H)
        renderer.onDrawFrame(gl)
    })

    // ── G1000 PFD — night theme ───────────────────────────────────────────────

    add(Scenario("07_pfd_night") {
        val simData  = MutableStateFlow<SimSnapshot?>(cruiseSnap)
        val renderer = G1000PfdRenderer(assets, simData, insetMap = null, theme = Theme.NIGHT)
        renderer.hsiMode  = HsiMode.ARC
        renderer.baroUnit = BaroUnit.HPA
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, W, H)
        renderer.onDrawFrame(gl)
    })

    // ── G1000 PFD — disconnected (null snapshot) ──────────────────────────────

    add(Scenario("08_pfd_no_data") {
        val simData  = MutableStateFlow<SimSnapshot?>(null)
        val renderer = G1000PfdRenderer(assets, simData, insetMap = null, theme = Theme.DAY)
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, W, H)
        renderer.onDrawFrame(gl)
    })
}
