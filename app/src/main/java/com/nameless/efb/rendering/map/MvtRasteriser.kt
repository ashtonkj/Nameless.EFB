package com.nameless.efb.rendering.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.nameless.efb.data.map.GeomType
import com.nameless.efb.data.map.MapLayer
import com.nameless.efb.data.map.decodeMvt
import java.nio.ByteBuffer

/**
 * Rasterises a Mapbox Vector Tile (MVT) to a 512×512 RGBA byte array.
 *
 * Tiles are decoded on an IO thread then rasterised with Android [Canvas] into
 * an in-memory [Bitmap]. The resulting RGBA bytes are queued for GL texture
 * upload by [TileCache].
 *
 * Colour palette is deliberately muted so map tile colours do not compete
 * with the aviation overlays drawn on top.
 */
object MvtRasteriser {

    /**
     * Decodes [mvtBytes] and renders to a [tileSize]×[tileSize] RGBA8888 bitmap.
     *
     * Returns the raw RGBA byte array (length = tileSize² × 4).
     */
    fun rasterise(mvtBytes: ByteArray, tileSize: Int = 512): ByteArray {
        val bitmap = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background: muted off-white land
        canvas.drawColor(0xFFEEEAE0.toInt())

        val layers = decodeMvt(mvtBytes)
        val scaleFactor = tileSize.toFloat() / 4096f   // MVT extent default = 4096

        for (layer in layers) {
            drawLayer(canvas, layer, scaleFactor, tileSize)
        }

        val byteArray = ByteArray(tileSize * tileSize * 4)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(byteArray))
        bitmap.recycle()
        return byteArray
    }

    // ── Layer draw dispatch ───────────────────────────────────────────────────

    private fun drawLayer(canvas: Canvas, layer: MapLayer, scale: Float, tileSize: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (feature in layer.features) {
            when (layer.name) {
                "water", "waterway" -> {
                    paint.color = 0xFFADD8E6.toInt()   // muted blue
                    paint.style = Paint.Style.FILL
                    drawGeometry(canvas, feature.type, feature.geometry, paint, scale, fill = true)
                }
                "landuse", "landcover" -> {
                    val kind = feature.tags["class"] ?: feature.tags["subclass"] ?: ""
                    paint.color = when (kind) {
                        "forest", "wood" -> 0xFFC8DDB4.toInt()  // pale green
                        "residential"    -> 0xFFE8E0D8.toInt()  // warm grey
                        "industrial"     -> 0xFFD9CFC8.toInt()
                        else             -> return
                    }
                    paint.style = Paint.Style.FILL
                    drawGeometry(canvas, feature.type, feature.geometry, paint, scale, fill = true)
                }
                "road", "transportation" -> {
                    val classTag = feature.tags["class"] ?: ""
                    paint.color = 0xFFBBBBBB.toInt()   // grey road
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = when (classTag) {
                        "motorway", "trunk"   -> 3f
                        "primary", "secondary" -> 2f
                        else                   -> 1f
                    }
                    drawGeometry(canvas, feature.type, feature.geometry, paint, scale, fill = false)
                }
                "building" -> {
                    paint.color = 0xFFD0C8C0.toInt()   // pale brown
                    paint.style = Paint.Style.FILL
                    drawGeometry(canvas, feature.type, feature.geometry, paint, scale, fill = true)
                }
            }
        }
    }

    // ── Geometry renderer ─────────────────────────────────────────────────────

    private fun drawGeometry(
        canvas: Canvas,
        type: GeomType,
        geometry: List<FloatArray>,
        paint: Paint,
        scale: Float,
        fill: Boolean,
    ) {
        for (ring in geometry) {
            if (ring.size < 2) continue
            val path = Path()
            path.moveTo(ring[0] * scale, ring[1] * scale)
            var i = 2
            while (i + 1 < ring.size) {
                path.lineTo(ring[i] * scale, ring[i + 1] * scale)
                i += 2
            }
            if (fill && type == GeomType.POLYGON) path.close()
            canvas.drawPath(path, paint)
        }
    }
}
