package com.nameless.efb.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nameless.efb.data.db.entity.ProcedureEntity

@Dao
interface ProcedureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(procedures: List<ProcedureEntity>)

    @Query("SELECT * FROM procedures WHERE airportIcao = :icao AND type = :type ORDER BY identifier")
    suspend fun forAirport(icao: String, type: String): List<ProcedureEntity>

    @Query("DELETE FROM procedures WHERE airportIcao = :icao")
    suspend fun deleteForAirport(icao: String)

    @Query("SELECT COUNT(*) FROM procedures")
    suspend fun count(): Long
}
