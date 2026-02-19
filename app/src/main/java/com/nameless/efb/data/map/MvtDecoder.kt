package com.nameless.efb.data.map

/**
 * Lightweight Mapbox Vector Tile (MVT 2.1) protobuf decoder.
 *
 * Only the fields needed for rasterisation are decoded:
 *   - Layer name, extent, keys, values
 *   - Feature geometry type and command stream
 *   - Feature tag pairs (for styling decisions)
 *
 * Reference: https://github.com/mapbox/vector-tile-spec/tree/master/2.1
 */

// ── Public model types ─────────────────────────────────────────────────────────

enum class GeomType { UNKNOWN, POINT, LINESTRING, POLYGON }

/**
 * A single map feature decoded from an MVT tile.
 *
 * [geometry] is a list of rings/lines; each ring/line is a list of (x, y)
 * pairs in tile-local coordinates (0..extent), stored as `[x0, y0, x1, y1, …]`.
 *
 * [tags] maps layer key strings to value strings (values are coerced to string
 * for simplicity; callers that need numeric precision can parse as needed).
 */
data class MapFeature(
    val type: GeomType,
    val geometry: List<FloatArray>,   // each FloatArray = interleaved x,y pairs
    val tags: Map<String, String>,
)

/** A decoded MVT layer. */
data class MapLayer(
    val name: String,
    val extent: Int,
    val features: List<MapFeature>,
)

// ── Decoder entry point ────────────────────────────────────────────────────────

/**
 * Decodes [bytes] as an MVT protobuf tile and returns all layers.
 *
 * Returns an empty list if [bytes] is null, empty, or unparseable.
 */
fun decodeMvt(bytes: ByteArray?): List<MapLayer> {
    if (bytes == null || bytes.isEmpty()) return emptyList()
    return try {
        ProtoReader(bytes).readTile()
    } catch (_: Exception) {
        emptyList()
    }
}

// ── Internal protobuf reader ───────────────────────────────────────────────────

private class ProtoReader(private val buf: ByteArray) {

    private var pos = 0

    // ── Tile (field 3 = Layer) ─────────────────────────────────────────────────

    fun readTile(): List<MapLayer> {
        val layers = mutableListOf<MapLayer>()
        while (pos < buf.size) {
            val (field, wireType) = readTag()
            when {
                field == 3 && wireType == 2 -> layers.add(readLayer(readBytes()))
                else -> skipField(wireType)
            }
        }
        return layers
    }

    // ── Layer ─────────────────────────────────────────────────────────────────

    private fun readLayer(data: ByteArray): MapLayer {
        val r = ProtoReader(data)
        var name = ""
        var extent = 4096
        val keys   = mutableListOf<String>()
        val values = mutableListOf<String>()
        val rawFeatures = mutableListOf<ByteArray>()

        while (r.pos < data.size) {
            val (field, wireType) = r.readTag()
            when {
                field == 1  && wireType == 2 -> name = r.readString()
                field == 2  && wireType == 2 -> rawFeatures.add(r.readBytes())
                field == 3  && wireType == 2 -> keys.add(r.readString())
                field == 4  && wireType == 2 -> values.add(r.readLayerValue())
                field == 5  && wireType == 0 -> extent = r.readVarint().toInt()
                else -> r.skipField(wireType)
            }
        }

        val features = rawFeatures.map { readFeature(it, keys, values) }
        return MapLayer(name, extent, features)
    }

    // ── Feature ───────────────────────────────────────────────────────────────

    private fun readFeature(
        data: ByteArray,
        keys: List<String>,
        values: List<String>,
    ): MapFeature {
        val r = ProtoReader(data)
        var type = GeomType.UNKNOWN
        var rawTags = intArrayOf()
        var rawGeom = intArrayOf()

        while (r.pos < data.size) {
            val (field, wireType) = r.readTag()
            when {
                field == 3 && wireType == 0 -> type = GeomType.entries.getOrElse(r.readVarint().toInt()) { GeomType.UNKNOWN }
                field == 2 && wireType == 2 -> rawTags = r.readPackedVarints()
                field == 4 && wireType == 2 -> rawGeom = r.readPackedVarints()
                else -> r.skipField(wireType)
            }
        }

        val tags = buildTagMap(rawTags, keys, values)
        val geometry = decodeGeometry(rawGeom)
        return MapFeature(type, geometry, tags)
    }

