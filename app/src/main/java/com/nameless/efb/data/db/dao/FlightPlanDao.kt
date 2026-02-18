package com.nameless.efb.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nameless.efb.data.db.entity.FlightPlanEntity
import com.nameless.efb.data.db.entity.FlightPlanWaypointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FlightPlanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(plan: FlightPlanEntity): Long

    @Update
    suspend fun update(plan: FlightPlanEntity)

    @Delete
    suspend fun delete(plan: FlightPlanEntity)

    @Query("SELECT * FROM flight_plans ORDER BY updatedAt DESC")
    fun allPlans(): Flow<List<FlightPlanEntity>>

    @Query("SELECT * FROM flight_plans WHERE id = :id")
    suspend fun byId(id: Long): FlightPlanEntity?

    @Query("SELECT * FROM flight_plans WHERE name LIKE '%' || :query || '%' OR departureIcao LIKE '%' || :query || '%' OR destinationIcao LIKE '%' || :query || '%' ORDER BY updatedAt DESC LIMIT 100")
    suspend fun search(query: String): List<FlightPlanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWaypoints(waypoints: List<FlightPlanWaypointEntity>)

    @Query("DELETE FROM flight_plan_waypoints WHERE flightPlanId = :planId")
    suspend fun deleteWaypoints(planId: Long)

    @Query("SELECT * FROM flight_plan_waypoints WHERE flightPlanId = :planId ORDER BY sequence")
    suspend fun waypointsFor(planId: Long): List<FlightPlanWaypointEntity>

    @Query("SELECT COUNT(*) FROM flight_plans")
    suspend fun count(): Long
}
