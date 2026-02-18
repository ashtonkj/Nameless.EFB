package com.nameless.efb.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nameless.efb.data.db.entity.MetarCacheEntity

@Dao
interface MetarDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(metar: MetarCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceAll(metars: List<MetarCacheEntity>)

    @Query("SELECT * FROM metar_cache WHERE icao = :icao")
    suspend fun forAirport(icao: String): MetarCacheEntity?

    @Query("SELECT * FROM metar_cache WHERE icao IN (:icaos)")
    suspend fun forAirports(icaos: List<String>): List<MetarCacheEntity>

    /** Delete entries older than the given timestamp (epoch ms). */
    @Query("DELETE FROM metar_cache WHERE fetchedAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("SELECT COUNT(*) FROM metar_cache")
    suspend fun count(): Long
}
