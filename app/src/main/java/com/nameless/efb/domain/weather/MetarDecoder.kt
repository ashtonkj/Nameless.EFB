package com.nameless.efb.domain.weather

import kotlin.math.roundToInt

/**
 * Regex-based METAR tokeniser for VFR/IFR flight category detection.
 *
 * Supports:
 *  - Station, time (ignored)
 *  - Wind: `22010KT`, `VRB03KT`, `22015G25KT`
 *  - CAVOK
 *  - Visibility: `9999`, `5000`, `10SM` (converted to metres)
 *  - Cloud groups: `FEW030`, `SCT018`, `BKN025`, `OVC010`
 *  - Temperature/dewpoint: `28/09`, `M05/M10`
 *  - QNH: `Q1018` (hPa) or `A2992` (inHg → hPa)
 *  - Remarks: everything after `RMK`
 *
 * Accuracy sufficient for an EFB training tool — not for operational use.
 */
object MetarDecoder {

    private val WIND_REGEX      = Regex("""(\d{3}|VRB)(\d{2,3})(?:G(\d{2,3}))?KT""")
    private val WIND_MPS_REGEX  = Regex("""(\d{3}|VRB)(\d{2,3})(?:G(\d{2,3}))?MPS""")
    private val VIS_METRIC      = Regex("""\b(\d{4})\b""")
    private val VIS_SM          = Regex("""(\d+(?:/\d+)?|\d+\s+\d+/\d+)SM""")
    private val CLOUD_REGEX     = Regex("""(FEW|SCT|BKN|OVC)(\d{3})""")
    private val TEMP_REGEX      = Regex("""(M?\d+)/(M?\d+)""")
    private val QNH_HPA         = Regex("""Q(\d{4})""")
    private val QNH_INHG        = Regex("""A(\d{4})""")

    fun decode(raw: String): DecodedMetar {
        val upper   = raw.trim().uppercase()
        val tokens  = upper.split(Regex("""\s+"""))

        val icao    = tokens.firstOrNull() ?: ""
        val isCavok = "CAVOK" in tokens

        // ── Wind ──────────────────────────────────────────────────────────────
        var windDir   = 0
        var windSpeed = 0
        var gust      = 0
        WIND_REGEX.find(upper)?.also {
            windDir   = if (it.groupValues[1] == "VRB") 0 else it.groupValues[1].toInt()
            windSpeed = it.groupValues[2].toInt()
            gust      = it.groupValues[3].toIntOrNull() ?: 0
        } ?: WIND_MPS_REGEX.find(upper)?.also {
            windDir   = if (it.groupValues[1] == "VRB") 0 else it.groupValues[1].toInt()
            windSpeed = (it.groupValues[2].toInt() * 1.94384f).roundToInt()  // m/s → kt
            gust      = ((it.groupValues[3].toIntOrNull() ?: 0) * 1.94384f).roundToInt()
        }

        // ── Visibility ────────────────────────────────────────────────────────
        val visibilityM: Int = when {
            isCavok -> 999_999
            else    -> {
                VIS_SM.find(upper)?.let { smToMetres(it.groupValues[1]) }
                    ?: VIS_METRIC.find(upper)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 9_999
            }
        }

        // ── Ceiling ───────────────────────────────────────────────────────────
        val ceiling: Int? = if (isCavok) null else {
            CLOUD_REGEX.findAll(upper)
                .filter { it.groupValues[1] in listOf("BKN", "OVC") }
                .mapNotNull { it.groupValues[2].toIntOrNull() }
                .minOrNull()
                ?.times(100)  // hundreds of feet → feet
        }

        // ── Temperature / Dewpoint ────────────────────────────────────────────
        var tempC = 0f; var dewC = 0f
        TEMP_REGEX.find(upper)?.also { m ->
            tempC = parseTemp(m.groupValues[1])
            dewC  = parseTemp(m.groupValues[2])
        }

        // ── QNH ───────────────────────────────────────────────────────────────
        val qnhHpa: Float = QNH_HPA.find(upper)?.groupValues?.get(1)?.toFloatOrNull()
            ?: QNH_INHG.find(upper)?.groupValues?.get(1)?.toFloatOrNull()?.let { it / 100f * 33.8639f }
            ?: 1013.25f

        // ── Remarks ───────────────────────────────────────────────────────────
        val rmkIdx = upper.indexOf("RMK")
        val remarks = if (rmkIdx >= 0) raw.substring(rmkIdx) else ""

        // ── Flight category ───────────────────────────────────────────────────
        val fc = computeFlightCategory(isCavok, visibilityM, ceiling)

        return DecodedMetar(
            icao            = icao,
            windDirDeg      = windDir,
            windSpeedKt     = windSpeed,
            gustKt          = gust,
            visibilityM     = visibilityM,
            ceiling         = ceiling,
            tempC           = tempC,
            dewpointC       = dewC,
            qnhHpa          = qnhHpa,
            isCavok         = isCavok,
            remarks         = remarks,
            flightCategory  = fc,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseTemp(s: String): Float =
        if (s.startsWith("M")) -(s.drop(1).toFloatOrNull() ?: 0f)
        else s.toFloatOrNull() ?: 0f

    private fun smToMetres(sm: String): Int {
        // Handle fractions like "1/4", "1 1/4"
        val parts = sm.trim().split(Regex("""\s+"""))
        val value = when (parts.size) {
            1 -> fractionToDouble(parts[0])
            2 -> parts[0].toDouble() + fractionToDouble(parts[1])
            else -> 0.0
        }
        return (value * 1609.344).roundToInt()
    }

    private fun fractionToDouble(s: String): Double {
        val idx = s.indexOf('/')
        return if (idx < 0) s.toDoubleOrNull() ?: 0.0
        else {
            val num = s.substring(0, idx).toDoubleOrNull() ?: 0.0
            val den = s.substring(idx + 1).toDoubleOrNull() ?: 1.0
            if (den != 0.0) num / den else 0.0
        }
    }

    internal fun computeFlightCategory(
        isCavok: Boolean,
        visibilityM: Int,
        ceilingFt: Int?,
    ): FlightCategory {
        if (isCavok) return FlightCategory.VFR

        val ceil = ceilingFt

        // Determine the most restrictive condition
        val visCat = when {
            visibilityM < 800    -> FlightCategory.LIFR
            visibilityM < 3_000  -> FlightCategory.IFR
            visibilityM < 5_000  -> FlightCategory.MVFR
            else                  -> FlightCategory.VFR
        }
        val ceilCat = when {
            ceil == null       -> FlightCategory.VFR  // no ceiling = sky clear
            ceil < 300         -> FlightCategory.LIFR
            ceil < 1_000       -> FlightCategory.IFR
            ceil < 3_000       -> FlightCategory.MVFR
            else               -> FlightCategory.VFR
        }

        // Return the more restrictive of the two
        val order = listOf(FlightCategory.VFR, FlightCategory.MVFR, FlightCategory.IFR, FlightCategory.LIFR)
        return if (order.indexOf(visCat) >= order.indexOf(ceilCat)) visCat else ceilCat
    }
}
