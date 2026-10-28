package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.store.savePlayStates
import dev.jamesrich.sofaking.contract.LibraryItem
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.withLock

/**
 * Likes or unlikes a song, album or artist ([favorite]); the item as clients read it. A Music item is found only by a
 * client that shows [music].
 */
suspend fun LibraryCatalog.setFavorite(id: String, favorite: Boolean, music: Boolean = false): LibraryItem =
    tasting(id, music, songsOnly = false) { old, now ->
        (old ?: PlayStateRecord(id, 0, null, false, now)).copy(favorite = favorite)
    }

/** Counts one play of the song [id], now; the song as clients read it. */
suspend fun LibraryCatalog.recordPlay(id: String, music: Boolean = false): LibraryItem =
    tasting(id, music, songsOnly = true) { old, now ->
        val state = old ?: PlayStateRecord(id, 0, null, false, now)
        state.copy(plays = state.plays + 1, lastPlayedAt = now)
    }

/** Changes the household's taste in [id], a song, or unless [songsOnly] an album or artist, with [change]. */
private suspend fun LibraryCatalog.tasting(
    id: String,
    music: Boolean,
    songsOnly: Boolean,
    change: (PlayStateRecord?, String) -> PlayStateRecord,
): LibraryItem = writing.withLock {
    val snapshot = snapshot()
    val view = snapshot.views[id]?.takeIf {
        snapshot.shows(it, music) && (it.record.kind == ItemKind.TRACK || (!songsOnly && it.record.kind in GROUPS))
    } ?: throw ApiFailure(HttpStatusCode.NotFound, "ITEM_NOT_FOUND", "No such song, album or artist")
    val state = change(playStates()[id], clock.instant().toString())
    // Counted even when a write fails partway, so the next read is never served the copy from before it.
    try {
        if (state.blank && !state.dismissed) {
            store.savePlayStates(emptyList(), listOf(id))
        } else {
            store.savePlayStates(listOf(state))
        }
    } finally {
        stateWrites.incrementAndGet()
    }
    view.out(snapshot, playStates())
}

private val GROUPS = setOf(ItemKind.ALBUM, ItemKind.ARTIST)
