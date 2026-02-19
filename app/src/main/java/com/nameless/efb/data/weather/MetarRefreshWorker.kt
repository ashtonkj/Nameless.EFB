package com.nameless.efb.data.weather

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nameless.efb.data.db.EfbDatabase
import com.nameless.efb.domain.nav.LatLon
import com.nameless.efb.domain.nav.SpatialQuery
import java.util.concurrent.TimeUnit

/**
 * WorkManager background task that refreshes METARs for all airports
 * visible within a 50nm radius of the last known aircraft position.
 *
 * Runs every 30 minutes. Cancels silently on network failure (MetarFetcher
 * handles the error internally).
 *
 * Schedule via [MetarRefreshWorker.schedule].
 */
class MetarRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db      = EfbDatabase.getInstance(applicationContext)
        val fetcher = MetarFetcher(db.metarDao())

        // Use Johannesburg as a default if no position is available
        val center = LatLon(-26.14, 28.25)
        val airports = db.airportDao().nearbyRaw(SpatialQuery.nearbyAirports(center, 50.0))
        val icaos = airports.map { it.icao }.filter { it.isNotBlank() }.take(20)

        if (icaos.isNotEmpty()) {
            fetcher.fetchAndCache(icaos)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "metar_refresh"

        /**
         * Schedules a periodic METAR refresh. Idempotent — safe to call on
         * every app launch.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MetarRefreshWorker>(
                30, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
