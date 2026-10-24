package dev.jamesrich.sofaking.backend.store

import org.jetbrains.exposed.v1.core.get
import java.time.Instant

/**
 * One logo the lineup carries, keyed by the hash of its source URL. [fetchedAt] set means the file `logos/<key>`
 * holds the last successful fetch as [contentType]; [failedAt] is the last attempt that failed, kept alongside a
 * stale file so a dead source keeps its old logo.
 */
data class LogoRecord(
    val key: String,
    val sourceUrl: String,
    val contentType: String?,
    val size: Long,
    val contentHash: String?,
    val fetchedAt: Instant?,
    val failedAt: Instant?,
    val failures: Int,
) {
    val cached: Boolean get() = fetchedAt != null && contentType != null && contentHash != null
}
