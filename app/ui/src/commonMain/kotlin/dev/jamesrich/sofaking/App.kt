package dev.jamesrich.sofaking

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.setSingletonImageLoaderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withStateAtLeast
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.PairingRequest
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.ClockFormat
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.LogoMark
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.guide.GuideScreen
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.player.CaptionSync
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.PlaybackState
import dev.jamesrich.sofaking.ui.player.PlayerScreen
import dev.jamesrich.sofaking.ui.player.ZapScope
import dev.jamesrich.sofaking.ui.player.rememberLivePlayer
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.sofaKingImageLoader
import dev.jamesrich.sofaking.ui.settings.ChannelManagerScreen
import dev.jamesrich.sofaking.ui.settings.ChannelPickerScreen
import dev.jamesrich.sofaking.ui.settings.PairingUi
import dev.jamesrich.sofaking.ui.settings.SettingsActions
import dev.jamesrich.sofaking.ui.settings.SettingsScreen
import dev.jamesrich.sofaking.ui.LocalClockFormat
import dev.jamesrich.sofaking.ui.MatchDisplayFrameRate
import dev.jamesrich.sofaking.ui.guide.LocalGuideDensity
import dev.jamesrich.sofaking.data.StartupTarget
import dev.jamesrich.sofaking.data.visibleChannels
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.runtime.CompositionLocalProvider
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.rememberLastInput
import dev.jamesrich.sofaking.ui.rememberLayoutProfile
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.trackLastInput

/** The whole app, the same on every platform: the theme, the window's layout profile and input, then the screen the user is on. */
/**
 * The whole app. [pairing] is a server to pair with from a `sofaking://pair` link the platform received; the app
 * claims it once and calls [onPairingHandled], so a restored activity does not claim it again.
 */
@Composable
fun App(pairing: PairingRequest? = null, onPairingHandled: () -> Unit = {}) {
    setSingletonImageLoaderFactory(::sofaKingImageLoader)
    SofaKingTheme {
        val lastInput = rememberLastInput()
        CompositionLocalProvider(LocalLayoutProfile provides rememberLayoutProfile(), LocalLastInput provides lastInput) {
            Surface(Modifier.fillMaxSize().trackLastInput(lastInput), color = MaterialTheme.colorScheme.background) { SofaKingApp(pairing = pairing, onPairingHandled = onPairingHandled) }
        }
    }
}

/** The screen the user is on; it survives a restore, but the one-shot arrival details marked [Transient] do not. */
@Serializable
private sealed interface Screen {
    /**
     * [atNow] moves the guide's cursor to the current time on arrival (the Guide key from the player); [search]
     * opens its search by voice (the Search key); [returnedFrom] is the channel the player was left on, which
     * the guide focuses and the preview must not reopen by itself until focus moves.
     */
    @Serializable
    data class Guide(
        val atNow: Boolean = false,
        @Transient val search: Boolean = false,
        @Transient val returnedFrom: String? = null,
    ) : Screen
    /** [scope] is the guide filter the channel was picked from, which Up and Down stay in. */
    @Serializable
    data class Player(val guideId: String, @Transient val scope: ZapScope? = null) : Screen
    @Serializable
    data object Settings : Screen
    /** The channel manager behind Settings. */
    @Serializable
    data object Channels : Screen
    /** The startup channel picker behind Settings. */
    @Serializable
    data object StartupChannel : Screen

    /** Settings and the pages behind it, where no stream plays. */
    val isSettings: Boolean get() = this is Settings || this is Channels || this is StartupChannel
}

