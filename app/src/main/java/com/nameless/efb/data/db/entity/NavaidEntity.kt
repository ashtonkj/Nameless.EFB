package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "navaids",
    indices = [Index("identifier"), Index("icaoRegion")],
)
data class NavaidEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identifier: String,
    val name: String,
    val type: String,           // "VOR", "NDB", "ILS", "DME", "FIX", "VOR-DME", "RNAV"
    val latitude: Double,
    val longitude: Double,
    val elevationFt: Int,
    val frequencyHz: Int,       // 0 for fixes
    val magneticVariation: Float,
    val rangeNm: Int,
    val icaoRegion: String,
    val airportIcao: String,    // associated airport (ILS/marker) or empty
)
