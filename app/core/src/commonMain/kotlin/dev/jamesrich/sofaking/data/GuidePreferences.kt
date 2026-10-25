package dev.jamesrich.sofaking.data

/** How the app writes clock times: the platform's own 12- or 24-hour preference, or one of them outright (previews). */
enum class ClockFormat { AUTO, TWELVE_HOUR, TWENTY_FOUR_HOUR }

/** How tall the guide's rows are; COMFORTABLE is the TV's 56 dp, COMPACT fits more channels on screen. */
enum class GuideDensity { COMFORTABLE, COMPACT }
