package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LiveBreaks
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.data.visibleChannels
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.guide.GuideScreen
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.localDay
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.PlayerScreen
import dev.jamesrich.sofaking.ui.player.ZapScope
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * The lineup as this device shows it: hidden channels leave the guide and the channel list, and the player's zapping
 * when that switch is on; the full lineup stays for the manager, the picker and a channel opened by number.
 */
@Composable
internal fun rememberVisibleLineup(lineup: Lineup?, hidden: Set<String>): Lineup? =
    remember(lineup, hidden) { lineup?.let { l -> l.copy(channels = visibleChannels(l.channels, hidden)) } }

/** The guide, once the cold start has decided where to land, or what stands in for it while there is no lineup. */
@Composable
internal fun GuideDestination(
    arrival: Arrival,
    shell: AppShellState,
    vm: LineupViewModel,
    player: LivePlayer,
    settingsFocus: FocusRequester,
    headerFocus: FocusRequester?,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val recordingMarks by vm.recordings.marks.collectAsStateWithLifecycle()
    // With no server to talk to, Settings is the only thing to do, so it takes focus.
    val homeFocus = homeFocusFor(state)
    LaunchedEffect(homeFocus) {
        if (homeFocus == HomeFocus.SETTINGS) requestFocusWhenAttached(settingsFocus)
    }
    // Settings arrive a beat after the cached lineup; the guide waits for them so it can open on the last channel.
    val s = state
    val visibleLineup = rememberVisibleLineup((s as? LineupState.Ready)?.lineup, settings?.hiddenChannels.orEmpty())
    when {
        s is LineupState.Loading || settings == null || !shell.launchDecided -> MessageScreen(stringResource(Res.string.loading), loading = true)
        s is LineupState.Unconfigured -> MessageScreen(
            message = stringResource(Res.string.unconfigured),
            hint = stringResource(Res.string.unconfigured_hint),
        )
        s is LineupState.Failed -> MessageScreen(
            message = stringResource(Res.string.load_failed),
            detail = s.message,
            hint = stringResource(Res.string.load_failed_hint),
            onRetry = vm::refresh,
        )
        s is LineupState.Ready -> GuideOrEmpty(
            lineup = visibleLineup ?: s.lineup,
            favorites = settings?.favorites.orEmpty(),
            favoritesOrder = settings?.favoritesOrder.orEmpty(),
            recentChannels = settings?.recentChannels.orEmpty(),
            lastChannel = settings?.lastChannel,
            lastFilterKey = settings?.guideFilter,
            startAtNow = arrival.atNow,
            startSearch = arrival.search,
            returnedFrom = arrival.returnedFrom,
            onPlay = { guideId, scope, unattended -> shell.push(Route.Player(guideId, scope, unattended)) },
            onToggleFavorite = vm::toggleFavorite,
            onFilterChanged = vm::setGuideFilter,
            onLoadGuideWindow = vm::loadGuideWindow,
            previewPlayer = player,
            sessions = vm.sessions,
            captionsEnabled = settings?.captionsEnabled ?: false,
            captionStyle = settings?.captionStyle ?: CaptionStyle.SYSTEM,
            autoOpenDelay = settings?.autoOpenDelay ?: AutoOpenDelay.AFTER_10_SECONDS,
            previewSound = settings?.previewSound ?: true,
            recordingMarks = recordingMarks.takeIf { capabilities.recordings },
            onRecord = vm.recordings::toggle,
            onRecordSeries = vm.recordings::toggleSeries,
            headerFocus = headerFocus,
            startInHeader = arrival.inHeader && headerFocus != null,
            shown = shell.top == Route.Guide,
        )
    }
}

