package com.nameless.efb.data.connectivity

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kotlin mirror of the Rust efb-protocol codec.
 *
 * Header layout (little-endian, 17 bytes):
 * ```
 * [0..4]   magic       : u32  = 0xEFB12345
 * [4..6]   version     : u16  = 1
 * [6]      packet_type : u8
 * [7..9]   payload_len : u16
 * [9..13]  sequence    : u32
 * [13..17] checksum    : u32  CRC-32 of payload
 * ```
 * Payload is a serialized [SimSnapshot] (464 bytes) for SimData packets.
 */
object EfbProtocol {

    const val MAGIC: Long = 0xEFB12345L
    const val VERSION: Int = 1
    const val HEADER_LEN: Int = 17
    const val PAYLOAD_LEN: Int = 464

    private const val PACKET_SIM_DATA: Byte = 0x01
    private const val PACKET_ACK: Byte = 0x03

    private val sequence = AtomicInteger(0)

    /**
     * Decode an incoming datagram. Returns a [SimSnapshot] if the packet is a
     * valid SimData frame, or null for any validation failure (wrong magic,
     * version mismatch, bad checksum, truncated payload, non-SimData type).
     */
    fun decode(buf: ByteArray, len: Int): SimSnapshot? {
        if (len < HEADER_LEN) return null
        val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)

        val magic = bb.int.toLong() and 0xFFFFFFFFL
        if (magic != MAGIC) return null

        val version = bb.short.toInt() and 0xFFFF
        if (version != VERSION) return null

        val packetType = bb.get()
        if (packetType != PACKET_SIM_DATA) return null

        val payloadLen = bb.short.toInt() and 0xFFFF
        if (payloadLen > 65535 || len < HEADER_LEN + payloadLen) return null

        bb.getInt()  // sequence (unused in decode)
        val checksum = bb.int.toLong() and 0xFFFFFFFFL

        val payload = ByteArray(payloadLen).also { bb.get(it) }
        if (crc32(payload) != checksum) return null

