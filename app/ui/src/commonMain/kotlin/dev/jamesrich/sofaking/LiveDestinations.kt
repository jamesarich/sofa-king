package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.data.playerLineup
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.channel_gone
import dev.jamesrich.sofaking.resources.load_failed
import dev.jamesrich.sofaking.resources.load_failed_hint
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.no_channels
import dev.jamesrich.sofaking.resources.no_channels_hint
import dev.jamesrich.sofaking.resources.settings
import dev.jamesrich.sofaking.resources.unconfigured
import dev.jamesrich.sofaking.resources.unconfigured_hint
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.guide.GuideActions
import dev.jamesrich.sofaking.ui.guide.GuideArrival
import dev.jamesrich.sofaking.ui.guide.GuidePreview
import dev.jamesrich.sofaking.ui.guide.GuideScreen
import dev.jamesrich.sofaking.ui.guide.OnLaterScreen
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.localDay
import dev.jamesrich.sofaking.ui.player.ChannelChoices
import dev.jamesrich.sofaking.ui.player.KeyLegendCount
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.MultiviewScreen
import dev.jamesrich.sofaking.ui.player.PlayerBreaks
import dev.jamesrich.sofaking.ui.player.PlayerCaptions
import dev.jamesrich.sofaking.ui.player.PlayerRecording
import dev.jamesrich.sofaking.ui.player.PlayerScreen
import dev.jamesrich.sofaking.ui.player.ZapScope
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** The preview band's player and how [prefs] play it. */
private fun guidePreview(player: LivePlayer, vm: LineupViewModel, prefs: AppSettings) = GuidePreview(
    player = player,
    sessions = vm.sessions,
    captionsEnabled = prefs.captionsEnabled,
    captionStyle = prefs.captionStyle,
    autoOpenDelay = prefs.autoOpenDelay,
    sound = prefs.previewSound,
)

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
    val prefs = settings ?: AppSettings()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val recordingMarks by vm.recordingMarks.collectAsStateWithLifecycle()
    val watchlist by vm.watchlistMarks.collectAsStateWithLifecycle()
    // With no server to talk to, Settings is the only thing to do, so it takes focus.
    val homeFocus = homeFocusFor(state)
    LaunchedEffect(homeFocus) {
        if (homeFocus == HomeFocus.SETTINGS) requestFocusWhenAttached(settingsFocus)
    }
    // Settings arrive a beat after the cached lineup, and the shell composes no page before the cold start is decided.
    val s = state
    when {
        s is LineupState.Loading || settings == null -> MessageScreen(
            stringResource(Res.string.loading),
            loading = true,
        )

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
            lineup = s.visible,
            favorites = prefs.favorites,
            favoritesOrder = prefs.favoritesOrder,
            recentChannels = prefs.recentChannels,
            arrival = arrival.forGuide(settings, hasHeader = headerFocus != null),
            actions = GuideActions(
                onPlay = { channel, scope, unattended -> shell.push(Route.Player(channel.guideId, scope, unattended)) },
                onToggleFavorite = { vm.toggleFavorite(it.guideId) },
                onFilterChange = vm::setGuideFilter,
                onLoadGuideWindow = vm::loadGuideWindow,
                onRecord = vm::toggleRecording,
                onRecordSeries = vm::toggleSeriesRecording,
                onRecordTeam = vm::toggleTeamRecording,
                onRecordKeyword = vm::toggleKeywordRecording,
                onRecordWindow = vm::recordWindow,
                onWatchFromStart = { shell.push(Route.Recording(it, fromStart = true)) },
                onToggleWatchlist = vm::toggleWatchlist,
                watchlisted = { program -> watchlist.has(program).takeIf { watchlist.offered } },
                onOnLater = { shell.push(Route.OnLater) }.takeIf { capabilities.onLater },
            ),
            preview = guidePreview(player, vm, prefs),
            recordingMarks = recordingMarks.takeIf { capabilities.recordings },
            headerFocus = headerFocus,
            shown = shell.top == Route.Guide,
        )
    }
}

/**
 * On Later over the channels the guide shows; a program's details record it, put it on the watchlist or watch its
 * channel full screen.
 */
@Composable
internal fun OnLaterDestination(shell: AppShellState, vm: LineupViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val prefs = settings ?: AppSettings()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val recordingMarks by vm.recordingMarks.collectAsStateWithLifecycle()
    val watchlist by vm.watchlistMarks.collectAsStateWithLifecycle()
    val visibleLineup = (state as? LineupState.Ready)?.visible
    if (visibleLineup == null) {
        MessageScreen(stringResource(Res.string.loading), loading = true)
        return
    }
    val watch = { channel: Channel -> shell.push(Route.Player(channel.guideId)) }
    OnLaterScreen(
        lineup = visibleLineup,
        favorites = prefs.favorites,
        recordingMarks = recordingMarks.takeIf { capabilities.recordings },
        actions = GuideActions(
            onPlay = { channel, _, _ -> watch(channel) },
            onToggleFavorite = { vm.toggleFavorite(it.guideId) },
            onFilterChange = {},
            onLoadGuideWindow = {},
            onRecord = vm::toggleRecording,
            onRecordSeries = vm::toggleSeriesRecording,
            onRecordTeam = vm::toggleTeamRecording,
            onRecordWindow = vm::recordWindow,
            onWatchFromStart = { shell.push(Route.Recording(it, fromStart = true)) },
            onToggleWatchlist = vm::toggleWatchlist,
            watchlisted = { program -> watchlist.has(program).takeIf { watchlist.offered } },
        ),
        load = vm::onLater,
        onWatch = watch,
    )
}

