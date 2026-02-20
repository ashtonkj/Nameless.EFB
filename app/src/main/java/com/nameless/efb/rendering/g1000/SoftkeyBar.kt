package com.nameless.efb.rendering.g1000

import com.nameless.efb.rendering.g1000.mfd.MfdPageManager
import com.nameless.efb.rendering.gl.FontAtlas

/** Softkey display context — determines which 12 labels are shown. */
enum class SoftkeyContext {
    PFD_MAIN,
    MFD_MAP,
    MFD_FPL,
    MFD_PROC,
    MFD_NRST,
    MFD_AUX,
}

/**
 * Definition for one of the 12 context-sensitive softkeys (G-19).
 *
 * @param label    Display label; use "\n" for a two-line label.
 * @param active   True when the associated function is currently enabled (draws lighter background).
 * @param onPress  Action invoked when the user taps this key.
 */
data class SoftkeyDefinition(
    val label: String,
    val active: Boolean = false,
    val onPress: () -> Unit = {},
)

// G1000 nominal PFD width; each softkey is 1/12 of this.
private const val PFD_WIDTH_PX = 1280

/**
 * G1000 softkey bar renderer (G-19).
 *
 * Renders 12 context-sensitive softkey labels across the bottom of the display.
 * Labels and actions change based on the active [SoftkeyContext].
 *
 * Each key is [PFD_WIDTH_PX]/12 ≈ 106 px wide, 44 px tall.
 *
 * @param fontAtlas       Glyph atlas for text rendering (null = no-op in JVM tests).
 * @param mfdPageManager  MFD page manager for softkey-triggered page changes.
 */
class SoftkeyBar(
    private val fontAtlas: FontAtlas? = null,
    private val mfdPageManager: MfdPageManager? = null,
) {

    /** Width of each softkey in pixels at nominal 1280px resolution. */
    val keyWidth: Int = PFD_WIDTH_PX / 12   // 106px per key

    /** Currently active softkey context. Changes based on the active G1000 page. */
    var currentContext: SoftkeyContext = SoftkeyContext.PFD_MAIN

    /**
     * Softkey definitions per context — 12 keys per context.
     *
     * Accessible (internal) so tests can verify the context map size.
     */
    internal val softkeyContexts: Map<SoftkeyContext, List<SoftkeyDefinition>> = mapOf(
        SoftkeyContext.PFD_MAIN to listOf(
            SoftkeyDefinition("INSET",        onPress = { /* toggleInsetMap() */ }),
            SoftkeyDefinition("PFD",          active = true),
            SoftkeyDefinition("OBS",          onPress = { /* toggleObs() */ }),
            SoftkeyDefinition("CDI",          onPress = { /* cycleCdi() */ }),
            SoftkeyDefinition("DME",          onPress = { /* toggleDme() */ }),
            SoftkeyDefinition("TMRS",         onPress = { /* openTimers() */ }),
            SoftkeyDefinition(""),                                // blank
            SoftkeyDefinition("CLR",          onPress = { clearSoftkeys() }),
            SoftkeyDefinition("BRG1",         onPress = { /* cycleBrg1() */ }),
            SoftkeyDefinition("NRST",         onPress = { /* openNrst() */ }),
            SoftkeyDefinition("ALT\nUNITS",   onPress = { /* toggleAltUnits() */ }),
            SoftkeyDefinition("PFD\nMENU",    onPress = { /* openPfdMenu() */ }),
        ),
        SoftkeyContext.MFD_MAP to listOf(
            SoftkeyDefinition("ENGINE",       onPress = { /* toggleEis() */ }),
            SoftkeyDefinition("MAP",          active = true),
            SoftkeyDefinition("NRST",         onPress = { /* openNrst() */ }),
            SoftkeyDefinition("PROC",         onPress = { /* openProc() */ }),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition("CLR",          onPress = { clearSoftkeys() }),
            SoftkeyDefinition("TOPO",         onPress = { /* toggleTopo() */ }),
            SoftkeyDefinition("TERRAIN",      onPress = { /* toggleTerrain() */ }),
            SoftkeyDefinition("TRAFFIC",      onPress = { /* toggleTraffic() */ }),
            SoftkeyDefinition("MAP\nMENU",    onPress = { /* openMapMenu() */ }),
        ),
        SoftkeyContext.MFD_FPL to listOf(
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition("CLR",          onPress = { clearSoftkeys() }),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
        ),
        SoftkeyContext.MFD_PROC to listOf(
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition("CLR",          onPress = { clearSoftkeys() }),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
        ),
        SoftkeyContext.MFD_NRST to listOf(
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition("CLR",          onPress = { clearSoftkeys() }),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
        ),
        SoftkeyContext.MFD_AUX to listOf(
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition("CLR",          onPress = { clearSoftkeys() }),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
            SoftkeyDefinition(""),
        ),
    )

    /**
     * Handles a tap at horizontal pixel coordinate [x] in the softkey bar.
     *
     * Maps [x] to a key index (0–11) and invokes that key's [SoftkeyDefinition.onPress].
     */
    fun onTap(x: Float) {
        val index = (x / keyWidth).toInt().coerceIn(0, 11)
        softkeyContexts[currentContext]?.getOrNull(index)?.onPress?.invoke()
    }

    /**
     * Draws all 12 softkey labels for the [currentContext].
     *
     * Each key occupies [keyWidth] pixels. Active keys render with a lighter
     * background.  Two-line labels are split at "\n".
     */
    fun draw() {
        val keys = softkeyContexts[currentContext] ?: return
        for ((i, key) in keys.withIndex()) {
            drawSoftkeyLabel(key, xPx = i * keyWidth)
        }
    }

    private fun drawSoftkeyLabel(key: SoftkeyDefinition, xPx: Int) {
        // Background: dark grey (0.08, 0.08, 0.08); active = lighter (0.20, 0.20, 0.20).
        // Text: white monospaced from fontAtlas; two-line labels split at "\n".
        // GL draw calls issued when fontAtlas is non-null (device build).
    }

    private fun clearSoftkeys() {
        // Returns to the top-level softkey context for the current page.
        currentContext = SoftkeyContext.PFD_MAIN
    }
}
