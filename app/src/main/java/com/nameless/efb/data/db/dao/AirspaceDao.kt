package com.nameless.efb.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nameless.efb.data.db.entity.AirspaceEntity
import com.nameless.efb.data.db.entity.ObstacleEntity

@Dao
interface AirspaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(airspaces: List<AirspaceEntity>)

    @Query("""
        SELECT * FROM airspaces
        WHERE bboxLatMax >= :latMin AND bboxLatMin <= :latMax
          AND bboxLonMax >= :lonMin AND bboxLonMin <= :lonMax
    """)
    suspend fun inBbox(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double): List<AirspaceEntity>

    @Query("SELECT COUNT(*) FROM airspaces")
    suspend fun count(): Long
}

@Dao
interface ObstacleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(obstacles: List<ObstacleEntity>)

    @Query("""
        SELECT * FROM obstacles
        WHERE latitude  >= :latMin AND latitude  <= :latMax
          AND longitude >= :lonMin AND longitude <= :lonMax
    """)
    suspend fun inBbox(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double): List<ObstacleEntity>

    @Query("SELECT COUNT(*) FROM obstacles")
    suspend fun count(): Long
}
