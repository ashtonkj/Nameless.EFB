package com.nameless.efb.rendering.g1000

import com.nameless.efb.rendering.gauge.GlViewport

/**
 * Viewport layout for the G1000 PFD at 1280×800 nominal resolution.
 *
 * All coordinates scale proportionally when the surface is a different size.
 * GL convention: y = 0 is the bottom of the framebuffer.
 *
 * Nominal layout (screen top = 0):
 * ```
 *  y=0  ┌──────────────────────────────────────────────────────────────────┐
 *  y=30 │ Mode annunciator strip (G-31)                                    │
 *       ├──────┬──────────────────────────────────────────────┬────────────┤
 *       │ IAS  │            Attitude indicator (G-01)         │ ALT (G-03) │
 *       │ Tape │                                              │            │
 *       │ G-02 │                                              │ VSI (G-04) │
 * y=580 ├──────┼──────────────────────────────────────────────┴────────────┤
 *       │Inset │      HSI (G-05) + Nav status box (G-06)                   │
 *       │ Map  ├──────────────────────────────────────────────────────────┤
 *       │ G-07 │ Wind G-08 │ OAT/TAS G-10 │ Marker beacons G-09           │
 * y=800 └──────┴────────────────────────────────────────────────────────── ┘
 *        x=0   x=180                               x=1100              x=1280
 * ```
 */
class G1000PfdLayout(totalWidth: Int, totalHeight: Int) {

    // Scale factors from nominal 1280×800.
    private val sx = totalWidth  / 1280f
    private val sy = totalHeight /  800f

    private fun px(nominalX: Int) = (nominalX * sx).toInt()
    private fun py(nominalY: Int) = (nominalY * sy).toInt()

    // Helper: build viewport, clamping to at-least-1-pixel dimensions.
    private fun vp(x: Int, y: Int, w: Int, h: Int) =
        GlViewport(maxOf(0, x), maxOf(0, y), maxOf(1, w), maxOf(1, h))

    // ── Nominal GL y-origins (screen y converted to GL y = totalHeight - screen_y) ──

    private val glH = totalHeight

    // Annunciator strip: screen y=0..30  →  GL y=glH-30 .. glH
    val annunciator  = vp(0, glH - py(30),                    totalWidth,  py(30))

    // IAS tape: x=0..180, screen y=30..580  →  GL y=glH-580 .. glH-30
    val ias          = vp(0,         glH - py(580), px(180),  py(550))

    // Attitude: x=180..1100, screen y=30..580
    val attitude     = vp(px(180),   glH - py(580), px(920),  py(550))

    // Altitude tape: x=1100..1220, screen y=30..580
    val altitude     = vp(px(1100),  glH - py(580), px(120),  py(550))

    // VSI tape: x=1220..1280, screen y=30..580
    val vsi          = vp(px(1220),  glH - py(580), px(60),   py(550))

    // Inset map: 180×150px bottom-left corner of PFD (GL y=0)
    val insetMap     = vp(0, 0,                                px(180),  py(150))

    // HSI area: x=180..1100, screen y=580..770  →  GL y=glH-770 .. glH-580
    val hsi          = vp(px(180),   glH - py(770), px(920),  py(190))

    // Bottom data strip (wind, OAT/TAS, markers): x=180..1280, screen y=770..800  →  GL y=0..30
    val bottomStrip  = vp(px(180),   0,             px(1100), py(30))
}
