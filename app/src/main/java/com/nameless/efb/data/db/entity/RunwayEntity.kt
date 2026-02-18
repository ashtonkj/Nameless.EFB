package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "runways",
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
data class RunwayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val airportIcao: String,
    val ident: String,          // e.g., "03L", "21R"
    val lengthFt: Int,
    val widthFt: Int,
    val surface: String,        // "asphalt", "concrete", "grass", etc.
    val latHe: Double,          // high-end threshold latitude
    val lonHe: Double,
    val latLe: Double,          // low-end threshold latitude
    val lonLe: Double,
    val headingDeg: Double,
    val ilsFreqHz: Int,         // 0 if none
)
