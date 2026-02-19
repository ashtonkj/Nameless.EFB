package com.nameless.efb.rendering.gauge

/**
 * Computes [GlViewport]s for all 14 steam gauge instruments from the total
 * framebuffer dimensions.
 *
 * Default layout (C172 six-pack + engine instruments):
 * ```
 * ┌─────┬─────┬─────┐   ─┐
 * │ ASI │  AI │ ALT │    │  primary (top 60 %)
 * ├─────┼─────┼─────┤    │
 * │ TC  │  DI │ VSI │   ─┘
 * ├─────┼─────┼─────┤   ─┐
 * │ RPM │ MAP │ OIL │    │  engine (bottom 40 %)
 * ├─────┼─────┼─────┤    │
 * │  FF │  FQ │ EGT │    │
 * └─────┴─────┴─────┘    │
 *  (EL + SUC overlay FQ) ─┘
 * ```
 *
 * GL convention: y = 0 is the bottom of the framebuffer.
 *
 * @param totalWidth  framebuffer width in pixels
 * @param totalHeight framebuffer height in pixels
 */
class GaugePanelLayout(totalWidth: Int, totalHeight: Int) {

    private val colW = totalWidth / 3

    // Primary 6-pack: top 60 % split into 2 equal rows.
    private val primaryRowH = (totalHeight * 0.30).toInt()

    // Engine instruments: bottom 40 % split into 2 equal rows.
    private val engineRowH = (totalHeight * 0.20).toInt()

    // Row y-origins (GL y=0 at bottom).
    private val r0 = 0                           // engine bottom row
    private val r1 = engineRowH                  // engine top row
    private val r2 = 2 * engineRowH              // primary bottom row
    private val r3 = r2 + primaryRowH            // primary top row

    // ── Primary flight instruments ────────────────────────────────────────────

    val asi: GlViewport = GlViewport(0,        r3, colW, primaryRowH)
    val ai:  GlViewport = GlViewport(colW,     r3, colW, primaryRowH)
    val alt: GlViewport = GlViewport(colW * 2, r3, colW, primaryRowH)

    val tc:  GlViewport = GlViewport(0,        r2, colW, primaryRowH)
    val di:  GlViewport = GlViewport(colW,     r2, colW, primaryRowH)
    val vsi: GlViewport = GlViewport(colW * 2, r2, colW, primaryRowH)

    // ── Engine instruments ────────────────────────────────────────────────────

    val rpm: GlViewport = GlViewport(0,        r1, colW, engineRowH)
    val map: GlViewport = GlViewport(colW,     r1, colW, engineRowH)
    val oil: GlViewport = GlViewport(colW * 2, r1, colW, engineRowH)

    val ff:  GlViewport = GlViewport(0,        r0, colW, engineRowH)
    val fq:  GlViewport = GlViewport(colW,     r0, colW, engineRowH)
    val egt: GlViewport = GlViewport(colW * 2, r0, colW, engineRowH)

    // Electrical and suction share the ff / fq cells when MAP is absent.
    val elec:    GlViewport = ff
    val suction: GlViewport = fq
}