/** A channel full screen, zapping through the lineup; a lineup that is not in yet sends the viewer back. */
@Composable
internal fun PlayerDestination(route: Route.Player, shell: AppShellState, vm: LineupViewModel, player: LivePlayer) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val recordingMarks by vm.recordings.marks.collectAsStateWithLifecycle()
    val lineup = (state as? LineupState.Ready)?.lineup
    val hidden = settings?.hiddenChannels.orEmpty()
    val visibleLineup = rememberVisibleLineup(lineup, hidden)
    val channelGone = stringResource(Res.string.channel_gone)
    if (lineup == null || visibleLineup == null) {
        LaunchedEffect(Unit) { shell.back() }
        return
    }
    // Hidden channels drop out of zapping only when asked, and never under the channel being watched.
    val playerLineup = if (settings?.zapSkipsHidden == true && route.guideId !in hidden) visibleLineup else lineup
    val start = playerLineup.channels.indexOfFirst { it.guideId == route.guideId }.coerceAtLeast(0)
    val previousGuideId = remember(route) { shell.history.previousFor(route.guideId) }
    PlayerScreen(
        lineup = playerLineup,
        startIndex = start,
        previousGuideId = previousGuideId,
        zapScope = route.scope,
        unattended = route.unattended,
        player = player,
        sessions = vm.sessions,
        onExit = shell::back,
        onGuide = { shell.toGuide(Arrival(atNow = true)) },
        onSearch = { shell.toGuide(Arrival(search = true)) },
        onChannelChanged = { shell.history = shell.history.tuned(it.guideId) },
        reloadLineup = vm::reloadLineup,
        onChannelGone = { channel ->
            shell.notice = formatTemplate(channelGone, channel.label())
            shell.back()
        },
        captionsEnabled = settings?.captionsEnabled ?: false,
        onCaptionsChanged = vm::setCaptionsEnabled,
        captionAdvanceFor = { settings?.captionAdvanceFor(it.guideId) ?: AppSettings.DEFAULT_CAPTION_ADVANCE_MS },
        onCaptionAdvanceChanged = { channel, ms -> vm.setChannelCaptionAdvance(channel.guideId, ms) },
        captionStyle = settings?.captionStyle ?: CaptionStyle.SYSTEM,
        smoothCaptions = settings?.smoothCaptions ?: true,
        favorites = settings?.favorites.orEmpty(),
        favoritesOrder = settings?.favoritesOrder.orEmpty(),
        recentGuideIds = settings?.recentChannels.orEmpty(),
        onToggleFavorite = { vm.toggleFavorite(it.guideId) },
        keyLegendShown = settings?.keyLegendShown ?: AppSettings.KEY_LEGEND_BARS,
        onKeyLegendShown = vm::setKeyLegendShown,
        recordingMarks = recordingMarks.takeIf { capabilities.recordings },
        onRecord = vm.recordings::toggle,
        zoom = shell.videoZoom,
        onZoom = { shell.videoZoom = it },
        liveBreaks = settings?.liveBreaks ?: LiveBreaks.PLAY,
        adsDodgedTodayMs = settings?.adsDodged?.on(localDay()) ?: 0,
        onAdsDodged = { vm.addAdsDodged(localDay(), it) },
        sleep = shell.sleep,
    )
}

@Composable
private fun GuideOrEmpty(
    lineup: Lineup,
    favorites: Set<String>,
    favoritesOrder: List<String>,
    recentChannels: List<String>,
    lastChannel: String?,
    lastFilterKey: String?,
    startAtNow: Boolean,
    startSearch: Boolean,
    returnedFrom: String?,
    onPlay: (guideId: String, scope: ZapScope?, unattended: Boolean) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onFilterChanged: (String?) -> Unit,
    onLoadGuideWindow: (Instant) -> Unit,
    previewPlayer: LivePlayer,
    sessions: PlaybackSessions,
    captionsEnabled: Boolean,
    captionStyle: CaptionStyle,
    autoOpenDelay: AutoOpenDelay,
    previewSound: Boolean,
    recordingMarks: RecordingMarks?,
    onRecord: suspend (Programme) -> Boolean,
    onRecordSeries: suspend (Programme) -> Boolean,
    headerFocus: FocusRequester?,
    startInHeader: Boolean,
    shown: Boolean,
) {
    if (lineup.channels.isEmpty()) {
        MessageScreen(stringResource(Res.string.no_channels), hint = stringResource(Res.string.no_channels_hint))
    } else {
        GuideScreen(
            lineup = lineup,
            favorites = favorites,
            favoritesOrder = favoritesOrder,
            recentChannels = recentChannels,
            initialChannelId = lastChannel,
            initialFilterKey = lastFilterKey,
            startAtNow = startAtNow,
            startSearch = startSearch,
            returnedFrom = returnedFrom,
            onPlay = { channel, scope, unattended -> onPlay(channel.guideId, scope, unattended) },
            onToggleFavorite = { onToggleFavorite(it.guideId) },
            onFilterChanged = onFilterChanged,
            onLoadGuideWindow = onLoadGuideWindow,
            previewPlayer = previewPlayer,
            sessions = sessions,
            captionsEnabled = captionsEnabled,
            captionStyle = captionStyle,
            autoOpenDelay = autoOpenDelay,
            previewSound = previewSound,
            recordingMarks = recordingMarks,
            onRecord = onRecord,
            onRecordSeries = onRecordSeries,
            headerFocus = headerFocus,
            startInHeader = startInHeader,
            shown = shown,
        )
    }
}
