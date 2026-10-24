package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LiveBreaks
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.break_watching
import dev.jamesrich.sofaking.resources.no_channels
import dev.jamesrich.sofaking.resources.now_playing
import dev.jamesrich.sofaking.resources.now_playing_channel
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.KeepScreenOn
import dev.jamesrich.sofaking.ui.LastInput
import dev.jamesrich.sofaking.ui.LocalInPictureInPicture
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalWindowFullScreen
import dev.jamesrich.sofaking.ui.WindowFullScreen
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.isLongPress
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.repeatCountOrNull
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** A swipe up this far on a touch screen opens the channel list. */
private const val SWIPE_UP_DP = 80

/** The server's answer when a session is asked for a channel id its lineup no longer has. */
internal const val CHANNEL_NOT_FOUND = Wire.ErrorCode.CHANNEL_NOT_FOUND

/**
 * How long a zap rests before the new channel is tuned, so pressing through channels does not open a session per press.
 */
internal const val ZAP_SETTLE_MS = 400L

/**
 * A zap from a tuned channel waits [ZAP_SETTLE_MS]; the first tune, which may take over the preview's stream, does not.
 */
internal fun zapSettleMs(tuned: Channel?, next: Channel): Long =
    if (tuned == null || tuned.guideId == next.guideId) 0L else ZAP_SETTLE_MS

/** The full-screen player's test tag; Android exposes it as a resource id for the Baseline Profile generator. */
const val PLAYER_TEST_TAG = "player"

