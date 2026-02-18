package com.nameless.efb.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nameless.efb.data.db.dao.AircraftProfileDao
import com.nameless.efb.data.db.dao.AirportDao
import com.nameless.efb.data.db.dao.AirspaceDao
import com.nameless.efb.data.db.dao.AirwayDao
import com.nameless.efb.data.db.dao.FixDao
import com.nameless.efb.data.db.dao.FlightPlanDao
import com.nameless.efb.data.db.dao.LogbookDao
import com.nameless.efb.data.db.dao.MetarDao
import com.nameless.efb.data.db.dao.NavaidDao
import com.nameless.efb.data.db.dao.ObstacleDao
import com.nameless.efb.data.db.dao.PlateAnnotationDao
import com.nameless.efb.data.db.entity.AircraftProfileEntity
import com.nameless.efb.data.db.entity.AirportEntity
import com.nameless.efb.data.db.entity.AirspaceEntity
import com.nameless.efb.data.db.entity.AirwayEntity
import com.nameless.efb.data.db.entity.AirwaySegmentEntity
import com.nameless.efb.data.db.entity.FixEntity
import com.nameless.efb.data.db.entity.FlightPlanEntity
import com.nameless.efb.data.db.entity.FlightPlanWaypointEntity
import com.nameless.efb.data.db.entity.FrequencyEntity
import com.nameless.efb.data.db.entity.LogbookEntryEntity
import com.nameless.efb.data.db.entity.MetarCacheEntity
import com.nameless.efb.data.db.entity.NavaidEntity
import com.nameless.efb.data.db.entity.ObstacleEntity
import com.nameless.efb.data.db.entity.PlateAnnotationEntity
import com.nameless.efb.data.db.entity.RunwayEntity

@Database(
    entities = [
        AirportEntity::class,
        RunwayEntity::class,
        FrequencyEntity::class,
        NavaidEntity::class,
        FixEntity::class,
        AirwayEntity::class,
        AirwaySegmentEntity::class,
        AirspaceEntity::class,
        ObstacleEntity::class,
        FlightPlanEntity::class,
        FlightPlanWaypointEntity::class,
        AircraftProfileEntity::class,
        LogbookEntryEntity::class,
        MetarCacheEntity::class,
        PlateAnnotationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class EfbDatabase : RoomDatabase() {

    abstract fun airportDao(): AirportDao
    abstract fun navaidDao(): NavaidDao
    abstract fun fixDao(): FixDao
    abstract fun airwayDao(): AirwayDao
    abstract fun airspaceDao(): AirspaceDao
    abstract fun obstacleDao(): ObstacleDao
    abstract fun flightPlanDao(): FlightPlanDao
    abstract fun aircraftProfileDao(): AircraftProfileDao
    abstract fun logbookDao(): LogbookDao
    abstract fun metarDao(): MetarDao
    abstract fun plateAnnotationDao(): PlateAnnotationDao

    companion object {
        @Volatile
        private var instance: EfbDatabase? = null

        fun getInstance(context: Context): EfbDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }

        private fun buildDatabase(context: Context) =
            Room.databaseBuilder(context, EfbDatabase::class.java, "efb.db")
                .build()
    }
}
