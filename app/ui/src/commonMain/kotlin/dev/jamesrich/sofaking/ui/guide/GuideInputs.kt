package dev.jamesrich.sofaking.ui.guide

import androidx.compose.runtime.Stable
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.ZapScope
import kotlin.time.Instant

/**
 * Where a visit to the guide starts. [returnedFrom] is the channel the full screen was just left on: focus lands on
 * it, a filter hiding it gives way, and the preview's rest timer does not reopen it until focus has moved.
 * [initialChannelId] (the last channel watched) is where a fresh guide starts, [startAtNow] puts the cursor at the
 * current time instead of where it was, and [startSearch] opens search by voice on arrival. [startInHeader] leaves
 * focus on the header's tab, where Back from another tab put it: the guide takes its first focus, and the preview
 * its channel, only once focus comes down into it.
 */
data class GuideArrival(
    val initialChannelId: String? = null,
    /** The filter saved from the last visit (see [guideFilterFromKey]); a fresh guide opens on it. */
    val initialFilterKey: String? = null,
    val startAtNow: Boolean = false,
    val startSearch: Boolean = false,
    val returnedFrom: String? = null,
    val startInHeader: Boolean = false,
)

/** What the guide asks of the app: opening a channel, the viewer's choices to save, and loading and recording. */
data class GuideActions(
    /** Opens [channel] full screen; [unattended] when the preview opened it by itself, with no key pressed. */
    val onPlay: (channel: Channel, scope: ZapScope?, unattended: Boolean) -> Unit,
    val onToggleFavorite: (Channel) -> Unit,
    /** The viewer chose a filter chip; its key is saved for the next launch. */
    val onFilterChange: (String?) -> Unit,
    /** The grid moved to a day whose programs may not be loaded; the window starts at the given instant. */
    val onLoadGuideWindow: (Instant) -> Unit,
    /** Records the program or cancels its recording; false when the server did not take it. */
    val onRecord: suspend (Program) -> Boolean = notRecorded,
    /** Records the program's series or stops it; false when the server did not take it. */
    val onRecordSeries: suspend (Program) -> Boolean = notRecorded,
    /** Records every game of a team on any channel, or stops; false when the server did not take it. */
    val onRecordTeam: suspend (String) -> Boolean = { false },
    /** Plays a recording still being written from the start of its program. */
    val onWatchFromStart: (recordingId: String) -> Unit = {},
)

/** A server that does not record takes nothing. */
private val notRecorded: suspend (Program) -> Boolean = { false }

/** The preview band's player and how it plays: captions, when it opens full screen, and whether it has sound. */
@Stable
data class GuidePreview(
    val player: LivePlayer,
    val sessions: PlaybackSessions,
    val captionsEnabled: Boolean,
    val captionStyle: CaptionStyle,
    val autoOpenDelay: AutoOpenDelay,
    val sound: Boolean,
)
