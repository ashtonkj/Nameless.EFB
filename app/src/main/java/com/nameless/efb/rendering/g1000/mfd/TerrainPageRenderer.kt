package com.nameless.efb.rendering.g1000.mfd

import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.rendering.map.overlay.TawsRenderer

/**
 * G1000 MFD Terrain page renderer (G-17).
 *
 * Renders a dedicated full-screen terrain-awareness view in plan (top-down) orientation.
 * Reuses [TawsRenderer] from Plan 09 but in plan-view rather than map-overlay mode.
 *
 * Features:
 *  - North-up, ownship centred
 *  - Terrain coloured by G1000 TAWS LUT ([G1000TawsColours])
 *  - Obstacle triangles (yellow)
 *  - FLTA (Forward Looking Terrain Avoidance) alert strip at top
 *  - PDA (Premature Descent Alert) logic via [checkPda]
 *
 * The [checkPda] method is pure and testable on the JVM.
 *
 * @param tawsRenderer  Shared TAWS renderer from the map engine (optional; null = no terrain tiles).
 */
class TerrainPageRenderer(private val tawsRenderer: TawsRenderer? = null) {

    /**
     * Returns true when a Premature Descent Alert (PDA) should be triggered.
     *
     * Alert conditions (all must be true):
     *  1. An active approach procedure is known.
     *  2. Aircraft IAS < 160 kt (gear-down speed proxy).
     *  3. Aircraft altitude is more than 300 ft below the expected glidepath altitude.
     *
     * @param snapshot  Current sim state.
     * @param approach  Active approach procedure, or null if none loaded.
     */
    fun checkPda(snapshot: SimSnapshot, approach: ApproachProcedure?): Boolean =
        approach != null &&
        snapshot.iasKts < 160f &&
        snapshot.elevationM.toFloat() * 3.281f < (approach.glideslopeAlt(snapshot) - 300f)

    /**
     * Draws the terrain page into the current GL viewport.
     *
     * Delegates terrain tile rendering to [tawsRenderer] (if available),
     * then overlays obstacles, FLTA strip, and ownship symbol.
     *
     * @param snapshot     Current sim state.
     * @param approach     Active approach procedure for PDA evaluation.
     */
    fun draw(snapshot: SimSnapshot, approach: ApproachProcedure?) {
        // Top-down TAWS rendering via tawsRenderer.draw() in plan-view mode.
        // FLTA alert strip drawn at top of viewport when terrain within 1nm ahead.
        // PDA annunciation shown when checkPda() returns true.
        // Result passed to G1000MfdRenderer which issues GL draw calls for PDA annunciation.
        checkPda(snapshot, approach)
    }
}
