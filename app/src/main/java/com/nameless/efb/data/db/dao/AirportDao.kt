package com.nameless.efb.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.nameless.efb.data.db.entity.AirportEntity
import com.nameless.efb.data.db.entity.FrequencyEntity
import com.nameless.efb.data.db.entity.RunwayEntity

@Dao
interface AirportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(airports: List<AirportEntity>)

    @Query("SELECT * FROM airports WHERE icao = :icao")
    suspend fun byIcao(icao: String): AirportEntity?

    @Query("SELECT * FROM airports WHERE countryCode = :code ORDER BY name")
    suspend fun byCountry(code: String): List<AirportEntity>

    /** R-tree bounding-box pre-filter. Caller applies exact great-circle distance. */
    @RawQuery
    suspend fun nearbyRaw(query: SupportSQLiteQuery): List<AirportEntity>

    @Query("SELECT * FROM airport_frequencies WHERE airportIcao = :icao ORDER BY type, frequencyHz")
    suspend fun frequenciesFor(icao: String): List<FrequencyEntity>

    @Query("SELECT * FROM runways WHERE airportIcao = :icao ORDER BY ident")
    suspend fun runwaysFor(icao: String): List<RunwayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFrequencies(frequencies: List<FrequencyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRunways(runways: List<RunwayEntity>)

    @Query("SELECT COUNT(*) FROM airports")
    suspend fun count(): Long
}
