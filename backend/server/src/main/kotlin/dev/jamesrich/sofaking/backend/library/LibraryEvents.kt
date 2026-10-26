package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.events.EventType
import dev.jamesrich.sofaking.backend.events.ServerEvents
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The most titles a `library.added` event names; the count says how many more there were. */
private const val NAMED = 10

/**
 * Publishes `library.added` for one scan of [library]: [saved] is what the scan wrote and [before] the item ids the
 * library held, so the new items are the saved ones it did not. Extras are left out, and nothing is published when
 * the scan added no file.
 */
internal fun ServerEvents?.libraryAdded(
    library: LibraryRecord,
    saved: List<LibraryItemRecord>,
    before: Set<String>,
    all: List<LibraryItemRecord>,
) {
    val events = this ?: return
    val added = saved.filter { it.id !in before && it.path != null && it.kind != ItemKind.EXTRA }
    if (added.isEmpty()) return
    val byId = all.associateBy { it.id }
    val names = added.map { item -> name(item, byId) }.distinct()
    events.publish(
        EventType.LIBRARY_ADDED,
        buildJsonObject {
            put("libraryId", library.id)
            put("library", library.name)
            put("added", added.size)
            putJsonArray("titles") { names.take(NAMED).forEach { add(it) } }
        },
    )
}

/**
 * An episode as its show and number, `Show S01E02`; a track as its album and artist, `Album – Artist`; anything else
 * by its own title and year.
 */
private fun name(item: LibraryItemRecord, byId: Map<String, LibraryItemRecord>): String {
    if (item.kind == ItemKind.TRACK) {
        val album = item.parentId?.let(byId::get) ?: return item.title
        return listOfNotNull(album.title, album.parentId?.let(byId::get)?.title).joinToString(" – ")
    }
    if (item.kind != ItemKind.EPISODE) return item.year?.let { "${item.title} ($it)" } ?: item.title
    val season = item.parentId?.let(byId::get)
    val show = (season?.parentId?.let(byId::get) ?: season)?.takeIf { it.kind == ItemKind.SHOW }
    val number = if (item.season != null && item.episode != null) {
        "S%02dE%02d".format(item.season, item.episode)
    } else {
        null
    }
    return listOfNotNull(show?.title, number).joinToString(" ").ifEmpty { item.title }
}
