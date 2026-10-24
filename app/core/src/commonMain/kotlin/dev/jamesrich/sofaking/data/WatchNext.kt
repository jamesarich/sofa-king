package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.LibraryHomeState
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.encodeURLPathPart
import io.ktor.http.parseUrl

/**
 * The platform's row of what to watch next outside the app, such as the Android TV home screen's Watch Next row.
 * The app hands it the library's Continue watching shelf each time the home shelves load. [None] stands in where
 * the platform has no such row.
 */
fun interface WatchNext {
    /** [items] are the Continue watching shelf, most recent first; empty clears the row. */
    fun publish(items: List<MediaItem>)

    companion object {
        val None = WatchNext {}
    }
}

/**
 * What [home] puts in the Watch Next row: the Continue watching shelf once the shelves are in, nothing once there is
 * no server or no library, and null (the row stays as it is) while they load or fail to.
 */
fun watchNextItems(home: LibraryHomeState): List<MediaItem>? = when (home) {
    is LibraryHomeState.Ready -> home.home.shelves.firstOrNull {
        it.id == Wire.ShelfId.CONTINUE
    }?.items.orEmpty().filter { it.kind.playable }

    LibraryHomeState.Unconfigured, LibraryHomeState.Unavailable -> emptyList()

    LibraryHomeState.Loading, is LibraryHomeState.Failed -> null
}

/** The link a Watch Next entry opens: [itemId] in the player, from where the household stopped. */
fun itemLink(itemId: String): String = "sofaking://vod/" + itemId.encodeURLPathPart()

/** The library item a `sofaking://vod/ID` link plays; null for any other link. */
fun parseItemLink(link: String): String? {
    val url = parseUrl(link.trim()) ?: return null
    if (url.protocol.name != "sofaking" || url.host != "vod") return null
    return url.segments.singleOrNull()?.takeIf { it.isNotBlank() }
}