@Composable
fun PlayerScreen(
    lineup: Lineup,
    startIndex: Int,
    player: LivePlayer,
    sessions: PlaybackSessions,
    onExit: () -> Unit,
    onChannelChange: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    /** The channel watched before this player opened, for Last; it may have been left through the guide. */
    previousGuideId: String? = null,
    /** The guide filter the channel was picked from; null cycles the whole lineup. */
    zapScope: ZapScope? = null,
    /** Opened by the guide with no key pressed: the screen may sleep until the first key or tap. */
    unattended: Boolean = false,
    onGuide: () -> Unit = onExit,
    onSearch: () -> Unit = onExit,
    reloadLineup: suspend () -> Lineup? = { null },
    onChannelMissing: (Channel) -> Unit = { onExit() },
    captionsEnabled: Boolean = false,
    onCaptionsChange: (Boolean) -> Unit = {},
    captionAdvanceFor: (Channel) -> Int = { AppSettings.DEFAULT_CAPTION_ADVANCE_MS },
    onCaptionAdvanceChange: (Channel, Int) -> Unit = { _, _ -> },
    captionStyle: CaptionStyle = CaptionStyle.SYSTEM,
    smoothCaptions: Boolean = true,
    /** Starred channels, for the channel list's Favorites chip, in the order Settings gave them. */
    favorites: Set<String> = emptySet(),
    favoritesOrder: List<String> = emptyList(),
    /** Channels watched lately, latest first, for the channel list's Recent chip; empty leaves the chip out. */
    recentGuideIds: List<String> = emptyList(),
    /** Stars or unstars a channel from the channel list; null leaves the star out. */
    onToggleFavorite: ((Channel) -> Unit)? = null,
    /** How many info bars have carried the key legend so far; it rides the first [AppSettings.KEY_LEGEND_BARS]. */
    keyLegendShown: Int = AppSettings.KEY_LEGEND_BARS,
    onKeyLegendShow: (Int) -> Unit = {},
    /** The server's scheduled recordings, for the bar's Record; null where it does not record. */
    recordingMarks: RecordingMarks? = null,
    /** Records the program, or cancels its recording; false when the server did not take it. */
    onRecord: suspend (Program) -> Boolean = { false },
    /** How the picture meets the screen, and the next one picked from the bar or the Zoom key. */
    zoom: VideoZoom = VideoZoom.FIT,
    onZoom: (VideoZoom) -> Unit = {},
    /** Whether the break screen covers the ads a streaming channel marks, and whether their sound plays under it. */
    liveBreaks: LiveBreaks = LiveBreaks.PLAY,
    /** The ads the break screen covered today before now, and where each break screen's time goes when it closes. */
    adsDodgedTodayMs: Long = 0,
    onAdsDodge: (Long) -> Unit = {},
    /** The sleep timer, which leaves the player as Back does once it runs out. */
    sleep: SleepTimer = remember { SleepTimer() },
) {
    val state = rememberPlayerState(
        lineup.channels,
        startIndex,
        previousGuideId,
        zapScope,
        captionsEnabled,
        attended = !unattended,
        keyLegendShown,
    )
    val ring = remember(lineup.channels, zapScope, state.widened) { zapRing(lineup.channels, zapScope, state.widened) }
    // As rememberUpdatedState does: the key handlers and effects read the ring and the lineup last composed.
    state.channels = ring
    state.lineupChannels = lineup.channels
    val now by rememberMinuteNow()
    val parts = rememberLiveParts(state, player, sessions, liveBreaks.covers)
    val load: (Channel) -> Unit = { channel -> parts.tuner.load(channel, nowPlayingFor(lineup, channel, now)) }
    SleepExit(sleep, onExit)
    // A failure inside a break keeps the screen up under its card: a browser can fail at the seam between two ads, and
    // the card should not uncover the ad while the stream reconnects.
    val breakScreen = parts.cover.up && !LocalInPictureInPicture.current
    val channelAdvance = state.captionAdvance(captionAdvanceFor)
    val captionLead = if (state.captionsOn && player.supportsCaptionLead) channelAdvance * 1_000L else 0L
    CaptionLeadEffect(player, captionLead, smoothCaptions)
    LiveSessionEffects(player, state, parts.tuner, muted = breakScreen && liveBreaks.muted)
    TuneEffects(state, parts.tuner, ring, lineup, now, onChannelChange)
    PlayerTimers(state, player)
    ReconnectEffect(state, reloadLineup, onChannelMissing, load)
    KeyLegendEffect(state, LocalLastInput.current.kind == InputKind.POINTER, onKeyLegendShow)
    val controls = liveControls(
        parts,
        channelAdvance,
        LiveExits(onGuide, onSearch, onExit, onCaptionsChange, onCaptionAdvanceChange),
        load,
        onRecord = {
            val program = state.currentChannel?.let { lineup.nowAndNext(it, now).first }
            parts.notice.recordFor(program, recordingMarks, onRecord)?.onClick?.invoke()
        },
        onZoom = {
            val next = zoom.next()
            onZoom(next)
            parts.notice.zoomed(next)
        },
    )
    LiveSurface(parts, controls, lineup, now, breakScreen, zoom, captionStyle, onExit, modifier) {
        if (breakScreen) LiveBreakScreen(state, lineup, now, parts.cover, adsDodgedTodayMs, onAdsDodge)
        LiveOverlays(
            parts = parts,
            controls = controls,
            lineup = lineup,
            now = now,
            scope = zapScope?.takeIf { ring.size < lineup.channels.size },
            ringSize = ring.size,
            sessions = sessions,
            captionAdvanceMs = channelAdvance.takeIf { player.supportsCaptionLead },
            choices = ChannelChoices(favorites, favoritesOrder, recentGuideIds, onToggleFavorite),
            record = { program -> parts.notice.recordFor(program, recordingMarks, onRecord) },
            sleep = sleep,
            zoom = zoom,
            onExit = onExit,
        )
    }
}

/** What the channel list offers: the starred channels in [order], the [recent] ones, and the star's toggle, if any. */
internal data class ChannelChoices(
    val favorites: Set<String>,
    val order: List<String>,
    val recent: List<String>,
    val onToggleFavorite: ((Channel) -> Unit)?,
)

