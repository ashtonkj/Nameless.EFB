package com.nameless.efb.data.connectivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

/**
 * Receives GDL-90 formatted ADS-B data from X-Plane 12.3+ on UDP multicast
 * 239.253.1.1:4000 (GDL-90 standard port).
 *
 * GDL-90 framing: 0x7E start flag, HDLC byte-escape stuffing (0x7D escape,
 * XOR 0x20 for 0x7E/0x7D in data), CRC-16 CCITT, 0x7E end flag.
 *
 * Messages parsed:
 * - 0x0A Ownship Report: lat/lon/altitude/groundspeed
 * - 0x65 AHRS Extension (X-Plane / ForeFlight): pitch/roll/heading
 */
class Gdl90Receiver(
    private val scope: CoroutineScope,
    private val onSnapshot: (SimSnapshot) -> Unit,
) {
    /** Epoch millis of the most recently received valid GDL-90 frame; 0 if none. */
    @Volatile var lastPacketTime: Long = 0L

    private var socket: MulticastSocket? = null
    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            try {
                val sock = MulticastSocket(4000).also { socket = it }
                sock.joinGroup(InetAddress.getByName("239.253.1.1"))
                val buf = ByteArray(2048)
                val packet = DatagramPacket(buf, buf.size)
                // Accumulate partial state between frames (AHRS + ownship)
                var lat = 0.0
                var lon = 0.0
                var altFt = 0f
                var pitchDeg = 0f
                var rollDeg = 0f
                var headingDeg = 0f
                var hasOwnship = false
                var hasAhrs = false

                while (isActive) {
                    sock.receive(packet)
                    val frames = extractFrames(packet.data, packet.length)
                    for (frame in frames) {
                        if (frame.isEmpty()) continue
                        when (frame[0].toInt() and 0xFF) {
                            0x0A -> {
                                val r = parseOwnshipReport(frame) ?: continue
                                lat = r.lat; lon = r.lon; altFt = r.altFt
                                hasOwnship = true
                            }
                            0x65 -> {
                                val r = parseAhrsMessage(frame) ?: continue
                                pitchDeg = r.pitch; rollDeg = r.roll; headingDeg = r.heading
                                hasAhrs = true
                            }
                        }
                    }
                    if (hasOwnship) {
                        lastPacketTime = System.currentTimeMillis()
                        onSnapshot(SimSnapshot(
                            latitude      = lat,
                            longitude     = lon,
                            elevationM    = (altFt * 0.3048).toDouble(),
                            pitchDeg      = pitchDeg,
                            rollDeg       = rollDeg,
                            magHeadingDeg = headingDeg,
                        ))
                    }
                }
            } catch (_: Exception) { }
        }
    }

    fun stop() {
        job?.cancel()
        socket?.close()
        socket = null
    }

    // ── Frame extraction ─────────────────────────────────────────────────────

    private fun extractFrames(data: ByteArray, len: Int): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var start = -1
        for (i in 0 until len) {
            if (data[i] == 0x7E.toByte()) {
                if (start >= 0 && i > start + 1) {
                    val raw = data.sliceArray(start + 1 until i)
                    frames.add(hdlcUnstuff(raw))
                }
                start = i
            }
        }
        return frames
    }

    /** Remove HDLC byte-escape stuffing (0x7D escape, XOR 0x20). */
    private fun hdlcUnstuff(data: ByteArray): ByteArray {
        val result = ArrayList<Byte>(data.size)
        var i = 0
        while (i < data.size) {
            if (data[i] == 0x7D.toByte() && i + 1 < data.size) {
                result.add((data[i + 1].toInt() xor 0x20).toByte())
                i += 2
            } else {
                result.add(data[i])
                i++
            }
        }
        return result.toByteArray()
    }

    // ── Message parsers ──────────────────────────────────────────────────────

    private data class OwnshipData(val lat: Double, val lon: Double, val altFt: Float)

    /** Parse MSG 0x0A Ownship Report (at least 29 bytes including message ID). */
    private fun parseOwnshipReport(frame: ByteArray): OwnshipData? {
        if (frame.size < 29) return null
        // Bytes 5-7: latitude (signed 24-bit, 180/2^23 deg/LSB)
        val latRaw = ((frame[5].toInt() and 0xFF) shl 16) or
                     ((frame[6].toInt() and 0xFF) shl 8) or
                     (frame[7].toInt() and 0xFF)
        val latSigned = if (latRaw and 0x800000 != 0) latRaw or -0x1000000 else latRaw
        val lat = latSigned.toDouble() * 180.0 / (1 shl 23)

        // Bytes 8-10: longitude (signed 24-bit, 360/2^24 deg/LSB)
        val lonRaw = ((frame[8].toInt() and 0xFF) shl 16) or
                     ((frame[9].toInt() and 0xFF) shl 8) or
                     (frame[10].toInt() and 0xFF)
        val lonSigned = if (lonRaw and 0x800000 != 0) lonRaw or -0x1000000 else lonRaw
        val lon = lonSigned.toDouble() * 360.0 / (1 shl 24)

        // Bytes 11-12: altitude (upper 12 bits, 25ft increments above -1000ft)
        val altCode = ((frame[11].toInt() and 0xFF) shl 4) or ((frame[12].toInt() and 0xF0) ushr 4)
        val altFt = if (altCode == 0xFFF) 0f else altCode * 25f - 1000f

        return OwnshipData(lat, lon, altFt)
    }

    private data class AhrsData(val pitch: Float, val roll: Float, val heading: Float)

    /** Parse MSG 0x65 AHRS Extension (ForeFlight / X-Plane, 8 bytes). */
    private fun parseAhrsMessage(frame: ByteArray): AhrsData? {
        if (frame.size < 8) return null
        // Bytes 1-2: pitch  (signed int16 big-endian, 0.1 deg/LSB)
        val pitch = (((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF))
            .toShort().toFloat() * 0.1f
        // Bytes 3-4: roll
        val roll = (((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF))
            .toShort().toFloat() * 0.1f
        // Bytes 5-6: heading (unsigned int16 big-endian, 0.1 deg/LSB)
        val hdg = (((frame[5].toInt() and 0xFF) shl 8) or (frame[6].toInt() and 0xFF)).toFloat() * 0.1f
        return AhrsData(pitch, roll, hdg)
    }
}
