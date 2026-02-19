package com.nameless.efb.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stub entity for Navigraph instrument procedures (SIDs, STARs, approaches).
 *
 * The table is empty in the bundled navdata.db and is populated on Navigraph
 * AIRAC sync (implemented in Plan 10).  Credentials are stored in Android
 * Keystore (NFR-S01), never in this table.
 */
@Entity(
    tableName = "procedures",
    indices = [Index("airportIcao"), Index("type"), Index("identifier")],
)
data class ProcedureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val airportIcao: String,
    val type: String,           // "SID", "STAR", "APPROACH"
    val identifier: String,     // e.g., "FORT1A"
    val runway: String,         // associated runway or empty for all
    val transition: String,
    val waypointsJson: String,  // ordered list of fix IDs + altitude/speed constraints
    val airacCycle: String,     // e.g., "2401"
)
