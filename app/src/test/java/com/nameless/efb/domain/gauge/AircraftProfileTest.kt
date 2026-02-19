package com.nameless.efb.domain.gauge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AircraftProfileTest {

    private val c172Json = """
        {
          "type": "C172SP",
          "fuelType": "AVGAS",
          "vsoKts": 40.0,
          "vfeKts": 85.0,
          "vs1Kts": 48.0,
          "vnoKts": 127.0,
          "vneKts": 163.0,
          "vrKts": 55.0,
          "vxKts": 59.0,
          "vyKts": 74.0,
          "vappKts": 65.0,
          "hasManifoldPressure": false,
          "maxRpm": 2700.0,
          "oilTempRedlineDegC": 118.0,
          "oilPressMinPsi": 25.0,
          "busVoltsMin": 13.0,
          "suctionMinInhg": 4.5
        }
    """.trimIndent()

    @Test
    fun `c172 profile parses vne correctly`() {
        val profile = AircraftProfile.fromJson(c172Json)
        assertEquals(163f, profile.vneKts, 0.1f)
    }

    @Test
    fun `c172 profile parses fuel type as AVGAS`() {
        val profile = AircraftProfile.fromJson(c172Json)
        assertEquals(FuelType.AVGAS, profile.fuelType)
    }

    @Test
    fun `c172 profile has no manifold pressure`() {
        val profile = AircraftProfile.fromJson(c172Json)
        assertEquals(false, profile.hasManifoldPressure)
    }

    @Test
    fun `da40 profile parses JetA1 fuel type`() {
        val json = """
            {
              "type": "DA40-NG",
              "fuelType": "JET_A1",
              "vneKts": 178.0,
              "hasManifoldPressure": false,
              "maxRpm": 2300.0
            }
        """.trimIndent()
        val profile = AircraftProfile.fromJson(json)
        assertEquals(FuelType.JET_A1, profile.fuelType)
        assertEquals(178f, profile.vneKts, 0.1f)
        assertEquals(2300f, profile.maxRpm, 0.1f)
    }

    @Test
    fun `baron profile has manifold pressure`() {
        val json = """{"type":"BE58","fuelType":"AVGAS","hasManifoldPressure":true}"""
        val profile = AircraftProfile.fromJson(json)
        assertEquals(true, profile.hasManifoldPressure)
    }

    @Test
    fun `fromJson uses defaults for missing fields`() {
        // Minimal JSON — only required fields; all others should default
        val json = """{"type":"test","fuelType":"AVGAS"}"""
        val profile = AircraftProfile.fromJson(json)
        // V-speeds should fall back to C172 defaults
        assertEquals(163f, profile.vneKts, 0.1f)
        assertEquals(4.5f, profile.suctionMinInhg, 0.01f)
    }
}
