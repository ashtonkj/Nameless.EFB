package com.nameless.efb.rendering.g1000

import com.nameless.efb.rendering.gauge.GlViewport

/**
 * Viewport layout for the G1000 PFD at 1280x800 nominal resolution.
 * Matches the Garmin G1000 CRG Rev. R layout proportions.
 *
 * GL convention: y = 0 is the bottom of the framebuffer.
 */
class G1000PfdLayout(totalWidth: Int, totalHeight: Int) {

    // Scale factors from nominal 1280x800.
    private val sx = totalWidth  / 1280f
    private val sy = totalHeight /  800f

    private fun px(nominalX: Int) = (nominalX * sx).toInt()
    private fun py(nominalY: Int) = (nominalY * sy).toInt()

    private fun vp(x: Int, y: Int, w: Int, h: Int) =
        GlViewport(maxOf(0, x), maxOf(0, y), maxOf(1, w), maxOf(1, h))

    private val glH = totalHeight

    // NAV/COM frequency bar: screen y=0..45
    val navComBar    = vp(0, glH - py(45), totalWidth, py(45))

    // Full attitude background: entire area between navComBar and softkeyBar.
    // screen y=45..775, full width — drawn first as background layer.
    val attitudeFull = vp(0, py(25), totalWidth, py(730))

    // IAS tape: x=30..140, screen y=75..380 (inset from edges, not full height)
    val ias          = vp(px(30),   glH - py(380), px(110), py(305))

    // Attitude overlay area (for pitch ladder, aircraft symbol, etc.): x=140..1060, screen y=45..390
    val attitude     = vp(px(140),  glH - py(390), px(920), py(345))

    // Altitude tape: x=1060..1190, screen y=75..380 (inset from edges, not full height)
    val altitude     = vp(px(1060), glH - py(380), px(130), py(305))

    // VSI tape: x=1190..1250, screen y=75..380 (inset from edges, not full height)
    val vsi          = vp(px(1190), glH - py(380), px(60),  py(305))

    // HSI area: x=140..1060, screen y=390..775 (same width as attitude)
    val hsi          = vp(px(140),  glH - py(775), px(920), py(385))

    // Dark flanking panels — full height from nav bar to softkey bar.
    // Left panel covers x=0..140 (entire left side behind IAS tape).
    val leftFlank    = vp(0,        py(25), px(140), py(730))
    // Right panel covers x=1060..1280 (entire right side behind ALT/VSI).
    val rightFlank   = vp(px(1060), py(25), px(220), py(730))

    // Inset map: bottom-left corner, screen y=580..775, x=0..160
    val insetMap     = vp(0, py(25), px(160), py(195))

    // Softkey bar: screen y=775..800
    val softkeyBar   = vp(0, 0, totalWidth, py(25))

    // Legacy viewports (kept for API compatibility, minimized)
    val annunciator  = vp(0, 0, 1, 1)
    val bottomStrip  = vp(0, py(25), totalWidth, py(30))
}
