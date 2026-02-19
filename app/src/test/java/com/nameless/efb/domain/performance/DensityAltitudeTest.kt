package com.nameless.efb.domain.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DensityAltitudeTest {

    @Test
    fun johannesburgSummer_within7000to9000ft() {
        // FAOR elevation 5558ft, OAT 30°C (typical Joburg summer)
        // ISA at 5558ft = 15 - 11.1 = 3.9°C → DA ≈ 8660ft
        val da = DensityAltitude.compute(pressureAltFt = 5558f, oatDegC = 30f)
        assertTrue("DA $da not in 7000-9000ft range", da > 7000f && da < 9000f)
    }

    @Test
    fun isaConditions_daEqualsPa() {
        // At sea level, ISA OAT = 15°C → DA = PA = 0
        val da = DensityAltitude.compute(pressureAltFt = 0f, oatDegC = 15f)
        assertEquals(0f, da, 0.5f)
    }

    @Test
    fun hotDay_daHigherThanPa() {
        // OAT above ISA → density altitude > pressure altitude
        val da = DensityAltitude.compute(pressureAltFt = 2000f, oatDegC = 35f)
        assertTrue("Hot day: DA $da should exceed PA 2000ft", da > 2000f)
    }

    @Test
    fun coldDay_daLowerThanPa() {
        // OAT below ISA → density altitude < pressure altitude
        val da = DensityAltitude.compute(pressureAltFt = 2000f, oatDegC = -10f)
        assertTrue("Cold day: DA $da should be below PA 2000ft", da < 2000f)
    }

    @Test
    fun pressureAltitude_qnhAboveStd_negative() {
        // QNH 1020 hPa (above standard 1013.25) → slightly negative PA offset
        val pa = DensityAltitude.pressureAltitude(altitudeFt = 100f, qnhHpa = 1020f)
        assertTrue("QNH 1020: PA should be less than field elevation", pa < 100f)
    }

    @Test
    fun pressureAltitude_qnhBelowStd_positive() {
        // QNH 1000 hPa (below standard) → PA greater than field elevation
        val pa = DensityAltitude.pressureAltitude(altitudeFt = 100f, qnhHpa = 1000f)
        assertTrue("QNH 1000: PA should exceed field elevation", pa > 100f)
    }
}
