package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "airways",
    indices = [Index("identifier")],
)
data class AirwayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identifier: String,     // e.g., "N871", "Z30", "J1"
    val type: String,           // "V" (Victor), "J" (Jet), "Q" (RNAV/Q-route)
)

@Entity(
    tableName = "airway_segments",
    indices = [Index("airwayId"), Index("fixFromId"), Index("fixToId")],
)
data class AirwaySegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val airwayId: Long,
    val fixFromId: Long,
    val fixToId: Long,
    val level: String,          // "L"=low, "H"=high, "B"=both
    val direction: String,      // "F"=forward only, "B"=backward only, "N"=both
    val minAltFt: Int,
    val maxAltFt: Int,
)
