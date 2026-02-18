package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "fixes",
    indices = [Index("identifier"), Index("icaoRegion")],
)
data class FixEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identifier: String,
    val latitude: Double,
    val longitude: Double,
    val icaoRegion: String,
)
