package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "obstacles")
data class ObstacleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,           // "tower","antenna","wind_turbine","power_line","chimney"
    val latitude: Double,
    val longitude: Double,
    val heightAglFt: Int,
    val elevationMslFt: Int,
    val source: String,         // "openaip"
    val countryCode: String,
)
