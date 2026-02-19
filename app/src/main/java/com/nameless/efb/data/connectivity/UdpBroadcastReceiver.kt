package com.nameless.efb.data.connectivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Receives X-Plane native UDP DATA output on [port] 49000.
 *
 * X-Plane DATA format: 5-byte header "DATA\0" followed by 36-byte groups
 * (4-byte group index + 8 floats). Groups used:
 * - 3  → Speeds:   f[0]=IAS(kts), f[2]=TAS(kts), f[3]=GS(kts)
 * - 6  → Attitude: f[0]=pitch(deg), f[1]=roll(deg), f[3]=mag_hdg(deg)
 * - 17 → Position: f[0]=lat(deg), f[1]=lon(deg), f[2]=alt_msl(ft)
 */
class UdpBroadcastReceiver(
    private val scope: CoroutineScope,
    private val port: Int = 49000,
    private val onSnapshot: (SimSnapshot) -> Unit,
) {
    /** Epoch millis of the most recently received valid packet; 0 if none. */
    @Volatile var lastPacketTime: Long = 0L

    private var socket: DatagramSocket? = null
    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(port).also { socket = it }
                val buffer = ByteArray(4096)
                val packet = DatagramPacket(buffer, buffer.size)
                while (isActive) {
                    sock.receive(packet)
                    val snapshot = parseXplaneUdp(packet.data, packet.length) ?: continue
                    lastPacketTime = System.currentTimeMillis()
                    onSnapshot(snapshot)
                }
            } catch (_: Exception) { }
        }
    }

    fun stop() {
        job?.cancel()
        socket?.close()
        socket = null
    }

    companion object {
        /**
         * Parse a raw X-Plane DATA UDP datagram into a [SimSnapshot].
         * Exposed for unit testing without a live socket.
         */
        fun parseXplaneUdp(data: ByteArray, len: Int = data.size): SimSnapshot? {
            if (len < 5) return null
            // Verify "DATA\0" header
            if (data[0] != 'D'.code.toByte() ||
                data[1] != 'A'.code.toByte() ||
                data[2] != 'T'.code.toByte() ||
                data[3] != 'A'.code.toByte()) return null

            val groups = mutableMapOf<Int, FloatArray>()
            var offset = 5
            while (offset + 36 <= len) {
                val bb = ByteBuffer.wrap(data, offset, 36).order(ByteOrder.LITTLE_ENDIAN)
                val groupId = bb.int
                val floats = FloatArray(8) { bb.float }
                groups[groupId] = floats
                offset += 36
            }
            if (groups.isEmpty()) return null
            return buildSnapshot(groups)
        }

        private fun buildSnapshot(groups: Map<Int, FloatArray>): SimSnapshot {
            val speed    = groups[3]
            val attitude = groups[6]
            val position = groups[17]
            return SimSnapshot(
                iasKts         = speed?.get(0) ?: 0f,
                tasKts         = speed?.get(2) ?: 0f,
                groundspeedMs  = (speed?.get(3) ?: 0f) * 0.514444f, // kts → m/s
                pitchDeg       = attitude?.get(0) ?: 0f,
                rollDeg        = attitude?.get(1) ?: 0f,
                magHeadingDeg  = attitude?.get(3) ?: 0f,
                latitude       = (position?.get(0) ?: 0f).toDouble(),
                longitude      = (position?.get(1) ?: 0f).toDouble(),
                elevationM     = ((position?.get(2) ?: 0f) * 0.3048).toDouble(), // ft → m
            )
        }
    }
}