/**
 * The player surface, with the picture and its captions under [content]: it holds focus, names the channel and its
 * program to a screen reader, and takes the keys, taps and swipes. [breakScreen] is whether the break screen is up,
 * which OK or Back put away. Picture-in-picture shows the picture and its captions, which scale with the window, and
 * nothing else.
 */
@Composable
private fun LiveSurface(
    parts: LiveParts,
    controls: PlayerControls,
    lineup: Lineup,
    now: Instant,
    breakScreen: Boolean,
    zoom: VideoZoom,
    captionStyle: CaptionStyle,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = parts.state
    val player = parts.player
    val breakScreenUp by rememberUpdatedState(breakScreen)
    val breakTakes: (Key) -> Boolean = { key -> breakScreenUp && breakScreenTakes(key, state) }
    BackHandler {
        when {
            breakTakes(Key.Back) -> parts.cover.dismissed = true
            state.back(controls) -> onExit()
        }
    }
    val audioTracks by player.audioTracks.collectAsStateWithLifecycle()
    val status by player.status.collectAsStateWithLifecycle()
    Box(
        modifier
            .fillMaxSize()
            .testTag(PLAYER_TEST_TAG)
            .background(SofaScrim)
            .focusRequester(parts.focus.picture)
            .focusable()
            .namesChannel(
                state,
                lineup,
                now,
                stringResource(Res.string.now_playing),
                stringResource(Res.string.now_playing_channel),
            )
            .liveKeys(parts, controls, breakTakes, hasAudioChoice = audioTracks.isNotEmpty())
            .liveTouches(parts, breakTakes, LocalWindowFullScreen.current, LocalLastInput.current),
    ) {
        player.Video(Modifier.fillMaxSize(), zoom) { Box(Modifier.fillMaxSize().background(SofaScrim)) }
        val windowHeightDp =
            with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value.toInt() }
        Captions(player, captionStyle, CaptionPolicy.bottomPaddingFraction(windowHeightDp), Modifier.fillMaxSize())
        KeepScreenOn(status.isPlaying && state.attended)
        if (!LocalInPictureInPicture.current) content()
    }
}

/**
 * The channel on screen and its program, as a screen reader names the player: [template] fills in both, and
 * [channelTemplate] the channel alone when nothing is listed.
 */
private fun Modifier.namesChannel(
    state: PlayerState,
    lineup: Lineup,
    now: Instant,
    template: String,
    channelTemplate: String,
): Modifier = semantics {
    state.currentChannel?.let { channel ->
        val title = lineup.nowAndNext(channel, now).first?.title
        contentDescription = if (title == null) {
            formatTemplate(channelTemplate, channel.label())
        } else {
            formatTemplate(template, channel.label(), title)
        }
    }
}

/**
 * The remote and the keyboard: a key the break screen takes ([breakTakes]) puts it away on its key-up; every other
 * goes to the player's state.
 */
private fun Modifier.liveKeys(
    parts: LiveParts,
    controls: PlayerControls,
    breakTakes: (Key) -> Boolean,
    hasAudioChoice: Boolean,
): Modifier = onPreviewKeyEvent { event ->
    val state = parts.state
    state.attended = true
    if (breakTakes(event.key)) {
        if (event.type == KeyEventType.KeyUp) parts.cover.dismissed = true
        return@onPreviewKeyEvent true
    }
    state.onKeyEvent(event.key, event.type, controls, event.isLongPress, event.repeatCountOrNull, hasAudioChoice)
}

/**
 * A tap or click on the video: the break screen goes away, the channel list closes, or the info bar and its touch
 * controls toggle. Focus stays on the player surface, so the remote and keyboard keep working after a pointer has
 * been used. A double click switches a window the app can make [fullScreen]. A swipe up on a touch screen opens the
 * channel list, the way a second OK does on the remote.
 */