/** Where the guide opens: as the shell [Arrival] asks, on the channel and filter of the last visit. */
private fun Arrival.forGuide(settings: AppSettings?, hasHeader: Boolean) = GuideArrival(
    initialChannelId = settings?.lastChannel,
    initialFilterKey = settings?.guideFilter,
    startAtNow = atNow,
    startSearch = search,
    returnedFrom = returnedFrom,
    startInHeader = inHeader && hasHeader,
)

/** A channel full screen, zapping through the lineup; a lineup that is not in yet sends the viewer back. */
@Composable
internal fun PlayerDestination(route: Route.Player, shell: AppShellState, vm: LineupViewModel, player: LivePlayer) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val prefs = settings ?: AppSettings()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val recordingMarks by vm.recordingMarks.collectAsStateWithLifecycle()
    val ready = state as? LineupState.Ready
    val playerLineup = ready?.let { playerLineup(it.lineup, it.visible, route.guideId, settings) }
    val channelGone = stringResource(Res.string.channel_gone)
    if (playerLineup == null) {
        LaunchedEffect(Unit) { shell.back() }
        return
    }
    val start = playerLineup.channels.indexOfFirst { it.guideId == route.guideId }.coerceAtLeast(0)
    val previousGuideId = remember(route) { shell.history.previousFor(route.guideId) }
    // A `sofaking://live` link or multiview's way back replaces the route in place, under the same entry; a new
    // channel starts a new player state, which reads its start channel only once.
    key(route.guideId) {
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
            onChannelChange = { shell.history = shell.history.tuned(it.guideId) },
            reloadLineup = vm::reloadLineup,
            onChannelMissing = { channel ->
                shell.notice = formatTemplate(channelGone, channel.label())
                shell.back()
            },
            captions = playerCaptions(prefs, vm),
            choices = ChannelChoices(
                favorites = prefs.favorites,
                order = prefs.favoritesOrder,
                recent = prefs.recentChannels,
                onToggleFavorite = { vm.toggleFavorite(it.guideId) },
            ),
            keyLegend = KeyLegendCount(prefs.keyLegendShown, vm::setKeyLegendShown),
            recording = PlayerRecording(
                marks = recordingMarks.takeIf { capabilities.recordings },
                onRecord = vm::toggleRecording,
                onWatchFromStart = { shell.push(Route.Recording(it, fromStart = true)) },
            ),
            zoom = shell.videoZoom,
            onZoom = { shell.videoZoom = it },
            breaks = playerBreaks(prefs, vm),
            sleep = shell.sleep,
            onMultiview = { channel: Channel -> shell.push(Route.Multiview(listOf(channel.guideId))) }
                .takeIf { capabilities.multiview },
        )
    }
}

/** The live player's captions as [prefs] set them, with [vm] keeping the viewer's changes. */
private fun playerCaptions(prefs: AppSettings, vm: LineupViewModel) = PlayerCaptions(
    enabled = prefs.captionsEnabled,
    onEnabledChange = vm::setCaptionsEnabled,
    advanceFor = { prefs.captionAdvanceFor(it.guideId) },
    onAdvanceChange = { channel, ms -> vm.setChannelCaptionAdvance(channel.guideId, ms) },
    style = prefs.captionStyle,
    smooth = prefs.smoothCaptions,
)

/** The live player's break screen as [prefs] set it, counting today's dodged ads through [vm]. */
private fun playerBreaks(prefs: AppSettings, vm: LineupViewModel) = PlayerBreaks(
    mode = prefs.liveBreaks,
    dodgedTodayMs = prefs.adsDodged.on(localDay()),
    onDodge = { vm.addAdsDodged(localDay(), it) },
    facts = vm::programFacts,
)

/**
 * Multiview over the channels the guide shows, opened from the player; watching one full screen takes the player's
 * place, and leaving goes back to the channel it was opened on, which the player may have zapped to since it opened.
 */
@Composable
internal fun MultiviewDestination(
    route: Route.Multiview,
    shell: AppShellState,
    vm: LineupViewModel,
    player: LivePlayer,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val prefs = settings ?: AppSettings()
    val lineup = (state as? LineupState.Ready)?.visible
    if (lineup == null) {
        LaunchedEffect(Unit) { shell.back() }
        return
    }
    MultiviewScreen(
        lineup = lineup,
        initial = route.guideIds,
        player = player,
        sessions = vm.sessions,
        favoritesOrder = prefs.favoritesOrder,
        recent = prefs.recentChannels,
        onWatch = { channel -> shell.backToPlayer(channel.guideId) },
        onExit = { shell.backToPlayer(route.guideIds.first()) },
    )
}

@Composable
private fun GuideOrEmpty(
    lineup: Lineup,
    favorites: Set<String>,
    favoritesOrder: List<String>,
    recentChannels: List<String>,
    arrival: GuideArrival,
    actions: GuideActions,
    preview: GuidePreview,
    recordingMarks: RecordingMarks?,
    headerFocus: FocusRequester?,
    shown: Boolean,
) {
    if (lineup.channels.isEmpty()) {
        MessageScreen(stringResource(Res.string.no_channels), hint = stringResource(Res.string.no_channels_hint))
    } else {
        GuideScreen(
            lineup = lineup,
            favorites = favorites,
            recentChannels = recentChannels,
            arrival = arrival,
            actions = actions,
            preview = preview,
            favoritesOrder = favoritesOrder,
            recordingMarks = recordingMarks,
            headerFocus = headerFocus,
            shown = shown,
        )
    }
}