        return deserializeSnapshot(payload)
    }

    /**
     * Encode a [SimSnapshot] into a framed UDP datagram (SimData packet).
     */
    fun encode(snapshot: SimSnapshot, seq: Int = sequence.getAndIncrement()): ByteArray {
        val payload = serializeSnapshot(snapshot)
        return buildPacket(seq, PACKET_SIM_DATA, payload)
    }

    /**
     * Build a minimal ACK datagram (empty payload) for the plugin watchdog.
     */
    fun buildAck(seq: Int = 0): ByteArray = buildPacket(seq, PACKET_ACK, ByteArray(0))

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun buildPacket(seq: Int, type: Byte, payload: ByteArray): ByteArray {
        val checksum = crc32(payload)
        val buf = ByteBuffer.allocate(HEADER_LEN + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC.toInt())
        buf.putShort(VERSION.toShort())
        buf.put(type)
        buf.putShort(payload.size.toShort())
        buf.putInt(seq)
        buf.putInt(checksum.toInt())
        buf.put(payload)
        return buf.array()
    }

    private fun serializeSnapshot(s: SimSnapshot): ByteArray {
        val bb = ByteBuffer.allocate(PAYLOAD_LEN).order(ByteOrder.LITTLE_ENDIAN)
        // Position
        bb.putDouble(s.latitude)
        bb.putDouble(s.longitude)
        bb.putDouble(s.elevationM)
        bb.putFloat(s.groundspeedMs)
        // Attitude
        bb.putFloat(s.pitchDeg)
        bb.putFloat(s.rollDeg)
        bb.putFloat(s.magHeadingDeg)
        bb.putFloat(s.groundTrackDeg)
        // Air data
        bb.putFloat(s.iasKts)
        bb.putFloat(s.tasKts)
        bb.putFloat(s.vviFpm)
        bb.putFloat(s.turnRateDegSec)
        bb.putFloat(s.slipDeg)
        bb.putFloat(s.oatDegc)
        bb.putFloat(s.barometerInhg)
        // Engine
        bb.putFloat(s.rpm)
        bb.putFloat(s.mapInhg)
        bb.putFloat(s.fuelFlowKgSec)
        bb.putFloat(s.oilPressPsi)
        bb.putFloat(s.oilTempDegc)
        for (x in s.egtDegc) bb.putFloat(x)
        for (x in s.fuelQtyKg) bb.putFloat(x)
        bb.putFloat(s.busVolts)
        bb.putFloat(s.batteryAmps)
        bb.putFloat(s.suctionInhg)
        // Navigation
        bb.putFloat(s.nav1HdefDot)
        bb.putFloat(s.nav1VdefDot)
        bb.putFloat(s.nav1ObsDeg)
        bb.putFloat(s.gpsDistNm)
        bb.putFloat(s.gpsBearingDeg)
        // Autopilot
        bb.putInt(s.apStateFlags)
        bb.putFloat(s.fdPitchDeg)
        bb.putFloat(s.fdRollDeg)
        bb.putFloat(s.apHeadingBugDeg)
        bb.putFloat(s.apAltitudeFt)
        bb.putFloat(s.apVsFpm)
        // Radios
        bb.putInt(s.com1ActiveHz)
        bb.putInt(s.com1StandbyHz)
        bb.putInt(s.com2ActiveHz)
        bb.putInt(s.nav1ActiveHz)
        bb.putInt(s.nav1StandbyHz)
        bb.putInt(s.transponderCode)
        bb.putInt(s.transponderMode)
        // Markers (bool as u8)
        bb.put(if (s.outerMarker) 1 else 0)
        bb.put(if (s.middleMarker) 1 else 0)
        bb.put(if (s.innerMarker) 1 else 0)
        // Weather
        bb.putFloat(s.windDirDeg)
        bb.putFloat(s.windSpeedKt)
        // Traffic
        for (x in s.trafficLat)  bb.putFloat(x)
        for (x in s.trafficLon)  bb.putFloat(x)
        for (x in s.trafficEleM) bb.putFloat(x)
        bb.put(s.trafficCount.toByte())
        // HSI
        bb.putInt(s.hsiSource)
        return bb.array()
    }

    internal fun deserializeSnapshot(payload: ByteArray): SimSnapshot? {
        if (payload.size < PAYLOAD_LEN) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return SimSnapshot(
            latitude          = bb.double,
            longitude         = bb.double,
            elevationM        = bb.double,
            groundspeedMs     = bb.float,
            pitchDeg          = bb.float,
            rollDeg           = bb.float,
            magHeadingDeg     = bb.float,
            groundTrackDeg    = bb.float,
            iasKts            = bb.float,
            tasKts            = bb.float,
            vviFpm            = bb.float,
            turnRateDegSec    = bb.float,
            slipDeg           = bb.float,
            oatDegc           = bb.float,
            barometerInhg     = bb.float,
            rpm               = bb.float,
            mapInhg           = bb.float,
            fuelFlowKgSec     = bb.float,
            oilPressPsi       = bb.float,
            oilTempDegc       = bb.float,
            egtDegc           = FloatArray(6) { bb.float },
            fuelQtyKg         = FloatArray(2) { bb.float },
            busVolts          = bb.float,
            batteryAmps       = bb.float,
            suctionInhg       = bb.float,
            nav1HdefDot       = bb.float,
            nav1VdefDot       = bb.float,
            nav1ObsDeg        = bb.float,
            gpsDistNm         = bb.float,
            gpsBearingDeg     = bb.float,
            apStateFlags      = bb.int,
            fdPitchDeg        = bb.float,
            fdRollDeg         = bb.float,
            apHeadingBugDeg   = bb.float,
            apAltitudeFt      = bb.float,
            apVsFpm           = bb.float,
            com1ActiveHz      = bb.int,
            com1StandbyHz     = bb.int,
            com2ActiveHz      = bb.int,
            nav1ActiveHz      = bb.int,
            nav1StandbyHz     = bb.int,
            transponderCode   = bb.int,
            transponderMode   = bb.int,
            outerMarker       = bb.get().toInt() != 0,
            middleMarker      = bb.get().toInt() != 0,
            innerMarker       = bb.get().toInt() != 0,
            windDirDeg        = bb.float,
            windSpeedKt       = bb.float,
            trafficLat        = FloatArray(20) { bb.float },
            trafficLon        = FloatArray(20) { bb.float },
            trafficEleM       = FloatArray(20) { bb.float },
            trafficCount      = bb.get().toInt() and 0xFF,
            hsiSource         = bb.int,
        )
    }

    // ── CRC-32 (ISO 3309 / Ethernet polynomial 0xEDB88320) ───────────────────
    // Matches the Rust crc32() in efb-protocol/src/lib.rs exactly.

    internal fun crc32(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (byte in data) {
            crc = crc xor (byte.toLong() and 0xFFL)
            repeat(8) {
                crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1
            }
        }
        return crc.inv() and 0xFFFFFFFFL
    }
}