private fun Modifier.liveTouches(
    parts: LiveParts,
    breakTakes: (Key) -> Boolean,
    fullScreen: WindowFullScreen?,
    lastInput: LastInput,
): Modifier {
    val state = parts.state
    return pointerInput(fullScreen) {
        detectTapGestures(
            onDoubleTap = fullScreen?.let { { _ -> it.toggle() } },
            onTap = {
                state.attended = true
                if (breakTakes(Key.Back)) parts.cover.dismissed = true else state.tap()
                parts.focus.picture.requestFocus()
            },
        )
    }
        .showBarOnMouseMove(lastInput, state::showInfo)
        .pointerInput(Unit) {
            var dragged = 0f
            detectVerticalDragGestures(
                onDragStart = { dragged = 0f },
                onDragEnd = { if (dragged < -SWIPE_UP_DP.dp.toPx()) state.openMiniGuide() },
                onVerticalDrag = { _, dy -> dragged += dy },
            )
        }
}

/** The break screen over the picture, with the program on now. */
@Composable
private fun LiveBreakScreen(
    state: PlayerState,
    lineup: Lineup,
    now: Instant,
    cover: BreakCover,
    dodgedBeforeMs: Long,
    onCover: (Long) -> Unit,
) {
    val program = state.currentChannel?.let { lineup.nowAndNext(it, now).first }
    BreakScreen(
        Modifier.fillMaxSize(),
        remainingMs = { cover.leftMs },
        watching = program?.let { stringResource(Res.string.break_watching, it.title) },
        dodgedBeforeMs = dodgedBeforeMs,
        onCover = onCover,
    )
}

/**
 * What shows over the picture: the status pills, the failure card, the typed number, the stream card, the channel
 * list and the info bar; or a note when the lineup has no channels. [record] gives Record for a program.
 */
@Composable
private fun BoxScope.LiveOverlays(
    parts: LiveParts,
    controls: PlayerControls,
    lineup: Lineup,
    now: Instant,
    scope: ZapScope?,
    ringSize: Int,
    sessions: PlaybackSessions,
    captionAdvanceMs: Int?,
    choices: ChannelChoices,
    record: (Program?) -> LiveRecord?,
    sleep: SleepTimer,
    zoom: VideoZoom,
    onExit: () -> Unit,
) {
    val state = parts.state
    val channel = state.currentChannel
    if (channel == null) {
        Text(
            stringResource(Res.string.no_channels),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.Center),
        )
        return
    }
    val status by parts.player.status.collectAsStateWithLifecycle()
    val audioTracks by parts.player.audioTracks.collectAsStateWithLifecycle()
    val pointerDriven = LocalLastInput.current.kind == InputKind.POINTER
    val paused = !status.playWhenReady
    val program = lineup.nowAndNext(channel, now).first
    StatusPills(
        state,
        buffering = status.state == PlaybackState.BUFFERING,
        paused = paused,
        notice = parts.notice.text,
        onBack = onExit.takeIf {
            pointerDriven && state.overlayVisible && !state.miniGuideOpen && !state.streamCardOpen
        },
    )
    state.failure?.let { FailureCard(it.code) { state.retryIn } }
    ChannelNumberBadge(state.numbers)
    StreamCardPanel(state, parts.player, sessions, audioTracks)
    ChannelListPanel(state, lineup, channel, scope, choices.favorites, choices.order, choices.recent, now)
    InfoBarPanel(
        state = state,
        lineup = lineup,
        channel = channel,
        now = now,
        scope = scope,
        ringSize = ringSize,
        captionAdvanceMs = captionAdvanceMs,
        pointerDriven = pointerDriven,
        paused = paused,
        hasAudioChoice = audioTracks.isNotEmpty(),
        record = record(program),
        sleep = sleep,
        programEnd = program?.stop,
        zoom = zoom,
        favorite = if (choices.onToggleFavorite != null) channel.guideId in choices.favorites else null,
        onToggleFavorite = { choices.onToggleFavorite?.invoke(channel) },
        controls = controls,
        onGuide = controls::guide,
        focus = parts.focus.picture,
        entry = parts.focus.buttons,
    )
}
