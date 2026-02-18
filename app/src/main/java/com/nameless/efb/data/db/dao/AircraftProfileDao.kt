package com.nameless.efb.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nameless.efb.data.db.entity.AircraftProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AircraftProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: AircraftProfileEntity): Long

    @Update
    suspend fun update(profile: AircraftProfileEntity)

    @Query("SELECT * FROM aircraft_profiles ORDER BY name")
    fun allProfiles(): Flow<List<AircraftProfileEntity>>

    @Query("SELECT * FROM aircraft_profiles WHERE id = :id")
    suspend fun byId(id: Long): AircraftProfileEntity?

    @Query("SELECT * FROM aircraft_profiles WHERE registration = :reg LIMIT 1")
    suspend fun byRegistration(reg: String): AircraftProfileEntity?
}
