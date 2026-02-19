package com.nameless.efb.data.weather

import com.nameless.efb.data.db.dao.MetarDao
import com.nameless.efb.data.db.entity.MetarCacheEntity
import com.nameless.efb.domain.weather.FlightCategory
import com.nameless.efb.domain.weather.MetarDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.URL

/**
 * Fetches METAR observations from the NOAA Aviation Weather Center ADDS API
 * and caches results in the Room database with a 30-minute TTL.
 *
 * Endpoint: `https://aviationweather.gov/api/data/metar`
 *
 * Uses the built-in [java.net.URL] HTTP client — no OkHttp dependency required.
 */
class MetarFetcher(private val metarDao: MetarDao) {

    private val baseUrl = "https://aviationweather.gov/api/data/metar"
    private val ttlMs   = 30 * 60 * 1_000L   // 30 minutes

    /**
     * Fetches METARs for the given [icaos] and caches them.
     *
     * If a cached entry is fresher than [ttlMs], it is returned from the
     * database without a network call.  Returns only successfully fetched
     * and parsed entries.
     */
    suspend fun fetchAndCache(icaos: List<String>): List<MetarCacheEntity> = withContext(Dispatchers.IO) {
        val now    = System.currentTimeMillis()
        val stale  = icaos.filter { icao ->
            val cached = metarDao.forAirport(icao)
            cached == null || (now - cached.fetchedAt) > ttlMs
        }

        if (stale.isNotEmpty()) {
            try {
                val ids  = stale.joinToString(",")
                val json = URL("$baseUrl?ids=$ids&format=json&taf=false").readText()
                val arr  = JSONArray(json)
                val entities = (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    buildEntity(obj.optString("rawOb", ""), now)
                }
                metarDao.insertOrReplaceAll(entities)
            } catch (_: IOException) {
                // Network error — serve stale cache or empty
            } catch (_: Exception) {
                // Parse error — ignore
            }
        }

        icaos.mapNotNull { metarDao.forAirport(it) }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun buildEntity(raw: String, fetchedAt: Long): MetarCacheEntity? {
        if (raw.isBlank()) return null
        return try {
            val decoded = MetarDecoder.decode(raw)
            MetarCacheEntity(
                icao           = decoded.icao,
                rawMetar       = raw,
                flightCategory = decoded.flightCategory.name,
                fetchedAt      = fetchedAt,
                windDirDeg     = decoded.windDirDeg,
                windSpeedKt    = decoded.windSpeedKt,
                visibilityM    = decoded.visibilityM,
                ceilingFt      = decoded.ceiling,
                tempC          = decoded.tempC,
                dewpointC      = decoded.dewpointC,
                qnhHpa         = decoded.qnhHpa,
            )
        } catch (_: Exception) {
            null
        }
    }
}
