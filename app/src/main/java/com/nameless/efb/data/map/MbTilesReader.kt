package com.nameless.efb.data.map

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Reads raster or vector tiles from an MBTiles SQLite file.
 *
 * MBTiles spec: https://github.com/mapbox/mbtiles-spec
 *
 * The spec stores Y in TMS convention (Y=0 at the south pole). This reader
 * automatically flips Y to the OSM slippy-map convention (Y=0 at north pole)
 * used by [com.nameless.efb.domain.nav.latLonToTile].
 */
class MbTilesReader(dbFile: File) : AutoCloseable {

    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
    )

    /**
     * Returns raw tile bytes for the given OSM-convention [zoom]/[x]/[y].
     * Returns null if the tile is absent from the database.
     *
     * MBTiles TMS Y-flip: `tmsY = (2^zoom − 1) − y`
     */
    fun getTile(zoom: Int, x: Int, y: Int): ByteArray? {
        val tmsY = (1 shl zoom) - 1 - y
        val cursor = db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(zoom.toString(), x.toString(), tmsY.toString())
        )
        return cursor.use { if (it.moveToFirst()) it.getBlob(0) else null }
    }

    /**
     * Returns the metadata key/value pairs stored in the MBTiles file.
     * Common keys: `name`, `description`, `format`, `bounds`, `minzoom`, `maxzoom`.
     */
    fun metadata(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val cursor = db.rawQuery("SELECT name, value FROM metadata", null)
        cursor.use {
            while (it.moveToNext()) {
                result[it.getString(0)] = it.getString(1)
            }
        }
        return result
    }

    override fun close() = db.close()
}
