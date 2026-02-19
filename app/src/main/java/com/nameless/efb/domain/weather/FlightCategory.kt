package com.nameless.efb.domain.weather

/** ICAO/FAA flight rules category derived from METAR ceiling and visibility. */
enum class FlightCategory {
    /** Ceiling ≥ 3000 ft AND visibility ≥ 5 km. */
    VFR,

    /** Ceiling 1000–2999 ft OR visibility 3–4.9 km. */
    MVFR,

    /** Ceiling 300–999 ft OR visibility 800 m – 2.9 km. */
    IFR,

    /** Ceiling < 300 ft OR visibility < 800 m. */
    LIFR,

    /** Category could not be determined (e.g. missing METAR). */
    UNKNOWN,
}
