package dev.jamesrich.sofaking.contract

import kotlinx.serialization.Serializable

// Crash diagnostics under /api/v1/diagnostics; the same compatibility rules as LiveTv.kt apply.

/**
 * One crash an app sends to its own server with `POST /api/v1/diagnostics/crashes`. Nothing in it is personal: the
 * app scrubs anything shaped like an address or a token from [message] and [frames] before it leaves the device.
 * [id] is the app's own id for the crash, so a report sent twice is stored once. [exception] is the class name,
 * [frames] the stack trace's frames, one per entry, and [occurredAt] an ISO-8601 instant. [appVersion], [platform]
 * (`android-tv`, `desktop`), [osVersion] and [device] (the model) say where it happened.
 */
@Serializable data class CrashReport(
    val id: String = "",
    val occurredAt: String = "",
    val exception: String = "",
    val message: String = "",
    val frames: List<String> = emptyList(),
    val appVersion: String = "",
    val platform: String = "",
    val osVersion: String = "",
    val device: String = "",
)
