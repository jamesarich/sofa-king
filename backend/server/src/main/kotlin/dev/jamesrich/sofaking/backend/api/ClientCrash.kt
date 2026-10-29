package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.Serializable

/**
 * One crash a device reported, as the admin API lists it: the app's `CrashReport` plus who sent it and when the
 * server got it. [clientId] and [clientName] name the paired device (the name is the report's device model when the
 * token was not a device's); [frames] are the stack trace's frames, scrubbed of anything address- or token-shaped.
 */
@Serializable data class ClientCrash(
    val id: String,
    val clientId: String? = null,
    val clientName: String = "",
    val receivedAt: String,
    val occurredAt: String = "",
    val exception: String = "",
    val message: String = "",
    val frames: List<String> = emptyList(),
    val appVersion: String = "",
    val platform: String = "",
    val osVersion: String = "",
    val device: String = "",
)
