package com.nameless.efb.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.nameless.efb.data.db.entity.FixEntity
import com.nameless.efb.data.db.entity.NavaidEntity

@Dao
interface NavaidDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(navaids: List<NavaidEntity>)

    @Query("SELECT * FROM navaids WHERE identifier = :id AND icaoRegion = :region LIMIT 1")
    suspend fun byIdentAndRegion(id: String, region: String): NavaidEntity?

    @Query("SELECT * FROM navaids WHERE identifier = :id ORDER BY type")
    suspend fun byIdent(id: String): List<NavaidEntity>

    @RawQuery
    suspend fun nearbyRaw(query: SupportSQLiteQuery): List<NavaidEntity>

    @Query("SELECT COUNT(*) FROM navaids")
    suspend fun count(): Long
}

@Dao
interface FixDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(fixes: List<FixEntity>)

    @Query("SELECT * FROM fixes WHERE identifier = :id AND icaoRegion = :region LIMIT 1")
    suspend fun byIdentAndRegion(id: String, region: String): FixEntity?

    @Query("SELECT COUNT(*) FROM fixes")
    suspend fun count(): Long
}
