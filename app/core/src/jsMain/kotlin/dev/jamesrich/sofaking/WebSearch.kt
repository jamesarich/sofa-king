package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.LiveHit
import dev.jamesrich.sofaking.data.LiveSearchResults
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.SearchSections
import dev.jamesrich.sofaking.data.search
import dev.jamesrich.sofaking.data.searchSections
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.time.Clock

/** A channel a search found, with the [program] it matched by or is showing; null when its guide lists none. */
@JsExport
class WebLiveHit(val channel: WebChannel, val program: WebProgram?)

/**
 * A row of library items a search found. [group] is `movies`, `shows`, `cast_and_crew` (titles found by the people
 * in them), `episodes`, `videos`, `artists`, `albums` or `tracks`.
 */
@JsExport
class WebSearchRow(val group: String, val items: Array<WebItem>)

/**
 * What [query] found: channels on [now], the library's [rows] in the order to show them, and programs on [later],
 * soonest first. [people] are the cast and directors the `cast_and_crew` row's titles were found by, when the server
 * names them.
 */
@JsExport
class WebSearchResults(
    val query: String,
    val now: Array<WebLiveHit>,
    val rows: Array<WebSearchRow>,
    val later: Array<WebLiveHit>,
    val people: Array<String>,
)

/** One search over the lineup and the libraries, as the Compose apps' search page runs it. */
@JsExport
class WebSearch internal constructor(private val parts: WebParts) {
    /**
     * Channels and programs in the lineup the guide shows, and the libraries' titles and cast, that match [query];
     * nothing for a blank query, and no library rows when the server cannot be reached.
     */
    fun find(query: String): Promise<WebSearchResults> = parts.scope.promise {
        val lineup = (parts.viewModel.state.value as? LineupState.Ready)?.visible
        val live = lineup?.search(query, Clock.System.now()) ?: LiveSearchResults()
        val found = parts.library.search(query)
        searchToWeb(live, searchSections(found.items, query, found.people)) { parts.toWeb(it) }
    }
}

internal fun searchToWeb(live: LiveSearchResults, sections: SearchSections, item: (MediaItem) -> WebItem) =
    WebSearchResults(
        sections.query,
        live.now.map { it.toWeb() }.toTypedArray(),
        sections.rows.map { WebSearchRow(it.group.name.lowercase(), it.items.map(item).toTypedArray()) }.toTypedArray(),
        live.later.map { it.toWeb() }.toTypedArray(),
        sections.people.toTypedArray(),
    )

private fun LiveHit.toWeb() = WebLiveHit(channel.toWeb(emptyList()), program?.toWeb())
