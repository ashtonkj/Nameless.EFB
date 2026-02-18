package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "airports")
data class AirportEntity(
    @PrimaryKey val icao: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevationFt: Int,
    val airportType: String,    // "large_airport", "medium_airport", "small_airport", "heliport", "seaplane_base", "balloonport", "closed"
    val isTowered: Boolean,
    val isMilitary: Boolean,
    val countryCode: String,
    val municipality: String,
    val source: String,         // "ourairports" | "xplane_apt"
)
