package com.nameless.efb.data.charts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

data class PlateInfo(
    val plateId: String,
    val name: String,       // e.g., "ILS OR LOCALIZER RWY 03L"
    val category: String,   // "APP", "DEP", "ARR", "APT", "REF"
    val icao: String,
)

/**
 * Minimal interface so [NavigraphChartsApi] can be mocked in tests
 * without network access.
 */
interface NavigraphKeystore {
    suspend fun getAccessToken(): String?
}

/**
 * Client for the Navigraph Charts API (CH-01, CH-02).
 *
 * Credentials are fetched on demand from [keystore] (backed by Android Keystore
 * for the actual token; OAuth refresh handled externally).
 *
 * Downloaded plates are cached in [cacheDir] as PDF files named by plateId.
 * Cache is permanent until the AIRAC cycle changes (detected via plate metadata).
 */
class NavigraphChartsApi(
    private val keystore: NavigraphKeystore,
    private val cacheDir: File,
) {
    private val baseUrl = "https://charts.navigraph.com/2/api"

    /**
     * Returns a list of available plates for [icao].
     *
     * Requires a valid Navigraph subscription; returns an empty list if the
     * token is unavailable or the API call fails.
     */
    suspend fun getPlatesForAirport(icao: String): List<PlateInfo> = withContext(Dispatchers.IO) {
        val token = keystore.getAccessToken() ?: return@withContext emptyList()
        try {
            val json = URL("$baseUrl/airports/${icao.uppercase()}/charts")
                .openConnection()
                .apply { setRequestProperty("Authorization", "Bearer $token") }
                .getInputStream()
                .bufferedReader()
                .readText()
            parsePlates(icao.uppercase(), json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Downloads (or returns cached) plate PDF for [plateId].
     *
     * Cached files are stored as `<cacheDir>/<plateId>.pdf`.
     */
    suspend fun downloadPlate(plateId: String): File? = withContext(Dispatchers.IO) {
        val cached = File(cacheDir, "$plateId.pdf")
        if (cached.exists()) return@withContext cached

        val token = keystore.getAccessToken() ?: return@withContext null
        try {
            val bytes = URL("$baseUrl/charts/$plateId/pdf")
                .openConnection()
                .apply { setRequestProperty("Authorization", "Bearer $token") }
                .getInputStream()
                .readBytes()
            cacheDir.mkdirs()
            cached.writeBytes(bytes)
            cached
        } catch (_: Exception) {
            null
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun parsePlates(icao: String, json: String): List<PlateInfo> {
        // Minimal JSON parsing using the built-in org.json library.
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                PlateInfo(
                    plateId  = obj.optString("chart_id", ""),
                    name     = obj.optString("name", ""),
                    category = obj.optString("category", ""),
                    icao     = icao,
                )
            }.filter { it.plateId.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
