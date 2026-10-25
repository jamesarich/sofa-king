package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.Serializable

/**
 * Where a virtual channel's slot takes its items: [kind] `library` (every movie, episode and video of the library
 * [value] names by ID), `show` (the episodes of the show [value] names by item ID) or `genre` (the movies and episodes
 * of every library whose genres hold [value], ignoring case).
 */
@Serializable data class VirtualSource(val kind: String, val value: String)

/**
 * One slot of a virtual channel: its [source], played in order or with [shuffle], from [start] (`HH:MM` in the
 * server's time zone) until the next slot's start. A channel either times every slot or none; untimed slots take
 * turns, one item each.
 */
@Serializable data class VirtualSlot(val source: VirtualSource, val shuffle: Boolean = false, val start: String? = null)

/**
 * A channel the server plays from the libraries on a schedule. [filler] fills the gap before a timed slot starts and
 * before each [padMinutes] mark (0, 15, 30 or 60), with a blank picture for what it cannot fill. [id] is given by the
 * server.
 */
@Serializable data class VirtualChannel(
    val id: String = "",
    val name: String,
    val number: String? = null,
    val slots: List<VirtualSlot>,
    val filler: VirtualSource? = null,
    val padMinutes: Int = 0,
)

/** One airing of a virtual channel's schedule: an item, filler, or a blank picture when [itemId] is null. */
@Serializable data class VirtualAiring(
    val startsAt: String,
    val endsAt: String,
    val title: String,
    val itemId: String? = null,
    val filler: Boolean = false,
)
