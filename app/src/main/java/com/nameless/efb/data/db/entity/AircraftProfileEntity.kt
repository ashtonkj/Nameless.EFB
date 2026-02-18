package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "aircraft_profiles")
data class AircraftProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,           // e.g., "Cessna 172SP"
    val registration: String,   // e.g., "ZS-ABC"
    val profileJson: String,    // full JSON profile (V-speeds, limits, layout)
    val isBuiltIn: Boolean,     // true for bundled presets
    val createdAt: Long,
    val updatedAt: Long,
)
