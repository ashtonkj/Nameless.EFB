package com.nameless.efb.domain.logbook

import com.nameless.efb.data.connectivity.SimSnapshot
import com.nameless.efb.data.db.dao.LogbookDao
import com.nameless.efb.data.db.entity.AirportEntity
import com.nameless.efb.data.db.entity.LogbookEntryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Automatically detects takeoff and landing events from live [SimSnapshot] data
 * and records logbook entries (UT-06).
 *
 * Detection rules:
 * - Takeoff: groundspeed crosses 40 kt upward while not already airborne
 * - Landing: groundspeed drops below 5 kt while airborne
 *
 * Each recorded entry includes departure/arrival ICAO, airborne time, and
 * the date of the flight. Other fields (registration, aircraft type) are
 * populated separately from the active [AircraftProfile].
 *
 * [update] must be called each frame from the main sim data consumer.
 */
class LogbookAutoCapture(
    private val logbookDao: LogbookDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    internal var flightActive = false
        private set

    private var departureIcao: String? = null
    private var takeoffTime: Long?     = null
    private var maxAltitudeFt: Int     = 0

    fun update(snapshot: SimSnapshot, nearestAirport: AirportEntity?) {
        val groundspeedKt = snapshot.groundspeedMs * 1.944f

        // Takeoff detection
        if (!flightActive && groundspeedKt > 40f) {
            flightActive  = true
            takeoffTime   = System.currentTimeMillis()
            departureIcao = nearestAirport?.icao
            maxAltitudeFt = 0
        }

        // Track maximum altitude during flight
        if (flightActive) {
            val altFt = (snapshot.elevationM * 3.28084).toInt()
            if (altFt > maxAltitudeFt) maxAltitudeFt = altFt
        }

        // Landing detection
        if (flightActive && groundspeedKt < 5f) {
            val arrival   = nearestAirport?.icao
            val departure = departureIcao
            val takeoff   = takeoffTime
            val maxAlt    = maxAltitudeFt
            flightActive  = false

            scope.launch {
                val now = System.currentTimeMillis()
                logbookDao.insert(
                    LogbookEntryEntity(
                        date           = now,
                        departureIcao  = departure  ?: "ZZZZ",
                        arrivalIcao    = arrival    ?: "ZZZZ",
                        aircraftType   = "",
                        registration   = "",
                        blockTimeSec   = 0,
                        flightTimeSec  = if (takeoff != null) (now - takeoff) / 1000L else 0L,
                        distanceNm     = 0.0,
                        maxAltitudeFt  = maxAlt,
                        notes          = "",
                    )
                )
            }
        }
    }
}
