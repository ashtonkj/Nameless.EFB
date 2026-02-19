package com.nameless.efb.data.connectivity

/**
 * Kotlin mirror of the Rust [SimSnapshot] struct in dataref-schema.
 *
 * Field order and names match the Rust definition exactly so that
 * [EfbProtocol.decode] can deserialize wire bytes into this class.
 *
 * Arrays ([FloatArray]) are used for fixed-size repeated fields to avoid
 * boxing overhead. Note: data class [copy] performs a shallow copy of arrays.
 */
data class SimSnapshot(
    // ── Position ──────────────────────────────────────────────────────────────
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val elevationM: Double = 0.0,
    val groundspeedMs: Float = 0f,

    // ── Attitude ──────────────────────────────────────────────────────────────
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val magHeadingDeg: Float = 0f,
    val groundTrackDeg: Float = 0f,

    // ── Air data ──────────────────────────────────────────────────────────────
    val iasKts: Float = 0f,
    val tasKts: Float = 0f,
    val vviFpm: Float = 0f,
    val turnRateDegSec: Float = 0f,
    val slipDeg: Float = 0f,
    val oatDegc: Float = 15f,
    val barometerInhg: Float = 29.92f,

    // ── Engine (index 0) ──────────────────────────────────────────────────────
    val rpm: Float = 0f,
    val mapInhg: Float = 0f,
    val fuelFlowKgSec: Float = 0f,
    val oilPressPsi: Float = 0f,
    val oilTempDegc: Float = 0f,
    val egtDegc: FloatArray = FloatArray(6),
    val fuelQtyKg: FloatArray = FloatArray(2),
    val busVolts: Float = 0f,
    val batteryAmps: Float = 0f,
    val suctionInhg: Float = 0f,

    // ── Navigation ────────────────────────────────────────────────────────────
    val nav1HdefDot: Float = 0f,
    val nav1VdefDot: Float = 0f,
    val nav1ObsDeg: Float = 0f,
    val gpsDistNm: Float = 0f,
    val gpsBearingDeg: Float = 0f,

    // ── Autopilot ─────────────────────────────────────────────────────────────
    val apStateFlags: Int = 0,
    val fdPitchDeg: Float = 0f,
    val fdRollDeg: Float = 0f,
    val apHeadingBugDeg: Float = 0f,
    val apAltitudeFt: Float = 0f,
    val apVsFpm: Float = 0f,

    // ── Radios ────────────────────────────────────────────────────────────────
    val com1ActiveHz: Int = 0,
    val com1StandbyHz: Int = 0,
    val com2ActiveHz: Int = 0,
    val nav1ActiveHz: Int = 0,
    val nav1StandbyHz: Int = 0,
    val transponderCode: Int = 0,
    val transponderMode: Int = 0,

    // ── Markers ───────────────────────────────────────────────────────────────
    val outerMarker: Boolean = false,
    val middleMarker: Boolean = false,
    val innerMarker: Boolean = false,

    // ── Weather ───────────────────────────────────────────────────────────────
    val windDirDeg: Float = 0f,
    val windSpeedKt: Float = 0f,

    // ── Traffic (up to 20 TCAS targets) ──────────────────────────────────────
    val trafficLat: FloatArray = FloatArray(20),
    val trafficLon: FloatArray = FloatArray(20),
    val trafficEleM: FloatArray = FloatArray(20),
    val trafficCount: Int = 0,    // Rust u8 → Kotlin Int (0..255)

    // ── HSI source ────────────────────────────────────────────────────────────
    val hsiSource: Int = 0,
)
