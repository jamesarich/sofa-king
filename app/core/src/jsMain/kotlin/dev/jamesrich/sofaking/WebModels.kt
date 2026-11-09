package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.MediaShelf
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.ServerCapabilities
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

// The flat values TypeScript reads: strings, booleans, `Int`s and `Double`s (milliseconds since the epoch, since
// `Long` does not cross), arrays for lists, and `null` for absent.

@JsExport
class WebProgram(
    val title: String,
    val subTitle: String?,
    val episode: String?,
    val description: String?,
    val startMs: Double,
    val stopMs: Double,
    val isNew: Boolean,
    val rating: String?,
    val icon: String?,
)

/** A channel with the programs from the one on now to six hours ahead, in order. */
@JsExport
class WebChannel(
    val id: String,
    val number: Double,
    val name: String,
    val logo: String?,
    val groups: Array<String>,
    val programs: Array<WebProgram>,
)

/**
 * [state] is `loading`, `unconfigured`, `failed` or `ready`; [message] says why for `failed`, and [signedOut] is true
 * when the server turned this device's token away.
 */
@JsExport
class WebLineup(
    val state: String,
    val message: String,
    val channels: Array<WebChannel>,
    val guideLoading: Boolean,
    val signedOut: Boolean = false,
)

@JsExport
class WebSettingsState(
    val serverUrl: String,
    val tokenSet: Boolean,
    val captionsEnabled: Boolean,
    /** `PLAY`, `SCREEN_MUTED` or `SCREEN`: what the live player does in an ad break. */
    val liveBreaks: String,
    val favorites: Array<String>,
    val recentChannels: Array<String>,
    val guideFilter: String?,
    val lastChannel: String?,
)

@JsExport
class WebCapabilities(val answered: Boolean, val library: Boolean)

@JsExport
class WebStream(
    val channelId: String,
    val sessionId: String,
    val url: String,
    val mimeType: String,
    val epoch: Int,
    val seekable: Boolean,
    val itemId: String?,
    /** `direct` for a file played as it is, else the server's HLS. */
    val mode: String,
    val offsetMs: Double,
    val startAtMs: Double,
    val itemDurationMs: Double?,
)

@JsExport
class WebItem(
    val id: String,
    val libraryId: String,
    val kind: String,
    val title: String,
    val showTitle: String?,
    val episodeLabel: String?,
    val year: Int?,
    val overview: String?,
    val durationMs: Double?,
    val poster: String?,
    val backdrop: String?,
    val playable: Boolean,
    val children: Int,
    val unwatched: Int,
    val positionMs: Double,
    val watched: Boolean,
    /** How far in, 0 to 1, or null when not started or the length is unknown. */
    val progress: Double?,
)

@JsExport
class WebShelf(val key: String, val title: String, val items: Array<WebItem>)

@JsExport
class WebLibraryInfo(val id: String, val name: String, val kind: String, val itemCount: Int)

@JsExport
class WebHome(
    val state: String,
    val message: String,
    val shelves: Array<WebShelf>,
    val libraries: Array<WebLibraryInfo>,
    /** True when [state] is `failed` because the server turned this device's token away. */
    val signedOut: Boolean = false,
)

@JsExport
class WebGrid(
    val libraryId: String,
    val items: Array<WebItem>,
    val genres: Array<String>,
    val total: Int,
    val hasMore: Boolean,
    val loading: Boolean,
    val error: String?,
)

@JsExport
class WebItemDetail(
    val item: WebItem,
    val children: Array<WebItem>,
    val next: WebItem?,
    val tagline: String?,
    val studio: String?,
    val cast: Array<String>,
    val directors: Array<String>,
    val genres: Array<String>,
    val related: Array<WebItem>,
)

@JsExport
class WebItemState(val state: String, val message: String, val detail: WebItemDetail?)

internal fun Program.toWeb() = WebProgram(
    title,
    subTitle,
    episode,
    description,
    start.toEpochMilliseconds().toDouble(),
    stop.toEpochMilliseconds().toDouble(),
    isNew,
    rating,
    icon,
)

internal fun lineupToWeb(state: LineupState): WebLineup = when (state) {
    LineupState.Loading -> WebLineup("loading", "", emptyArray(), false)

    LineupState.Unconfigured -> WebLineup("unconfigured", "", emptyArray(), false)

    is LineupState.Failed -> WebLineup("failed", state.message, emptyArray(), false, state.signedOut)

    is LineupState.Ready -> {
        val lineup = state.visible
        val now = Clock.System.now()
        val horizon = now + PROGRAM_HORIZON
        WebLineup(
            "ready",
            "",
            lineup.channels.map { channel ->
                val programs = lineup.guide[channel.guideId].orEmpty().filter { it.stop > now && it.start < horizon }
                channel.toWeb(programs)
            }.toTypedArray(),
            lineup.guideLoading,
        )
    }
}

private val PROGRAM_HORIZON = 6.hours

internal fun Channel.toWeb(programs: List<Program>) = WebChannel(
    guideId,
    number,
    name,
    logo,
    groups.toTypedArray(),
    programs.map { it.toWeb() }.toTypedArray(),
)

internal fun AppSettings.toWeb() = WebSettingsState(
    serverUrl,
    apiToken.isNotEmpty(),
    captionsEnabled,
    liveBreaks.name,
    favorites.toTypedArray(),
    recentChannels.toTypedArray(),
    guideFilter,
    lastChannel,
)

internal fun ServerCapabilities.toWeb() = WebCapabilities(answered, library)

internal fun PlaybackStream.toWeb() = WebStream(
    channelId,
    sessionId,
    url,
    mimeType,
    epoch,
    seekable,
    itemId,
    mode.ifEmpty { "live" },
    offsetMs.toDouble(),
    startAtMs.toDouble(),
    itemDurationMs?.toDouble(),
)

internal fun WebParts.toWeb(item: MediaItem): WebItem {
    remember(item)
    val progress = item.progress
    return WebItem(
        item.id,
        item.libraryId,
        item.kind.name.lowercase(),
        item.title,
        item.showTitle,
        item.episodeLabel,
        item.year,
        item.overview,
        item.durationMs?.toDouble(),
        item.poster,
        item.backdrop,
        item.kind.playable,
        item.children,
        item.unwatched,
        progress?.positionMs?.toDouble() ?: 0.0,
        progress?.watched == true,
        progress?.takeIf { it.inProgress && !it.watched }?.fraction?.toDouble(),
    )
}

internal fun WebParts.toWeb(shelf: MediaShelf) = WebShelf(shelf.key, shelf.title, shelf.items.toWeb(this))

internal fun List<MediaItem>.toWeb(parts: WebParts): Array<WebItem> = map { parts.toWeb(it) }.toTypedArray()

internal fun WebParts.toWeb(detail: MediaItemDetail) = WebItemDetail(
    toWeb(detail.item),
    detail.children.toWeb(this),
    detail.next?.let { toWeb(it) },
    detail.tagline,
    detail.studio,
    detail.cast.toTypedArray(),
    detail.directors.toTypedArray(),
    detail.item.genres.toTypedArray(),
    detail.related.toWeb(this),
)
