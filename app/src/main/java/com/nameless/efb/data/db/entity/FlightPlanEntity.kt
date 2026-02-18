package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "flight_plans")
data class FlightPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val departureIcao: String,
    val destinationIcao: String,
    val aircraftProfileId: Long?,
    val createdAt: Long,        // epoch ms
    val updatedAt: Long,
    val waypointsJson: String,  // serialised waypoint list
    val notes: String,
    val tags: String,           // comma-separated tags
    val cruiseAltitudeFt: Int,
)

@Entity(tableName = "flight_plan_waypoints")
data class FlightPlanWaypointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val flightPlanId: Long,
    val sequence: Int,
    val waypointType: String,   // "AIRPORT","NAVAID","FIX","USER","AIRWAY_ENTRY"
    val identifier: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeConstraintFt: Int?,
    val speedConstraintKt: Int?,
    val airway: String,         // airway identifier if this is an airway segment entry
)
