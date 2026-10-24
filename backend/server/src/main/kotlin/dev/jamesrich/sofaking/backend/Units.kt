package dev.jamesrich.sofaking.backend

// The units the server converts between, each named once.

internal const val MS_PER_SECOND = 1_000
internal const val NS_PER_MS = 1_000_000L
internal const val SECONDS_PER_MINUTE = 60
internal const val SECONDS_PER_HOUR = 3_600
internal const val KIB = 1_024
internal const val MIB = KIB * KIB

/** MPEG's 90 kHz clock, which PTS and SCTE-35 times count. */
internal const val TICKS_PER_MS = 90

/** A PTS is 33 bits and wraps about every 26.5 hours. */
internal const val PTS_BITS = 33
internal const val PTS_MASK = (1L shl PTS_BITS) - 1
internal const val GIB = MIB * KIB
