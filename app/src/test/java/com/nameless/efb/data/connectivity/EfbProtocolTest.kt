package com.nameless.efb.data.connectivity

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EfbProtocolTest {

    private fun testSnapshot() = SimSnapshot(
        latitude       = -26.1392,
        longitude      = 28.2462,
        elevationM     = 1694.0,
        iasKts         = 120.5f,
        barometerInhg  = 29.92f,
        oatDegc        = 22.0f,
        transponderCode = 7000,
        com1ActiveHz   = 118_025_000,
        egtDegc        = floatArrayOf(680f, 690f, 695f, 685f, 688f, 692f),
        fuelQtyKg      = floatArrayOf(75f, 75f),
        trafficCount   = 2,
        outerMarker    = true,
    )

    @Test
    fun `encode then decode preserves position fields`() {
        val original = testSnapshot()
        val encoded = EfbProtocol.encode(original, seq = 1)
        val decoded = EfbProtocol.decode(encoded, encoded.size)

        assertNotNull(decoded)
        assertEquals(original.latitude,      decoded!!.latitude,      1e-9)
        assertEquals(original.longitude,     decoded.longitude,       1e-9)
        assertEquals(original.elevationM,    decoded.elevationM,      1e-9)
    }

    @Test
    fun `encode then decode preserves all scalar fields`() {
        val original = testSnapshot()
        val encoded = EfbProtocol.encode(original, seq = 42)
        val decoded = EfbProtocol.decode(encoded, encoded.size)!!

        assertEquals(original.iasKts,          decoded.iasKts,          1e-4f)
        assertEquals(original.barometerInhg,   decoded.barometerInhg,   1e-4f)
        assertEquals(original.transponderCode, decoded.transponderCode)
        assertEquals(original.com1ActiveHz,    decoded.com1ActiveHz)
        assertEquals(original.trafficCount,    decoded.trafficCount)
        assertEquals(original.outerMarker,     decoded.outerMarker)
    }

    @Test
    fun `encode then decode preserves array fields`() {
        val original = testSnapshot()
        val decoded = EfbProtocol.decode(EfbProtocol.encode(original), EfbProtocol.encode(original).size)!!

        assertArrayEquals(original.egtDegc,   decoded.egtDegc,   1e-3f)
        assertArrayEquals(original.fuelQtyKg, decoded.fuelQtyKg, 1e-3f)
    }

    @Test
    fun `wrong magic returns null`() {
        val buf = EfbProtocol.encode(testSnapshot()).copyOf()
        buf[0] = (buf[0].toInt() xor 0xFF).toByte()
        assertNull(EfbProtocol.decode(buf, buf.size))
    }

    @Test
    fun `wrong version returns null`() {
        val buf = EfbProtocol.encode(testSnapshot()).copyOf()
        buf[4] = 99  // version byte
        assertNull(EfbProtocol.decode(buf, buf.size))
    }

    @Test
    fun `bad checksum returns null`() {
        val buf = EfbProtocol.encode(testSnapshot()).copyOf()
        buf[EfbProtocol.HEADER_LEN + 8] = (buf[EfbProtocol.HEADER_LEN + 8].toInt() xor 0xFF).toByte()
        assertNull(EfbProtocol.decode(buf, buf.size))
    }

    @Test
    fun `too short returns null`() {
        assertNull(EfbProtocol.decode(ByteArray(10), 10))
        assertNull(EfbProtocol.decode(ByteArray(0), 0))
    }

    @Test
    fun `buildAck produces valid 17-byte header-only packet`() {
        val ack = EfbProtocol.buildAck(seq = 5)
        // An ACK has no payload so total size equals HEADER_LEN
        assertEquals(EfbProtocol.HEADER_LEN, ack.size)
    }

    @Test
    fun `crc32 matches Rust reference vector`() {
        // CRC-32 of "123456789" (standard check value = 0xCBF43926)
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0xCBF43926L, EfbProtocol.crc32(data))
    }

    @Test
    fun `deserializeSnapshot returns null for undersized payload`() {
        assertNull(EfbProtocol.deserializeSnapshot(ByteArray(100)))
    }
}
