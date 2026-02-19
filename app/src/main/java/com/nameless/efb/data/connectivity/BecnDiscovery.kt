package com.nameless.efb.data.connectivity

import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

/**
 * Listens for X-Plane BECN (beacon) multicast packets on 239.255.1.1:49707 to
 * auto-discover X-Plane instances on the local network.
 *
 * Requires a [WifiManager.MulticastLock] to receive multicast on Android.
 * Timeout: 5 seconds per [start] call.
 */
class BecnDiscovery(
    private val scope: CoroutineScope,
    private val wifiManager: WifiManager,
    private val onFound: (XplaneInstance) -> Unit,
) {
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        multicastLock = wifiManager.createMulticastLock("efb_becn").also { it.acquire() }

        scope.launch(Dispatchers.IO) {
            try {
                val socket = MulticastSocket(49707)
                socket.joinGroup(InetAddress.getByName("239.255.1.1"))
                val buf = ByteArray(1024)
                val packet = DatagramPacket(buf, buf.size)

                withTimeoutOrNull(5_000L) {
                    while (true) {
                        socket.receive(packet)
                        parseBecn(packet)?.let { onFound(it) }
                    }
                }
                socket.close()
            } catch (_: Exception) {
            } finally {
                multicastLock?.release()
                multicastLock = null
            }
        }
    }

    fun stop() {
        multicastLock?.release()
        multicastLock = null
    }

    /**
     * BECN packet: "BECN\0" (5 bytes), then computer name (null-terminated).
     * Source address comes from the UDP packet itself.
     */
    private fun parseBecn(packet: DatagramPacket): XplaneInstance? {
        val data = packet.data
        val len = packet.length
        if (len < 5) return null
        if (data[0] != 'B'.code.toByte() ||
            data[1] != 'E'.code.toByte() ||
            data[2] != 'C'.code.toByte() ||
            data[3] != 'N'.code.toByte()) return null

        val name = extractNullTerminatedString(data, 5, len)
        return XplaneInstance(
            address = packet.address,
            port = 49100,
            name = name,
        )
    }

    private fun extractNullTerminatedString(data: ByteArray, start: Int, limit: Int): String {
        val end = (start until limit).firstOrNull { data[it] == 0.toByte() } ?: limit
        return String(data, start, end - start, Charsets.US_ASCII)
    }
}

data class XplaneInstance(
    val address: InetAddress,
    val port: Int,
    val name: String,
)