@Composable
private fun SofaKingApp(vm: LineupViewModel = viewModel { appGraph.lineupViewModel() }, pairing: PairingRequest? = null, onPairingHandled: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val pairingBusy by vm.pairing.collectAsStateWithLifecycle()
    var screen by rememberSerializable { mutableStateOf<Screen>(Screen.Guide()) }
    val pairingScope = rememberCoroutineScope()
    // Why the last pairing attempt failed; Settings shows it until the next attempt.
    var pairingFailure by remember { mutableStateOf<PairingFailure?>(null) }
    val pair: (String, String) -> Unit = { url, code ->
        pairingScope.launch {
            pairingFailure = vm.pair(url, code)
            screen = if (pairingFailure == null) Screen.Guide() else Screen.Settings
        }
    }
    // A scanned QR code pairs at once; a refused one lands in Settings with the reason.
    LaunchedEffect(pairing) {
        if (pairing == null) return@LaunchedEffect
        onPairingHandled()
        pair(pairing.serverUrl, pairing.code)
    }
    // A short word from the player on its way back to the guide: the channel it was asked for is gone.
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(NOTICE_MS)
        notice = null
    }
    val guideState = rememberSaveableStateHolder()
    val lineup = (state as? LineupState.Ready)?.lineup
    // The lineup as this device shows it: hidden channels leave the guide and the channel list, and the player's
    // zapping when that switch is on; the full lineup stays for the manager, the picker and a channel opened by number.
    val hidden = settings?.hiddenChannels.orEmpty()
    val visibleLineup = remember(lineup, hidden) { lineup?.let { l -> l.copy(channels = visibleChannels(l.channels, hidden)) } }
    // A cold start opens the channel last watched, full screen, once the lineup and settings are in. Decided
    // once per process (restored across configuration changes), and before the guide composes, so the preview
    // never opens a session that the full screen is about to replace.
    var launchDecided by rememberSaveable { mutableStateOf(false) }
    // The channel the player has tuned, so the guide knows which one it came back from, and the one before it
    // for Last; both outlive the player's own composition and a configuration change.
    var history by rememberSerializable { mutableStateOf(WatchHistory()) }
    val launch = launchTarget(state, settings)
    LaunchedEffect(launch, launchDecided) {
        if (launchDecided || launch == LaunchTarget.Undecided) return@LaunchedEffect
        launchDecided = true
        if (launch is LaunchTarget.Player) screen = Screen.Player(launch.guideId)
    }

    // One player for the guide preview and the full screen, so OK on the previewed channel continues its stream,
    // and one server session behind it, promoted from preview to watch in place.
    val captionSync = remember { CaptionSync() }
    val player = rememberLivePlayer(captionSync)
    val sessions = vm.sessions
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val channelGone = stringResource(Res.string.channel_gone)
    // The server replaced the stream under the held session (new epoch, or a session it dropped and the app
    // reopened): the player reopens the item it holds for that channel at the new URL, metadata intact.
    LaunchedEffect(sessions, player) {
        sessions.replaced.collect { stream ->
            lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
                if (player.currentChannelId != stream.channelId || player.currentStreamUrl == stream.url) return@withStateAtLeast
                captionSync.effectiveUs = captionSync.advanceUs
                player.replaceStream(stream)
                player.prepare()
            }
        }
    }
    // Before the first prepare: switching text tracks on a playing live stream restarts it.
    val captionsEnabled = settings?.captionsEnabled
    LaunchedEffect(captionsEnabled) {
        if (captionsEnabled != null) player.setCaptionsEnabled(captionsEnabled)
    }
    val playback = settings?.playback
    LaunchedEffect(playback) {
        if (playback != null) player.setPlaybackPreferences(playback)
    }
    // Only the full screen asks for a display mode: the guide's preview zaps too often for a set that blanks on each switch.
    MatchDisplayFrameRate(player, enabled = playback?.matchFrameRate == true && screen is Screen.Player)
    // The preview starts without a caption lead; the full screen sets each channel's own. Settings may change
    // the server, so it releases the session outright rather than keeping it to reopen.
    LaunchedEffect(screen) {
        when (val s = screen) {
            is Screen.Guide -> captionSync.advanceUs = 0L
            Screen.Settings -> {
                player.stop()
                sessions.release(forget = true)
            }
            is Screen.Player -> Unit
            // Reached from Settings, which already stopped the stream.
            Screen.Channels, Screen.StartupChannel -> Unit
        }
    }
    // Stopped, not paused: the TV's one hardware decoder and the server's session go free while another app is
    // in front. The item and playWhenReady survive; on return a fresh session is opened for the same channel
    // and intent and the item is prepared at its URL, at the live edge or still paused.
    val currentScreen by rememberUpdatedState(screen)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player.stop()
        sessions.releaseAsync()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (!currentScreen.isSettings && player.hasItem && player.status.value.state == PlaybackState.IDLE) {
            captionSync.effectiveUs = captionSync.advanceUs
            scope.launch { resume(player, sessions) }
        }
    }

    CompositionLocalProvider(
        LocalClockFormat provides (settings?.clockFormat ?: ClockFormat.AUTO),
        LocalGuideDensity provides (settings?.guideDensity ?: GuideDensity.COMFORTABLE),
    ) {
        when (val current = screen) {
            Screen.Settings -> settings?.let { s ->
                SettingsScreen(
                    settings = s,
                    onSave = { url, token -> vm.saveServer(url, token); screen = Screen.Guide() },
                    onBack = { screen = Screen.Guide() },
                    channelCount = lineup?.channels?.size ?: 0,
                    channels = lineup?.channels.orEmpty(),
                    actions = SettingsActions(
                        onCaptionsChanged = { vm.setCaptionsEnabled(it) },
                        onCaptionAdvanceChanged = { vm.setCaptionAdvanceMs(it) },
                        onSmoothCaptionsChanged = { vm.setSmoothCaptions(it) },
                        onCaptionStyleChanged = { vm.setCaptionStyle(it) },
                        onAutoOpenDelayChanged = { vm.setAutoOpenDelay(it) },
                        onPreviewSoundChanged = { vm.setPreviewSound(it) },
                        onStartupChanged = { target, guideId -> vm.setStartup(target, guideId) },
                        onChooseStartupChannel = { screen = Screen.StartupChannel },
                        onClockFormatChanged = { vm.setClockFormat(it) },
                        onGuideDensityChanged = { vm.setGuideDensity(it) },
                        onManageChannels = { screen = Screen.Channels },
                        onMatchFrameRateChanged = { vm.setMatchFrameRate(it) },
                        onDecoderChanged = { vm.setDecoder(it) },
                        onBufferChanged = { vm.setBuffer(it) },
                    ),
                    pairing = PairingUi(busy = pairingBusy, failure = pairingFailure, onPair = pair),
                )
            }
            Screen.Channels -> settings?.let { s ->
                ChannelManagerScreen(
                    channels = lineup?.channels.orEmpty(),
                    favorites = s.favorites,
                    favoritesOrder = s.favoritesOrder,
                    hidden = s.hiddenChannels,
                    zapSkipsHidden = s.zapSkipsHidden,
                    onFavoritesOrderChanged = { vm.setFavoritesOrder(it) },
                    onChannelHiddenChanged = { guideId, hide -> vm.setChannelHidden(guideId, hide) },
                    onZapSkipsHiddenChanged = { vm.setZapSkipsHidden(it) },
                    onBack = { screen = Screen.Settings },
                )
            }
            Screen.StartupChannel -> settings?.let { s ->
                ChannelPickerScreen(
                    channels = lineup?.channels.orEmpty(),
                    selected = s.startupChannel,
                    onPick = { channel ->
                        vm.setStartup(StartupTarget.CHANNEL, channel.guideId)
                        screen = Screen.Settings
                    },
                    onBack = { screen = Screen.Settings },
                )
            }
            is Screen.Player -> if (lineup != null && visibleLineup != null) {
                // Hidden channels drop out of zapping only when asked, and never under the channel being watched.
                val playerLineup = if (settings?.zapSkipsHidden == true && current.guideId !in hidden) visibleLineup else lineup
                val start = playerLineup.channels.indexOfFirst { it.guideId == current.guideId }.coerceAtLeast(0)
                val previousGuideId = remember(current) { history.previousFor(current.guideId) }
                PlayerScreen(
                    lineup = playerLineup,
                    startIndex = start,
                    previousGuideId = previousGuideId,
                    zapScope = current.scope,
                    player = player,
                    sessions = sessions,
                    onExit = { screen = Screen.Guide(returnedFrom = history.watching) },
                    onGuide = { screen = Screen.Guide(atNow = true, returnedFrom = history.watching) },
                    onSearch = { screen = Screen.Guide(search = true, returnedFrom = history.watching) },
                    onChannelChanged = {
                        history = history.tuned(it.guideId)
                        vm.setLastChannel(it.guideId)
                    },
                    reloadLineup = vm::reloadLineup,
                    onChannelGone = { channel ->
                        notice = formatTemplate(channelGone, channel.label())
                        screen = Screen.Guide(returnedFrom = history.watching)
                    },
                    captionsEnabled = settings?.captionsEnabled ?: false,
                    onCaptionsChanged = { vm.setCaptionsEnabled(it) },
                    captionAdvanceFor = { settings?.captionAdvanceFor(it.guideId) ?: AppSettings.DEFAULT_CAPTION_ADVANCE_MS },
                    onCaptionAdvanceChanged = { channel, ms -> vm.setChannelCaptionAdvance(channel.guideId, ms) },
                    captionStyle = settings?.captionStyle ?: CaptionStyle.SYSTEM,
                    smoothCaptions = settings?.smoothCaptions ?: true,
                    favorites = settings?.favorites.orEmpty(),
                    favoritesOrder = settings?.favoritesOrder.orEmpty(),
                    keyLegendShown = settings?.keyLegendShown ?: AppSettings.KEY_LEGEND_BARS,
                    onKeyLegendShown = { vm.setKeyLegendShown(it) },
                )
            } else {
                LaunchedEffect(Unit) { screen = Screen.Guide() }
            }
            // The guide's saveable state (filter, cursor, scroll, focused channel) outlives a trip to the player.
            is Screen.Guide -> guideState.SaveableStateProvider("guide") {
                Column {
                    val settingsFocus = remember { FocusRequester() }
                    Header(
                        refreshing = loading && state is LineupState.Ready,
                        notice = notice,
                        onSettings = { screen = Screen.Settings },
                        onRefresh = vm::refresh,
                        settingsFocus = settingsFocus,
                    )
                    // With no server to talk to, Settings is the only thing to do, so it takes focus over Refresh.
                    val homeFocus = homeFocusFor(state)
                    LaunchedEffect(homeFocus) {
                        if (homeFocus == HomeFocus.SETTINGS) requestFocusWhenAttached(settingsFocus)
                    }
                    // Settings arrive a beat after the cached lineup; the guide waits for them so it can open on the last channel.
                    val s = state
                    when {
                        s is LineupState.Loading || settings == null || !launchDecided -> MessageScreen(stringResource(Res.string.loading), loading = true)
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
                            startAtNow = current.atNow,
                            startSearch = current.search,
                            returnedFrom = current.returnedFrom,
                            onPlay = { guideId, scope -> screen = Screen.Player(guideId, scope) },
                            onToggleFavorite = vm::toggleFavorite,
                            onFilterChanged = vm::setGuideFilter,
                            onLoadGuideWindow = vm::loadGuideWindow,
                            previewPlayer = player,
                            sessions = sessions,
                            captionsEnabled = settings?.captionsEnabled ?: false,
                            captionStyle = settings?.captionStyle ?: CaptionStyle.SYSTEM,
                            autoOpenDelay = settings?.autoOpenDelay ?: AutoOpenDelay.AFTER_10_SECONDS,
                            previewSound = settings?.previewSound ?: true,
                        )
                    }
                }
            }
        }
    }
}

