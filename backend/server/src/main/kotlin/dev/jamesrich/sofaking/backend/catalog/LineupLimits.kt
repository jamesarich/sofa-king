package dev.jamesrich.sofaking.backend.catalog

// The limits a lineup's names, groups and numbers keep to, in a policy and in an operator's edit alike.

internal const val NAME_LIMIT = 256
internal const val GROUP_LIMIT = 128
internal const val NUMBER_LIMIT = 32
internal const val SOURCES_PER_STATION_LIMIT = 64

/** The channel numbers a lineup may give. */
internal val CHANNEL_NUMBERS = 1..999_999

/** Whether [value] is not blank and at most [limit] characters long. */
internal fun fits(value: String, limit: Int): Boolean = value.isNotBlank() && value.length <= limit
