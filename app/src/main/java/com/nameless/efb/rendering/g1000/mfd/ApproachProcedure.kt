package com.nameless.efb.rendering.g1000.mfd

import com.nameless.efb.data.connectivity.SimSnapshot

/**
 * Represents an active approach procedure used for PDA (Premature Descent Alert) logic (G-17).
 *
 * Implementations provide the expected glidepath altitude at the aircraft's current position,
 * allowing [TerrainPageRenderer.checkPda] to compare it against actual aircraft altitude.
 */
interface ApproachProcedure {

    /**
     * Returns the expected glidepath altitude in feet MSL at the aircraft's current position.
     *
     * @param snapshot  Current sim state (position, groundspeed, etc.)
     */
    fun glideslopeAlt(snapshot: SimSnapshot): Float
}
