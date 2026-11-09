package dev.jamesrich.sofaking.data

/**
 * Which look the app draws in: SYSTEM follows the platform's dark mode, DARK and LIGHT pin one, and PREVUE is the
 * deep-blue, yellow-on-blue guide of 1990s television, always dark. A TV is dark whatever is stored, and shows PREVUE
 * when it is chosen. Stored by name, so a build that does not know a value reads SYSTEM.
 */
enum class Appearance { SYSTEM, DARK, LIGHT, PREVUE }
