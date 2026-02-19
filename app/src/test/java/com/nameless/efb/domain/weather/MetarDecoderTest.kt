package com.nameless.efb.domain.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetarDecoderTest {

    @Test
    fun cavok_setsVfrAndNoCeiling() {
        val metar = MetarDecoder.decode("FAOR 121300Z 22010KT CAVOK 28/09 Q1018")
        assertTrue(metar.isCavok)
        assertEquals(1018f, metar.qnhHpa, 0.1f)
        assertEquals(28f, metar.tempC, 0.1f)
        assertEquals(9, metar.dewpointC.toInt())
        assertEquals(FlightCategory.VFR, metar.flightCategory)
        assertNull(metar.ceiling)
    }

    @Test
    fun wind_parsedCorrectly() {
        val metar = MetarDecoder.decode("FAOR 121300Z 22010G18KT CAVOK 28/09 Q1018")
        assertEquals(220, metar.windDirDeg)
        assertEquals(10, metar.windSpeedKt)
        assertEquals(18, metar.gustKt)
    }

    @Test
    fun ifr_lowCeiling() {
        // BKN008 = ceiling 800ft → IFR
        val metar = MetarDecoder.decode("FACT 121300Z 18012KT 9999 BKN008 15/12 Q1016")
        assertFalse(metar.isCavok)
        assertEquals(800, metar.ceiling)
        assertEquals(FlightCategory.IFR, metar.flightCategory)
    }

    @Test
    fun lifr_veryLowCeiling() {
        // OVC002 = ceiling 200ft → LIFR
        val metar = MetarDecoder.decode("FALA 121300Z 00000KT 2000 OVC002 10/09 Q1015")
        assertEquals(200, metar.ceiling)
        assertEquals(FlightCategory.LIFR, metar.flightCategory)
    }

    @Test
    fun mvfr_reducedVisibility() {
        // 4000m visibility, no ceiling → MVFR
        val metar = MetarDecoder.decode("FAWK 121300Z 10008KT 4000 FEW040 18/10 Q1017")
        assertEquals(FlightCategory.MVFR, metar.flightCategory)
    }

    @Test
    fun qnhInhg_convertedToHpa() {
        val metar = MetarDecoder.decode("KJFK 121300Z 27010KT 10SM FEW025 22/10 A2992")
        // 2992 / 100 * 33.8639 ≈ 1013.2 hPa
        assertEquals(1013.2f, metar.qnhHpa, 0.5f)
    }

    @Test
    fun negativeTemp_parsedWithM() {
        val metar = MetarDecoder.decode("FADN 121300Z 00000KT CAVOK M05/M10 Q1020")
        assertEquals(-5f, metar.tempC, 0.1f)
        assertEquals(-10f, metar.dewpointC, 0.1f)
    }

    @Test
    fun icao_extractedFromFirstToken() {
        val metar = MetarDecoder.decode("FAPE 121300Z 00000KT CAVOK 20/10 Q1019")
        assertEquals("FAPE", metar.icao)
    }

    @Test
    fun computeFlightCategory_ceilingDominates() {
        // Good visibility but low ceiling → ceiling determines category
        val fc = MetarDecoder.computeFlightCategory(
            isCavok = false,
            visibilityM = 9999,
            ceilingFt = 800,  // IFR
        )
        assertEquals(FlightCategory.IFR, fc)
    }

    @Test
    fun computeFlightCategory_visDominates() {
        // High ceiling but bad visibility → visibility determines category
        val fc = MetarDecoder.computeFlightCategory(
            isCavok = false,
            visibilityM = 600,  // LIFR
            ceilingFt = 5000,
        )
        assertEquals(FlightCategory.LIFR, fc)
    }
}
