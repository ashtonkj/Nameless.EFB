package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "metar_cache")
data class MetarCacheEntity(
    @PrimaryKey val icao: String,
    val rawMetar: String,
    val flightCategory: String, // "VFR","MVFR","IFR","LIFR","UNKNOWN"
    val fetchedAt: Long,        // epoch ms — TTL 30 min
    val windDirDeg: Int,
    val windSpeedKt: Int,
    val visibilityM: Int,
    val ceilingFt: Int?,        // null = CAVOK / no ceiling
    val tempC: Float,
    val dewpointC: Float,
    val qnhHpa: Float,          // SA default: hPa
)
