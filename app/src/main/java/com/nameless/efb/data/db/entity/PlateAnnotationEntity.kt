package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "plate_annotations",
    indices = [Index("plateId")],
)
data class PlateAnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val plateId: String,        // Navigraph plate ID or SACAA eAIP plate identifier
    val annotationsJson: String,// serialised list of PlateAnnotation objects
    val updatedAt: Long,
)
