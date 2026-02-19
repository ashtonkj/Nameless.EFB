package com.nameless.efb.domain.nav

/** Map rotation mode for the moving map display (MM-03). */
enum class OrientationMode {
    /** True north always points up; ownship rotates. */
    NORTH_UP,

    /** Map rotates so the aircraft ground track points up. */
    TRACK_UP,

    /** Map rotates so the aircraft magnetic heading points up. */
    HEADING_UP,
}
