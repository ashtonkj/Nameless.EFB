package com.nameless.efb.domain.wb

/**
 * A single loading station for weight-and-balance calculation.
 *
 * [arm] is the moment arm in the same unit system as the aircraft's W&B chart
 * (inches for GA, mm for some European types). [weightKg] is the item weight.
 */
data class WbStation(
    val arm: Float,
    val weightKg: Float,
    val name: String = "",
)

/**
 * Result of a weight-and-balance computation.
 *
 * [cgPoint] is a (arm, grossWeight) pair matching the axes of the aircraft's
 * CG envelope chart.  Use [isWithinEnvelope] for go/no-go; render [cgPoint]
 * on the envelope polygon for the visual check (UT-01).
 */
data class WbResult(
    val grossWeightKg: Float,
    val cgArm: Float,
    val isWithinEnvelope: Boolean,
    val cgPoint: Pair<Float, Float>,  // (cgArm, grossWeightKg)
)

/**
 * Weight-and-balance calculator (UT-01).
 *
 * Works in any arm unit system (inches, mm) — the caller must supply
 * [stations] and [envelope] in the same units.
 *
 * SA units: fuel in kg (not US gallons); densities AVGAS 0.72, JetA-1 0.80.
 */
object WeightAndBalance {

    /**
     * Computes CG from [stations] and checks whether it lies inside [envelope].
     *
     * [envelope] is a list of (arm, weight) polygon vertices in clockwise or
     * counter-clockwise order. Uses ray-casting (Jordan curve theorem).
     */
    fun compute(stations: List<WbStation>, envelope: List<Pair<Float, Float>>): WbResult {
        val totalWeight = stations.sumOf { it.weightKg.toDouble() }.toFloat()
        val totalMoment = stations.sumOf { (it.arm * it.weightKg).toDouble() }.toFloat()
        val cg = if (totalWeight > 0f) totalMoment / totalWeight else 0f

        return WbResult(
            grossWeightKg    = totalWeight,
            cgArm            = cg,
            isWithinEnvelope = isPointInPolygon(cg, totalWeight, envelope),
            cgPoint          = Pair(cg, totalWeight),
        )
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Ray-casting point-in-polygon test. [px] = arm axis, [py] = weight axis. */
    private fun isPointInPolygon(px: Float, py: Float, polygon: List<Pair<Float, Float>>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        val n = polygon.size
        var j = n - 1
        for (i in 0 until n) {
            val (xi, yi) = polygon[i]
            val (xj, yj) = polygon[j]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
