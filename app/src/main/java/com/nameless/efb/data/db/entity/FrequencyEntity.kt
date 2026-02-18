package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "airport_frequencies",
    foreignKeys = [
        ForeignKey(
            entity = AirportEntity::class,
            parentColumns = ["icao"],
            childColumns = ["airportIcao"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("airportIcao")],
)
data class FrequencyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val airportIcao: String,
    val type: String,           // "TWR","GND","ATIS","APP","DEP","CTR","UNICOM","MULTICOM"
    val description: String,
    val frequencyHz: Int,
)
