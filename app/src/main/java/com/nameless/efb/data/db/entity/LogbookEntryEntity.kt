package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "logbook_entries")
data class LogbookEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,             // epoch ms of takeoff
    val departureIcao: String,
    val arrivalIcao: String,
    val aircraftType: String,
    val registration: String,
    val blockTimeSec: Long,     // total block time in seconds
    val flightTimeSec: Long,    // airborne time in seconds
    val distanceNm: Double,
    val maxAltitudeFt: Int,
    val notes: String,
)
