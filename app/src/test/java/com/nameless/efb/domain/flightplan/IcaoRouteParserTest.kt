package com.nameless.efb.domain.flightplan

import com.nameless.efb.data.db.EfbDatabase
import com.nameless.efb.data.db.dao.AirportDao
import com.nameless.efb.data.db.dao.NavaidDao
import com.nameless.efb.data.db.entity.AirportEntity
import com.nameless.efb.data.db.entity.NavaidEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IcaoRouteParserTest {

    private lateinit var parser: IcaoRouteParser

    @Before
    fun setUp() {
        val airportDao = mockk<AirportDao>(relaxed = true)
        coEvery { airportDao.byIcao("FAOR") } returns faorEntity()
        coEvery { airportDao.byIcao("FACT") } returns factEntity()
        coEvery { airportDao.byIcao(not(or("FAOR", "FACT"))) } returns null

        val navaidDao = mockk<NavaidDao>(relaxed = true)
        coEvery { navaidDao.byIdent("TEBSA") } returns listOf(tebsaNavaid())
        coEvery { navaidDao.byIdent(not("TEBSA")) } returns emptyList()

        val navDb = mockk<EfbDatabase>(relaxed = true)
        every { navDb.airportDao() } returns airportDao
        every { navDb.navaidDao() } returns navaidDao

        parser = IcaoRouteParser(navDb)
    }

    // ── Tokeniser ─────────────────────────────────────────────────────────────

    @Test
    fun tokenise_splitsOnWhitespace() {
        val tokens = parser.tokenise("FAOR N871 TEBSA FACT")
        assertEquals(listOf("FAOR", "N871", "TEBSA", "FACT"), tokens)
    }

    @Test
    fun tokenise_handlesExtraSpaces() {
        val tokens = parser.tokenise("  FAOR   FACT  ")
        assertEquals(listOf("FAOR", "FACT"), tokens)
    }

    // ── Classification ────────────────────────────────────────────────────────

    @Test
    fun isAirportIcao_fourLetters() {
        assertTrue(parser.isAirportIcao("FAOR"))
        assertTrue(parser.isAirportIcao("FACT"))
        assertFalse(parser.isAirportIcao("FAO"))      // too short
        assertFalse(parser.isAirportIcao("FAOR1"))    // too long
        assertFalse(parser.isAirportIcao("1234"))     // starts with digit
    }

    @Test
    fun isAirway_southAfricanPrefixes() {
        assertTrue(parser.isAirway("N871"))
        assertTrue(parser.isAirway("Z30"))
        assertTrue(parser.isAirway("V21"))
        assertTrue(parser.isAirway("Q10"))
        assertFalse(parser.isAirway("TEBSA"))   // fix, not airway
        assertFalse(parser.isAirway("FAOR"))    // airport
    }

    // ── Route parsing ─────────────────────────────────────────────────────────

    @Test
    fun directRoute_bothAirportsResolved() = runBlocking {
        val result = parser.parse("FAOR FACT")
        assertFalse("Direct route should have no unresolved tokens", result.hasUnresolved)
        assertEquals("FAOR", result.waypoints.first().identifier)
        assertEquals("FACT", result.waypoints.last().identifier)
    }

    @Test
    fun routeWithFix_fixResolved() = runBlocking {
        val result = parser.parse("FAOR TEBSA FACT")
        assertFalse(result.hasUnresolved)
        val ids = result.waypoints.map { it.identifier }
        assertTrue(ids.contains("TEBSA"))
    }

    @Test
    fun routeWithAirway_markedResolved() = runBlocking {
        val result = parser.parse("FAOR N871 FACT")
        // Airways are valid route elements and should not be marked unresolved
        assertFalse("Airway N871 should resolve without error", result.hasUnresolved)
    }

    @Test
    fun unknownFix_markedUnresolved() = runBlocking {
        val result = parser.parse("FAOR XYZZY FACT")
        assertTrue("XYZZY is unknown and should be unresolved", result.hasUnresolved)
        assertTrue(result.unresolvedTokens.contains("XYZZY"))
    }

    @Test
    fun waypointCount_matchesTokenCount() = runBlocking {
        val result = parser.parse("FAOR FACT")
        assertEquals(2, result.waypoints.size)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun faorEntity() = AirportEntity(
        icao = "FAOR", name = "OR Tambo International",
        latitude = -26.1392, longitude = 28.2462, elevationFt = 5558,
        airportType = "large_airport", isTowered = true, isMilitary = false,
        countryCode = "ZA", municipality = "Johannesburg", source = "ourairports",
    )

    private fun factEntity() = AirportEntity(
        icao = "FACT", name = "Cape Town International",
        latitude = -33.9648, longitude = 18.6017, elevationFt = 151,
        airportType = "large_airport", isTowered = true, isMilitary = false,
        countryCode = "ZA", municipality = "Cape Town", source = "ourairports",
    )

    private fun tebsaNavaid() = NavaidEntity(
        id = 99, identifier = "TEBSA", name = "TEBSA",
        type = "FIX", latitude = -30.5, longitude = 24.0,
        elevationFt = 0, frequencyHz = 0, magneticVariation = -24.0f,
        rangeNm = 0, icaoRegion = "FA", airportIcao = "",
    )
}
