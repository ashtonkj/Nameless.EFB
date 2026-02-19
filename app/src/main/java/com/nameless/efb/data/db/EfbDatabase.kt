package com.nameless.efb.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
import com.nameless.efb.data.db.dao.ProcedureDao
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
import com.nameless.efb.data.db.entity.ProcedureEntity
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
        ProcedureEntity::class,
        FlightPlanEntity::class,
        FlightPlanWaypointEntity::class,
        AircraftProfileEntity::class,
        LogbookEntryEntity::class,
        MetarCacheEntity::class,
        PlateAnnotationEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class EfbDatabase : RoomDatabase() {

    abstract fun airportDao(): AirportDao
    abstract fun navaidDao(): NavaidDao
    abstract fun fixDao(): FixDao
    abstract fun airwayDao(): AirwayDao
    abstract fun airspaceDao(): AirspaceDao
    abstract fun obstacleDao(): ObstacleDao
    abstract fun procedureDao(): ProcedureDao
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

        private fun buildDatabase(context: Context): EfbDatabase {
            val builder = Room.databaseBuilder(
                context, EfbDatabase::class.java, "navdata.db"
            )

            // Pre-populate from the bundled nav database if it has been built.
            // Falls back to an empty database when the asset is not yet present
            // (dev builds before running nav-data-builder).
            try {
                context.assets.open("navdata/navdata.db").close()
                builder.createFromAsset("navdata/navdata.db")
            } catch (_: java.io.IOException) {
                // Asset not bundled — Room creates a fresh empty database.
            }

            return builder
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .addCallback(rtreeCallback)
                .build()
        }

        /**
         * Migration 1 → 2: adds the `procedures` table (Navigraph stub).
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS procedures (
                        id           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        airportIcao  TEXT NOT NULL DEFAULT '',
                        type         TEXT NOT NULL DEFAULT '',
                        identifier   TEXT NOT NULL DEFAULT '',
                        runway       TEXT NOT NULL DEFAULT '',
                        transition   TEXT NOT NULL DEFAULT '',
                        waypointsJson TEXT NOT NULL DEFAULT '[]',
                        airacCycle   TEXT NOT NULL DEFAULT ''
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_proc_airport ON procedures(airportIcao)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_proc_type    ON procedures(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_proc_ident   ON procedures(identifier)")
            }
        }

        /**
         * Creates R-tree virtual tables on first database creation.
         *
         * Room cannot declare virtual tables as entities, so they are created
         * here via a [RoomDatabase.Callback].  The [AirportDao.nearbyRaw] and
         * [NavaidDao.nearbyRaw] queries depend on these tables existing.
         */
        private val rtreeCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS airports_rtree " +
                    "USING rtree(id, lat_min, lat_max, lon_min, lon_max)"
                )
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS navaids_rtree " +
                    "USING rtree(id, lat_min, lat_max, lon_min, lon_max)"
                )
            }
        }
    }
}
