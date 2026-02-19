package com.nameless.efb.data.connectivity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UdpBroadcastReceiverTest {

    /** Build a raw X-Plane DATA datagram with the given groups. */
    private fun buildXplanePacket(groups: Map<Int, FloatArray>): ByteArray {
        val buf = ByteBuffer.allocate(5 + groups.size * 36).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('D'.code.toByte())
        buf.put('A'.code.toByte())
        buf.put('T'.code.toByte())
        buf.put('A'.code.toByte())
        buf.put(0)
        for ((index, floats) in groups) {
            buf.putInt(index)
            for (f in floats) buf.putFloat(f)
        }
        return buf.array()
    }

    @Test
    fun `group 3 IAS is mapped to iasKts`() {
        val pkt = buildXplanePacket(mapOf(3 to floatArrayOf(110f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)))
        val snap = UdpBroadcastReceiver.parseXplaneUdp(pkt)
        assertNotNull(snap)
        assertEquals(110f, snap!!.iasKts, 0.5f)
    }

    @Test
    fun `group 6 attitude is mapped correctly`() {
        val pkt = buildXplanePacket(mapOf(
            6 to floatArrayOf(5.0f, -3.0f, 280.0f, 283.0f, 0f, 0f, 0f, 0f)
        ))
        val snap = UdpBroadcastReceiver.parseXplaneUdp(pkt)
        assertNotNull(snap)
        assertEquals(5.0f,  snap!!.pitchDeg,       0.01f)
        assertEquals(-3.0f, snap.rollDeg,          0.01f)
        assertEquals(283.0f, snap.magHeadingDeg,   0.01f)
    }

    @Test
    fun `group 17 position is mapped correctly`() {
        val pkt = buildXplanePacket(mapOf(
            17 to floatArrayOf(-26.14f, 28.24f, 5560f, 0f, 0f, 0f, 0f, 0f)
        ))
        val snap = UdpBroadcastReceiver.parseXplaneUdp(pkt)
        assertNotNull(snap)
        assertEquals(-26.14,  snap!!.latitude,  0.001)
        assertEquals(28.24,   snap.longitude,   0.001)
        assertEquals(5560 * 0.3048, snap.elevationM, 1.0)
    }

    @Test
    fun `all three groups produce complete snapshot`() {
        val pkt = buildXplanePacket(mapOf(
            3  to floatArrayOf(95f, 0f, 100f, 98f, 0f, 0f, 0f, 0f),
            6  to floatArrayOf(2f, 1f, 270f, 272f, 0f, 0f, 0f, 0f),
            17 to floatArrayOf(-33.96f, 18.60f, 200f, 0f, 0f, 0f, 0f, 0f),
        ))
        val snap = UdpBroadcastReceiver.parseXplaneUdp(pkt)!!
        assertEquals(95f,    snap.iasKts,         0.1f)
        assertEquals(100f,   snap.tasKts,         0.1f)
        assertEquals(272f,   snap.magHeadingDeg,  0.1f)
        assertEquals(-33.96, snap.latitude,       0.001)
    }

    @Test
    fun `wrong header returns null`() {
        val bad = byteArrayOf(0x58, 0x58, 0x58, 0x58, 0x00)
        assertNull(UdpBroadcastReceiver.parseXplaneUdp(bad))
    }

    @Test
    fun `empty data returns null`() {
        assertNull(UdpBroadcastReceiver.parseXplaneUdp(ByteArray(0)))
    }
}