    // ── Geometry command decoding ──────────────────────────────────────────────

    private fun decodeGeometry(commands: IntArray): List<FloatArray> {
        val rings = mutableListOf<FloatArray>()
        var i = 0
        var curX = 0; var curY = 0

        while (i < commands.size) {
            val cmdInt = commands[i++]
            val cmdId  = cmdInt and 0x7
            val count  = cmdInt ushr 3

            when (cmdId) {
                1, 2 -> {  // MoveTo (1) or LineTo (2)
                    val coords = FloatArray(count * 2)
                    for (k in 0 until count) {
                        curX += zigzag(commands[i++])
                        curY += zigzag(commands[i++])
                        coords[k * 2]     = curX.toFloat()
                        coords[k * 2 + 1] = curY.toFloat()
                    }
                    if (cmdId == 1 && rings.isEmpty()) {
                        // first MoveTo starts the first ring
                        rings.add(coords)
                    } else if (cmdId == 1) {
                        // new MoveTo starts a new ring
                        rings.add(coords)
                    } else {
                        // LineTo appends to the current ring
                        val current = rings.lastOrNull()
                        if (current != null) {
                            rings[rings.lastIndex] = current + coords
                        } else {
                            rings.add(coords)
                        }
                    }
                }
                7 -> { /* ClosePath — no parameter count; ring is already closed logically */ }
                else -> { /* unknown command — skip remaining */ i = commands.size }
            }
        }
        return rings
    }

    // ── Tag map builder ───────────────────────────────────────────────────────

    private fun buildTagMap(
        rawTags: IntArray,
        keys: List<String>,
        values: List<String>,
    ): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var t = 0
        while (t + 1 < rawTags.size) {
            val ki = rawTags[t]; val vi = rawTags[t + 1]; t += 2
            if (ki < keys.size && vi < values.size) {
                map[keys[ki]] = values[vi]
            }
        }
        return map
    }

    // ── Protobuf primitives ───────────────────────────────────────────────────

    private fun readTag(): Pair<Int, Int> {
        val v = readVarint().toInt()
        return Pair(v ushr 3, v and 0x7)
    }

    fun readVarint(): Long {
        var result = 0L
        var shift  = 0
        while (true) {
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    private fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    private fun readString(): String {
        val len = readVarint().toInt()
        val s = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    /** Reads a Layer.Value message and returns its string representation. */
    private fun readLayerValue(): String {
        val data = readBytes()
        val r = ProtoReader(data)
        var result = ""
        while (r.pos < data.size) {
            val (field, wireType) = r.readTag()
            result = when {
                field == 1 && wireType == 2 -> r.readString()        // string_value
                field == 2 && wireType == 0 -> r.readVarint().toString()  // float (treated as varint wire)
                field == 3 && wireType == 5 -> { r.pos += 4; "?" }   // float (32-bit)
                field == 4 && wireType == 1 -> { r.pos += 8; "?" }   // double (64-bit)
                field == 5 && wireType == 0 -> r.readVarint().toString()  // int_value
                field == 6 && wireType == 0 -> r.readVarint().toString()  // uint_value
                field == 7 && wireType == 0 -> (r.readVarint() != 0L).toString()  // bool_value
                else -> { r.skipField(wireType); result }
            }
        }
        return result
    }

    private fun readPackedVarints(): IntArray {
        val data = readBytes()
        val r = ProtoReader(data)
        val out = mutableListOf<Int>()
        while (r.pos < data.size) out.add(r.readVarint().toInt())
        return out.toIntArray()
    }

    private fun skipField(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> pos += 8
            2 -> { val len = readVarint().toInt(); pos += len }
            5 -> pos += 4
            else -> throw IllegalArgumentException("Unknown wire type $wireType")
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Zigzag-decodes a protobuf signed integer. */
private fun zigzag(n: Int): Int = (n ushr 1) xor -(n and 1)

/** Concatenates two FloatArrays. */
private operator fun FloatArray.plus(other: FloatArray): FloatArray {
    val result = FloatArray(size + other.size)
    copyInto(result)
    other.copyInto(result, size)
    return result
}
