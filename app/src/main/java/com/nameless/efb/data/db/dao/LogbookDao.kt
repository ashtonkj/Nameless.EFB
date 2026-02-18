package com.nameless.efb.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nameless.efb.data.db.entity.LogbookEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LogbookDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: LogbookEntryEntity): Long

    @Update
    suspend fun update(entry: LogbookEntryEntity)

    @Query("SELECT * FROM logbook_entries ORDER BY date DESC")
    fun allEntries(): Flow<List<LogbookEntryEntity>>

    @Query("SELECT * FROM logbook_entries WHERE id = :id")
    suspend fun byId(id: Long): LogbookEntryEntity?

    @Query("SELECT SUM(flightTimeSec) FROM logbook_entries")
    suspend fun totalFlightTimeSec(): Long?

    @Query("SELECT COUNT(*) FROM logbook_entries")
    suspend fun count(): Long
}