/** Back in front: a fresh session for the channel and intent released on stop, at the URL the player holds for it. */
private suspend fun resume(player: LivePlayer, sessions: PlaybackSessions) {
    val stream = try {
        sessions.reopen()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Preparing the stale item fails on the player, where the screen's own reconnect takes over.
        null
    }
    if (!player.hasItem) return
    if (stream != null && stream.channelId == player.currentChannelId) player.replaceStream(stream)
    if (player.status.value.state == PlaybackState.IDLE) player.prepare()
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
    onPlay: (String, ZapScope?) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onFilterChanged: (String?) -> Unit,
    onLoadGuideWindow: (Instant) -> Unit,
    previewPlayer: LivePlayer,
    sessions: PlaybackSessions,
    captionsEnabled: Boolean,
    captionStyle: CaptionStyle,
    autoOpenDelay: AutoOpenDelay,
    previewSound: Boolean,
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
            onPlay = { channel, scope -> onPlay(channel.guideId, scope) },
            onToggleFavorite = { onToggleFavorite(it.guideId) },
            onFilterChanged = onFilterChanged,
            onLoadGuideWindow = onLoadGuideWindow,
            previewPlayer = previewPlayer,
            sessions = sessions,
            captionsEnabled = captionsEnabled,
            captionStyle = captionStyle,
            autoOpenDelay = autoOpenDelay,
            previewSound = previewSound,
        )
    }
}

