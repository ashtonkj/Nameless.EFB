package com.nameless.efb.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nameless.efb.data.db.entity.AirwayEntity
import com.nameless.efb.data.db.entity.AirwaySegmentEntity

@Dao
interface AirwayDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAirways(airways: List<AirwayEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<AirwaySegmentEntity>)

    @Query("SELECT * FROM airways WHERE identifier = :id LIMIT 1")
    suspend fun byIdentifier(id: String): AirwayEntity?

    @Query("""
        SELECT s.* FROM airway_segments s
        WHERE s.airwayId = :airwayId
        ORDER BY s.id
    """)
    suspend fun segmentsForAirway(airwayId: Long): List<AirwaySegmentEntity>

    @Query("SELECT COUNT(*) FROM airways")
    suspend fun count(): Long
}
