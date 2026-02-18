package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "airspaces")
data class AirspaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val airspaceClass: String,  // "A","B","C","D","E","F","G","R","P","D"
    val floorFt: Int,
    val ceilingFt: Int,
    val floorRef: String,       // "MSL","AGL","FL"
    val ceilingRef: String,
    val countryCode: String,
    val geometryJson: String,   // GeoJSON polygon string
    // Bounding box for fast pre-filter (duplicated here for Room queries)
    val bboxLatMin: Double,
    val bboxLatMax: Double,
    val bboxLonMin: Double,
    val bboxLonMax: Double,
)