/** App bar above the guide, inside the overscan margins; the mark sits within the buttons' height so no grid row is lost. */
@Composable
private fun Header(refreshing: Boolean, notice: String?, onSettings: () -> Unit, onRefresh: () -> Unit, settingsFocus: FocusRequester) {
    Row(
        Modifier.fillMaxWidth().screenPadding(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoMark(40.dp)
        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        // Status sits right of the name in whatever the buttons leave, so a long notice ellipsizes rather than
        // squeezing Refresh and Settings.
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The lineup on screen stays usable while it refreshes; the loader only says new data is on its way.
            AnimatedVisibility(
                visible = refreshing,
                enter = PopIn,
                exit = PopOut,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SofaLoader(size = 28.dp)
                    Text(
                        stringResource(Res.string.refreshing),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // The last text is kept so the notice reads while it fades out.
            var lastNotice by remember { mutableStateOf("") }
            LaunchedEffect(notice) { if (notice != null) lastNotice = notice }
            AnimatedVisibility(visible = notice != null, enter = PopIn, exit = PopOut, modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    notice ?: lastNotice,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SofaButton(onClick = onRefresh) { Text(stringResource(Res.string.refresh)) }
        SofaButton(onClick = onSettings, modifier = Modifier.focusRequester(settingsFocus)) { Text(stringResource(Res.string.settings)) }
    }
}

private const val NOTICE_MS = 5_000L
