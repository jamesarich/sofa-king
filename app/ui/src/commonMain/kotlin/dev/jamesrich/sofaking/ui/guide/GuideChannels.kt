package dev.jamesrich.sofaking.ui.guide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.ProgramKind
import dev.jamesrich.sofaking.data.orderedFavorites
import dev.jamesrich.sofaking.ui.player.ZapScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** How often the kinds of program on now are read again. */
private const val ON_NOW_REFRESH_SECONDS = 300L

/**
 * The window the grid shows, from [start] to [end]: the live one that starts at [liveStart], or one a whole number of
 * days after it. [nowMinute] is the current time's minute in it, null when now is outside it; [nowClamped] is the
 * same minute held to the window.
 */
@Immutable
internal data class GuideSpan(val liveStart: Instant, val start: Instant, val end: Instant, val now: Instant) {
    val nowMinute: Long? = GuideTimeline.offsetMinutes(start, now).takeIf { now >= start && now < end }
    val nowClamped: Long = GuideTimeline.offsetMinutes(start, now)
}

@Composable
internal fun rememberGuideSpan(state: GuideState, now: Instant): GuideSpan =
    remember(state.liveStartSecond, state.dayOffset, now) {
        val liveStart = Instant.fromEpochSeconds(state.liveStartSecond)
        val start = liveStart + (state.dayOffset * GuideTimeline.WINDOW_MINUTES).minutes
        GuideSpan(liveStart, start, GuideTimeline.windowEnd(start), now)
    }

/**
 * The channels the grid lists and the chips over it: the [filters] with their [counts], the [active] one, and the
 * [visible] channels it and search leave as list [items], with one requester per Later line in [laterFocus] so a row
 * step can land on its first chip. A channel opened from a filter keeps the player's Up and Down inside it
 * ([zapScope]); search does not narrow it. [onNowSlot] counts the refreshes of what is on now.
 */
@Immutable
internal data class GuideChannels(
    val filters: List<GuideFilter>,
    val counts: Map<GuideFilter, Int>,
    val active: GuideFilter,
    val zapScope: ZapScope?,
    val query: String,
    val visible: List<Channel>,
    val items: List<GuideListItem>,
    val laterFocus: Map<String, FocusRequester>,
    val onNowSlot: Long,
)

@Composable
internal fun rememberGuideChannels(
    lineup: Lineup,
    favorites: Set<String>,
    favoritesOrder: List<String>,
    recentChannels: List<String>,
    state: GuideState,
    span: GuideSpan,
): GuideChannels {
    val now = span.now
    // What is on now is read every few minutes, not every minute, so the chips and their lists hold still while
    // browsing.
    val onNowSlot = now.epochSeconds / ON_NOW_REFRESH_SECONDS
    val kindsOnNow = remember(lineup.channels, lineup.guide, onNowSlot) { channelsOnNow(lineup, now) }
    val filters = remember(lineup.channels, kindsOnNow.keys) { guideFilters(lineup.channels, kindsOnNow.keys) }
    val counts = remember(lineup.channels, favorites, recentChannels, kindsOnNow) {
        guideFilterCounts(lineup.channels, favorites, recentChannels, kindsOnNow)
    }
    val active = state.filter.takeIf { it in filters } ?: GuideFilter.All
    val query = if (state.searchOpen) state.searchText.text.toString() else ""
    val searchNow = now.takeIf { query.isNotBlank() }
    val byFilter = remember(lineup.channels, favorites, favoritesOrder, recentChannels, active, kindsOnNow) {
        filtered(active, lineup.channels, favorites, favoritesOrder, recentChannels, kindsOnNow)
    }
    val activeLabel = active.label()
    val zapScope = remember(active, activeLabel, byFilter) {
        if (active == GuideFilter.All) null else ZapScope(activeLabel, byFilter.mapTo(HashSet()) { it.guideId })
    }
    // Search also matches programs later in the window; those channels get a "Later" line under their row.
    val index = if (searchNow == null) null else rememberGuideSearchIndex(lineup)
    val needle = remember(query) { GuideSearchIndex.needle(query) }
    val upcoming = remember(byFilter, index, needle, searchNow, span.end) {
        if (searchNow == null ||
            index == null
        ) {
            emptyMap()
        } else {
            upcomingMatches(byFilter, index, needle, searchNow, span)
        }
    }
    val visible = remember(byFilter, index, needle, searchNow, upcoming) {
        if (searchNow == null ||
            index == null
        ) {
            byFilter
        } else {
            searchMatches(byFilter, index, needle, searchNow, upcoming)
        }
    }
    val items = remember(visible, upcoming) { guideListItems(visible, upcoming) }
    val laterFocus = remember(items) {
        items.filterIsInstance<GuideListItem.Later>().associate { it.key to FocusRequester() }
    }
    return GuideChannels(filters, counts, active, zapScope, query, visible, items, laterFocus, onNowSlot)
}

/**
 * The search text of [lineup], normalized once off the main thread while search is open and the channels and guide
 * hold still; null until it is built, and again whenever they change.
 */
@Composable
private fun rememberGuideSearchIndex(lineup: Lineup): GuideSearchIndex? =
    produceState<GuideSearchIndex?>(null, lineup.channels, lineup.guide) {
        value = null
        value = withContext(Dispatchers.Default) { GuideSearchIndex(lineup.channels, lineup.guide) }
    }.value

/** The channels [filter] lists; Favorites in the order Settings gave them, the rest by number. */
private fun filtered(
    filter: GuideFilter,
    channels: List<Channel>,
    favorites: Set<String>,
    favoritesOrder: List<String>,
    recent: List<String>,
    kindsOnNow: Map<ProgramKind, List<Channel>>,
): List<Channel> = when (filter) {
    GuideFilter.All -> channels
    GuideFilter.Favorites -> orderedFavorites(channels, favorites, favoritesOrder)
    GuideFilter.Recent -> recentChannels(channels, recent)
    is GuideFilter.Group -> channels.filter { filter.name in it.groups }
    is GuideFilter.OnNow -> kindsOnNow[filter.kind].orEmpty()
}

/**
 * The programs matching [query] later in [span], by channel. Only within the window on screen: a match from earlier
 * would jump to the window's start, not to it.
 */
private fun upcomingMatches(
    channels: List<Channel>,
    index: GuideSearchIndex,
    needle: String,
    searchNow: Instant,
    span: GuideSpan,
): Map<String, List<Program>> = buildMap {
    val after = maxOf(searchNow, span.start - 1.milliseconds)
    for (channel in channels) {
        val later = index.upcoming(needle, channel, after, span.end)
        if (later.isNotEmpty()) put(channel.guideId, later)
    }
}

/** The channels whose number, name or program on now matches [needle], or that have a match later on. */
private fun searchMatches(
    channels: List<Channel>,
    index: GuideSearchIndex,
    needle: String,
    searchNow: Instant,
    upcoming: Map<String, List<Program>>,
): List<Channel> = channels.filter { channel ->
    channel.guideId in upcoming || index.matches(needle, channel, searchNow)
}
