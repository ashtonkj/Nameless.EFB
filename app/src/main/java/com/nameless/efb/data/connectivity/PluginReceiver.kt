package com.nameless.efb.data.connectivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Receives binary UDP datagrams from the Rust X-Plane plugin on [port] 49100.
 *
 * For each valid [SimSnapshot] decoded by [EfbProtocol], invokes [onSnapshot]
 * and sends an ACK datagram back so the plugin watchdog doesn't pause streaming.
 */
class PluginReceiver(
    private val scope: CoroutineScope,
    private val port: Int = 49100,
    private val onSnapshot: (SimSnapshot) -> Unit,
    private val onStatusChange: (Boolean) -> Unit,
) {
    /** Epoch millis of the most recently received valid packet; 0 if none. */
    @Volatile var lastPacketTime: Long = 0L

    private var socket: DatagramSocket? = null
    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(port).also { socket = it }
                val buffer = ByteArray(8192)
                val packet = DatagramPacket(buffer, buffer.size)
                onStatusChange(true)
                while (isActive) {
                    sock.receive(packet)
                    val snapshot = EfbProtocol.decode(packet.data, packet.length) ?: continue
                    lastPacketTime = System.currentTimeMillis()
                    onSnapshot(snapshot)
                    sendAck(sock, packet.address, packet.port)
                }
            } catch (_: Exception) {
                onStatusChange(false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        socket?.close()
        socket = null
    }

    private fun sendAck(sock: DatagramSocket, addr: InetAddress, replyPort: Int) {
        val ack = EfbProtocol.buildAck()
        sock.send(DatagramPacket(ack, ack.size, addr, replyPort))
    }
}
