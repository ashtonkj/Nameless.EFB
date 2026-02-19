package com.nameless.efb.rendering.terrain

import android.util.LruCache
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Caches decoded 512×512 terrain elevation grids loaded from EFB float16 tile files.
 *
 * Tile files are named `{lat}_{lon}.f16` (e.g. `S34_E018.f16`) and contain
 * 512 × 512 big-endian float16 elevation values in metres, row 0 = north edge.
 *
 * @param tileDir  Directory where `.f16` tile files are stored on-device.
 */
class TerrainTileCache(private val tileDir: File) {

    // 48 tiles × (512 × 512 × 2 bytes) ≈ 24 MB
    private val cache = LruCache<TileKey, ShortArray>(48)

    /**
     * Returns terrain elevation in metres at [latDeg], [lonDeg], or
     * [Float.NaN] if no tile is available for that location.
     */
    fun queryElevation(latDeg: Double, lonDeg: Double): Float {
        val tileKey = TileKey(
            lat = floor(latDeg).toInt(),
            lon = floor(lonDeg).toInt(),
        )
        val grid = getElevationGrid(tileKey) ?: return Float.NaN

        // Fractional position within the tile [0..1)
        val fracLat = latDeg - tileKey.lat
        val fracLon = lonDeg - tileKey.lon

        // Pixel coordinates — row 0 = north (high lat), col 0 = west (low lon)
        val px = (fracLon * (TILE_DIM - 1)).coerceIn(0.0, (TILE_DIM - 2).toDouble())
        val py = ((1.0 - fracLat) * (TILE_DIM - 1)).coerceIn(0.0, (TILE_DIM - 2).toDouble())
        val ix = px.toInt()
        val iy = py.toInt()
        val fx = (px - ix).toFloat()
        val fy = (py - iy).toFloat()

        val s00 = half16ToFloat(grid[iy * TILE_DIM + ix])
        val s10 = half16ToFloat(grid[iy * TILE_DIM + ix + 1])
        val s01 = half16ToFloat(grid[(iy + 1) * TILE_DIM + ix])
        val s11 = half16ToFloat(grid[(iy + 1) * TILE_DIM + ix + 1])

        val top = s00 * (1 - fx) + s10 * fx
        val bot = s01 * (1 - fx) + s11 * fx
        return top * (1 - fy) + bot * fy
    }

    // ----- public tile access -----

    /**
     * Returns the raw 512×512 float16 elevation grid for the 1°×1° tile
     * whose south-west corner is at ([lat], [lon]) degrees.
     *
     * Used by [com.nameless.efb.rendering.map.overlay.TawsRenderer] to upload
     * the grid as a GL texture.  Returns null if the tile file is not cached.
     */
    fun getTileGrid(lat: Int, lon: Int): ShortArray? = getElevationGrid(TileKey(lat, lon))

    // ----- private helpers -----

    private fun getElevationGrid(key: TileKey): ShortArray? {
        cache.get(key)?.let { return it }

        val file = File(tileDir, key.toFileName())
        if (!file.exists()) return null

        val bytes = file.readBytes()
        if (bytes.size < TILE_DIM * TILE_DIM * 2) return null

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val grid = ShortArray(TILE_DIM * TILE_DIM) { buf.short }
        cache.put(key, grid)
        return grid
    }

    private companion object {
        const val TILE_DIM = 512

        /** IEEE 754 float16 → float32 bit-level conversion. */
        fun half16ToFloat(bits: Short): Float {
            val h = bits.toInt() and 0xFFFF
            val sign = (h ushr 15) shl 31
            val exp  = (h ushr 10) and 0x1F
            val mant = h and 0x3FF
            return when (exp) {
                0    -> java.lang.Float.intBitsToFloat(sign or (mant shl 13))          // subnormal/zero
                0x1F -> java.lang.Float.intBitsToFloat(sign or 0x7F800000 or (mant shl 13)) // inf/nan
                else -> java.lang.Float.intBitsToFloat(sign or ((exp + 112) shl 23) or (mant shl 13))
            }
        }
    }
}

/** Integer tile-corner key — bottom-left corner of a 1°×1° tile. */
data class TileKey(val lat: Int, val lon: Int) {
    /** Generates the filename expected by [TerrainTileCache] (e.g. `S34_E018.f16`). */
    fun toFileName(): String {
        val latStr = if (lat >= 0) "N%02d".format(lat)  else "S%02d".format(-lat)
        val lonStr = if (lon >= 0) "E%03d".format(lon)  else "W%03d".format(-lon)
        return "${latStr}_${lonStr}.f16"
    }
}
