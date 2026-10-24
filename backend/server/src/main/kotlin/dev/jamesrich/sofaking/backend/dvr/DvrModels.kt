package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.SeasonEpisode
import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.RecordingBreak
import dev.jamesrich.sofaking.contract.RecordingRule
import java.time.Duration
import java.time.Instant

/**
 * The server's own recording state beside the wire ones: a rule's airing someone canceled, kept so the
 * rule does not schedule it again.
 */
internal const val CANCELED = "canceled"

/**
 * One recording as stored; [startsAt] and [endsAt] are the program's, and the padding widens
 * the window the recorder runs in.
 */
data class StoredRecording(
    val id: String,
    val ruleId: String?,
    val channelId: String,
    val channelName: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val startPadding: Duration,
    val endPadding: Duration,
    val title: String,
    val episodeTitle: String? = null,
    val description: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val movie: Boolean = false,
    val year: Int? = null,
    val image: String? = null,
    val state: String,
    val reason: String? = null,
    /** Relative to the recordings folder: the final path once finished, the `.inprogress` one while recording. */
    val path: String? = null,
    val bytes: Long = 0,
    val protected: Boolean = false,
    val positionMs: Long = 0,
    val watched: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
    val commercials: String? = null,
    val breaks: List<RecordingBreak> = emptyList(),
) {
    val recordFrom: Instant get() = startsAt.minus(startPadding)
    val recordUntil: Instant get() = endsAt.plus(endPadding)

    val name: RecordingTitle get() = RecordingTitle(
        title,
        episodeTitle,
        if (season != null || episode != null) {
            SeasonEpisode(season, episode)
        } else {
            null
        },
        movie,
        year,
    )

    fun toWire(conflict: Boolean = false) = Recording(
        id, channelId, channelName, title, episodeTitle, description, season, episode, movie, year, image,
        startsAt.toString(), endsAt.toString(), startPadding.seconds.toInt(), endPadding.seconds.toInt(), state, reason,
        ruleId,
        path, bytes, protected, conflict, positionMs, watched, commercials, breaks,
    )
}

/** A series rule as stored. */
data class StoredRule(
    val id: String,
    val title: String,
    val channelId: String?,
    val newOnly: Boolean,
    val keep: Int,
    val startPadding: Duration,
    val endPadding: Duration,
    val priority: Int,
    val createdAt: Instant,
) {
    fun toWire() = RecordingRule(
        id, title, channelId, newOnly, keep, startPadding.seconds.toInt(), endPadding.seconds.toInt(), priority,
        createdAt.toString(),
    )
}

/** What the culler keeps: [freeGib] free on the disk, and recordings no older than [keepDays] (0 for no age limit). */
data class Retention(val freeGib: Int = 10, val keepDays: Int = 0)
