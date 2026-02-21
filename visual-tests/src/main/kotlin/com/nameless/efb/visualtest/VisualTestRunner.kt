package com.nameless.efb.visualtest

import android.content.res.AssetManager
import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.rendering.g1000.BaroUnit
import com.nameless.efb.rendering.g1000.G1000PfdRenderer
import com.nameless.efb.rendering.g1000.HsiMode
import com.nameless.efb.rendering.gauge.SteamGaugePanelRenderer
import com.nameless.efb.rendering.gl.Theme
import com.nameless.efb.ui.steam.GaugeState
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10Stub
import kotlinx.coroutines.flow.MutableStateFlow

// ── Render dimensions ─────────────────────────────────────────────────────────

private const val W = 1280
private const val H = 800

// ── Test scenarios ────────────────────────────────────────────────────────────

private data class Scenario(
    val filename: String,
    val width: Int = W,
    val height: Int = H,
    val render: () -> Unit,
)

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
            Readback.capture(scenario.width, scenario.height, File(outputDir, "${scenario.filename}.png"))
            passed++
        } catch (e: Exception) {
            println("FAILED: ${e.message}")
            e.printStackTrace()
            failed++
        }
    }

    GlContext.destroy()

    // ── Generate comparison images for scenario 14 ──────────────────────────
    val refFile = File("docs/reference/G1000Ref.png")
    val outFile = File(outputDir, "14_g1000_ref.png")
    if (refFile.exists() && outFile.exists()) {
        try {
            val refImg = ImageIO.read(refFile)
            val outImg = ImageIO.read(outFile)
            if (refImg.width == outImg.width && refImg.height == outImg.height) {
                val w = refImg.width
                val h = refImg.height

                // XOR (absolute difference) image.
                val xorImg = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val rPx = refImg.getRGB(x, y)
                        val oPx = outImg.getRGB(x, y)
                        val dr = kotlin.math.abs(((rPx shr 16) and 0xFF) - ((oPx shr 16) and 0xFF))
                        val dg = kotlin.math.abs(((rPx shr 8) and 0xFF) - ((oPx shr 8) and 0xFF))
                        val db = kotlin.math.abs((rPx and 0xFF) - (oPx and 0xFF))
                        xorImg.setRGB(x, y, (0xFF shl 24) or (dr shl 16) or (dg shl 8) or db)
                    }
                }
                val xorFile = File(outputDir, "14_g1000_xor.png")
                ImageIO.write(xorImg, "PNG", xorFile)
                println("  XOR diff: ${xorFile.absolutePath}")

                // Side-by-side: reference | output.
                val sbs = BufferedImage(w * 2, h, BufferedImage.TYPE_INT_ARGB)
                val g = sbs.createGraphics()
                g.drawImage(refImg, 0, 0, null)
                g.drawImage(outImg, w, 0, null)
                g.dispose()
                val sbsFile = File(outputDir, "14_g1000_comparison.png")
                ImageIO.write(sbs, "PNG", sbsFile)
                println("  Comparison: ${sbsFile.absolutePath}")
            } else {
                println("  Skipping comparison: size mismatch (ref=${refImg.width}x${refImg.height}, out=${outImg.width}x${outImg.height})")
            }
        } catch (e: Exception) {
            println("  Comparison failed: ${e.message}")
        }
    }

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

    // ── Steam gauge panel — all 14 gauges with non-zero engine values ──────────

    add(Scenario("09_steam_all_gauges") {
        val renderer = SteamGaugePanelRenderer(assets, theme = Theme.DAY)
        renderer.gaugeState = GaugeState(
            airspeedKts      = 135f,
            pitchDeg         = 1f,
            rollDeg          = 3f,
            altFt            = 7500f,
            headingDeg       = 045f,
            vsiFpm           = 100f,
            displayedVsiFpm  = 95f,
            slipDeg          = 2f,
            turnRateDegSec   = 0.5f,
            rpmEng0          = 2450f,
            mapInhg          = 24.5f,
            oilTempDegC      = 98f,
            oilPressPsi      = 72f,
            fuelFlowKgSec    = 0.00278f,   // ~10 LPH AVGAS
            fuelQtyKg        = floatArrayOf(30f, 29f),
            egtDegC          = floatArrayOf(690f, 695f, 685f, 700f, 688f, 692f),
            busVolts         = 28.1f,
            battAmps         = 2f,
            suctionInhg      = 5.0f,
        )
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, W, H)
        renderer.onDrawFrame(gl)
    })

    // ── G1000 PFD — extreme attitude: pitch 20°, roll 45° (pitch ladder visible) ──

    add(Scenario("10_pfd_extreme_attitude") {
        val extremeSnap = cruiseSnap.copy(
            pitchDeg      = 20f,
            rollDeg       = 45f,
            vviFpm        = 2800f,
        )
        val simData  = MutableStateFlow<SimSnapshot?>(extremeSnap)
        val renderer = G1000PfdRenderer(assets, simData, insetMap = null, theme = Theme.DAY)
        renderer.hsiMode  = HsiMode.ARC
        renderer.baroUnit = BaroUnit.HPA
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, W, H)
        renderer.onDrawFrame(gl)
    })

    // ── G1000 PFD — night theme (should look darker than 07_pfd_night) ────────

    add(Scenario("11_pfd_night_extreme") {
        val extremeSnap = cruiseSnap.copy(
            pitchDeg   = -5f,
            rollDeg    = -20f,
            iasKts     = 160f,
            vviFpm     = -800f,
        )
        val simData  = MutableStateFlow<SimSnapshot?>(extremeSnap)
        val renderer = G1000PfdRenderer(assets, simData, insetMap = null, theme = Theme.NIGHT)
        renderer.hsiMode  = HsiMode.FULL_360
        renderer.baroUnit = BaroUnit.HPA
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, W, H)
        renderer.onDrawFrame(gl)
    })

    // ── Steam gauge panel — idle (all values near minimum) ──────────────────

    add(Scenario("12_steam_idle") {
        val renderer = SteamGaugePanelRenderer(assets, theme = Theme.DAY)
        renderer.gaugeState = GaugeState(
            airspeedKts     = 0f,
            pitchDeg        = 0f,
            rollDeg         = 0f,
            altFt           = 0f,
            headingDeg      = 0f,
            vsiFpm          = 0f,
            displayedVsiFpm = 0f,
            rpmEng0         = 600f,
            mapInhg         = 14f,
            oilTempDegC     = 30f,
            oilPressPsi     = 25f,
            fuelQtyKg       = floatArrayOf(60f, 60f),
            egtDegC         = FloatArray(6) { 300f },
            busVolts        = 28f,
            suctionInhg     = 4.8f,
        )
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, W, H)
        renderer.onDrawFrame(gl)
    })

    // ── Steam gauge panel — redline (values at limits) ──────────────────────

    add(Scenario("13_steam_redline") {
        val renderer = SteamGaugePanelRenderer(assets, theme = Theme.DAY)
        renderer.gaugeState = GaugeState(
            airspeedKts     = 160f,
            pitchDeg        = -5f,
            rollDeg         = 30f,
            altFt           = 14000f,
            headingDeg      = 330f,
            vsiFpm          = 1500f,
            displayedVsiFpm = 1500f,
            rpmEng0         = 2700f,
            mapInhg         = 30f,
            oilTempDegC     = 120f,
            oilPressPsi     = 100f,
            fuelQtyKg       = floatArrayOf(5f, 4f),
            egtDegC         = floatArrayOf(900f, 920f, 880f, 950f, 870f, 910f),
            busVolts        = 13.5f,
            suctionInhg     = 5.2f,
        )
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, W, H)
        renderer.onDrawFrame(gl)
    })

    // ── G1000 PFD — pixel-perfect match to docs/reference/G1000Ref.png ─────
    // Reference state: level flight, 360° HDG, CRS 350°, alt -20 ft, IAS 30 kt,
    // baro 29.92 inHg, OAT 0°C, TAS 0 kt, full rose HSI, TRAFFIC on.
    // Compare: visual-tests/output/14_g1000_ref.png vs docs/reference/G1000Ref.png

    val g1000RefSnap = SimSnapshot(
        latitude        = -26.1392,
        longitude       = 28.2460,
        elevationM      = -20.0 * 0.3048,  // -20 ft for ref altitude readout
        groundspeedMs   = 0f,
        pitchDeg        = 0f,
        rollDeg         = 0f,
        magHeadingDeg   = 360f,
        groundTrackDeg  = 360f,
        iasKts          = 30f,
        tasKts          = 0f,
        vviFpm          = 0f,
        oatDegc         = 0f,
        barometerInhg   = 29.92f,
        nav1ObsDeg      = 350f,
        nav1HdefDot     = -0.3f,
        apHeadingBugDeg = 360f,
        apAltitudeFt    = 20f,   // altitude bug at 20 ft (visible on tape)
        com1ActiveHz    = 136_975_000,
        com1StandbyHz   = 118_000_000,
        com2ActiveHz    = 136_975_000,
        nav1ActiveHz    = 108_000_000,
        nav1StandbyHz   = 117_950_000,
        transponderCode = 0,
        transponderMode = 4,
        trafficCount    = 1,
    )

    // Reference image is 515x389 — render at same resolution for direct comparison.
    val refW = 515
    val refH = 389

    add(Scenario("14_g1000_ref", width = refW, height = refH) {
        val simData  = MutableStateFlow<SimSnapshot?>(g1000RefSnap)
        val renderer = G1000PfdRenderer(assets, simData, insetMap = null, theme = Theme.DAY)
        renderer.hsiMode  = HsiMode.FULL_360
        renderer.baroUnit = BaroUnit.INHG
        renderer.onSurfaceCreated(gl, eglConf)
        renderer.onSurfaceChanged(gl, refW, refH)
        renderer.onDrawFrame(gl)
    })
}
