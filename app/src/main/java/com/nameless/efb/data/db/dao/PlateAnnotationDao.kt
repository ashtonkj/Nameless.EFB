package com.nameless.efb.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nameless.efb.data.db.entity.PlateAnnotationEntity

@Dao
interface PlateAnnotationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(annotation: PlateAnnotationEntity)

    @Query("SELECT * FROM plate_annotations WHERE plateId = :plateId LIMIT 1")
    suspend fun forPlate(plateId: String): PlateAnnotationEntity?

    @Query("DELETE FROM plate_annotations WHERE plateId = :plateId")
    suspend fun deleteForPlate(plateId: String)
}
