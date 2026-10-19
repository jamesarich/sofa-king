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
import androidx.compose.runtime.key
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.defaultAudio
import dev.jamesrich.sofaking.data.initialSeasonId
import dev.jamesrich.sofaking.data.initialSubtitle
import dev.jamesrich.sofaking.data.initialAudio
import dev.jamesrich.sofaking.data.isPictureSubtitle
import dev.jamesrich.sofaking.data.trackChoice
import dev.jamesrich.sofaking.data.trackLabel
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.library.CardMenu
import dev.jamesrich.sofaking.ui.library.ItemDetailsActions
import dev.jamesrich.sofaking.ui.library.ItemDetailsScreen
import dev.jamesrich.sofaking.ui.library.LibraryGridActions
import dev.jamesrich.sofaking.ui.library.LibraryGridScreen
import dev.jamesrich.sofaking.ui.library.LibraryHomeActions
import dev.jamesrich.sofaking.ui.library.LibraryHomeScreen
import dev.jamesrich.sofaking.ui.library.libraryFocusKey
import dev.jamesrich.sofaking.ui.library.LibrarySearchActions
import dev.jamesrich.sofaking.ui.library.LibrarySearchScreen
import dev.jamesrich.sofaking.ui.library.SEARCH_FOCUS_KEY
import dev.jamesrich.sofaking.ui.library.stillOrBackdrop
import dev.jamesrich.sofaking.ui.player.PlayerIcons
import dev.jamesrich.sofaking.ui.theme.tvFocus
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
import dev.jamesrich.sofaking.data.DvrRecording
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.data.RecordingMarks
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.LogoMark
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.guide.GuideScreen
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.player.CaptionSync
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.PlaybackState
import dev.jamesrich.sofaking.ui.player.PlayerScreen
import dev.jamesrich.sofaking.ui.player.VodMarker
import dev.jamesrich.sofaking.ui.player.VodMarkerKind
import dev.jamesrich.sofaking.ui.player.VodPlayerScreen
import dev.jamesrich.sofaking.ui.player.VodTitle
import dev.jamesrich.sofaking.ui.player.VodTrack
import dev.jamesrich.sofaking.ui.player.VodTracks
import dev.jamesrich.sofaking.ui.player.VodUpNext
import dev.jamesrich.sofaking.ui.recordings.RecordingsScreen
import dev.jamesrich.sofaking.ui.recordings.recordingSubtitle
import dev.jamesrich.sofaking.ui.player.ZapScope
import dev.jamesrich.sofaking.ui.player.rememberLivePlayer
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.sofaKingImageLoader
import dev.jamesrich.sofaking.ui.settings.ChannelManagerScreen
import dev.jamesrich.sofaking.ui.settings.ChannelPickerScreen
import dev.jamesrich.sofaking.ui.settings.PairingUi
import dev.jamesrich.sofaking.ui.settings.SettingsActions
import dev.jamesrich.sofaking.ui.settings.SettingsLock
import dev.jamesrich.sofaking.ui.settings.SettingsLockScreen
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
    /**
     * A library item full screen; [fromStart] plays it from the beginning even when it is in progress. Leaving
     * returns to [parent], the library page it was played from, with the item focused.
     */
    @Serializable
    data class Vod(val itemId: String, val fromStart: Boolean = false, val parent: Screen = Library()) : Screen
    /** The Library tab's home. [returnedFrom] is the item whose page the viewer just left, which it focuses. */
    @Serializable
    data class Library(@Transient val returnedFrom: String? = null) : Screen
    /**
     * Search across the libraries, under the Library tab; [voice] starts it by voice on arrival, and [query] searches
     * for a name from an item's page, which Back returns to as [parent].
     */
    @Serializable
    data class LibrarySearch(
        @Transient val voice: Boolean = false,
        @Transient val returnedFrom: String? = null,
        val query: String? = null,
        val parent: Screen? = null,
    ) : Screen
    /** One library's grid, under the Library tab. */
    @Serializable
    data class LibraryGrid(val libraryId: String, @Transient val returnedFrom: String? = null) : Screen
    /** An item's page, full screen; Back returns to [parent]. [returnedFrom] is the episode just watched. */
    @Serializable
    data class LibraryItem(val itemId: String, val parent: Screen, @Transient val returnedFrom: String? = null) : Screen
    /** The Recordings tab. */
    @Serializable
    data object Recordings : Screen
    /** A recording full screen; [fromStart] plays it from the beginning even when it is in progress. */
    @Serializable
    data class Recording(val recordingId: String, val fromStart: Boolean = false) : Screen
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

    /** The guide and the live player, which hold a live channel's stream. */
    val playsLive: Boolean get() = this is Guide || this is Player

    /** This screen with the item [itemId] marked as the one the viewer comes back from, to focus. */
    fun returningFrom(itemId: String): Screen = when (this) {
        is Library -> copy(returnedFrom = itemId)
        is LibraryGrid -> copy(returnedFrom = itemId)
        is LibraryItem -> copy(returnedFrom = itemId)
        is LibrarySearch -> copy(returnedFrom = itemId)
        else -> this
    }
}

@Composable
private fun SofaKingApp(vm: LineupViewModel = viewModel { appGraph.lineupViewModel() }, pairing: PairingRequest? = null, onPairingHandled: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val pairingBusy by vm.pairing.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val recordingMarks by vm.recordings.marks.collectAsStateWithLifecycle()
    val libraryVm = viewModel { LibraryViewModel(vm.api, vm.sessions) }
    val recordingsVm = viewModel { RecordingsViewModel(vm.api, vm.sessions, changed = vm.recordings::refresh) }
    val libraryHome by libraryVm.home.collectAsStateWithLifecycle()
    val grid by libraryVm.grid.collectAsStateWithLifecycle()
    // The Library tab shows when the server's bootstrap says it serves libraries and it has at least one.
    val libraryShown = capabilities.library && when (val home = libraryHome) {
        LibraryHomeState.Unavailable -> false
        is LibraryHomeState.Ready -> home.home.libraries.isNotEmpty()
        else -> true
    }
    var screen by rememberSerializable { mutableStateOf<Screen>(Screen.Guide()) }
    // Where Back from Settings goes: the tab's page it was opened from, with focus on the header's open tab, as
    // the Settings button it left from sits beside it.
    var settingsReturn by rememberSerializable { mutableStateOf<Screen>(Screen.Guide()) }
    var focusTabOnReturn by remember { mutableStateOf(false) }
    val leaveSettings = {
        focusTabOnReturn = true
        screen = settingsReturn
    }
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
    // The PIN, once entered, opens Settings and the pages behind it until the viewer leaves for the guide or the
    // player; the lock's count of wrong tries outlives that trip.
    var settingsUnlocked by rememberSaveable { mutableStateOf(false) }
    val settingsLock = remember { SettingsLock() }
    val settingsLocked = settings?.settingsLocked == true && !settingsUnlocked
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
    // reopened): the player reopens the item it holds for that channel at the new URL, metadata intact. A library
    // item's new stream may start elsewhere in it, so its own screen loads it and seeks.
    LaunchedEffect(sessions, player) {
        sessions.replaced.collect { stream ->
            if (stream.itemId != null) return@collect
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
    MatchDisplayFrameRate(player, enabled = playback?.matchFrameRate == true && (screen is Screen.Player || screen is Screen.Vod || screen is Screen.Recording))
    // The preview starts without a caption lead; the full screen sets each channel's own. Settings may change
    // the server, so it releases the session outright rather than keeping it to reopen.
    LaunchedEffect(screen) {
        if (!screen.isSettings) settingsUnlocked = false
        when (val s = screen) {
            is Screen.Guide -> captionSync.advanceUs = 0L
            Screen.Settings -> {
                player.stop()
                sessions.release(forget = true)
            }
            is Screen.Player, is Screen.Vod, is Screen.Recording -> Unit
            // The library and the recordings play nothing until one is picked, so the guide's preview stops on the way in.
            is Screen.Library, is Screen.LibraryGrid, is Screen.LibrarySearch, is Screen.LibraryItem, Screen.Recordings -> {
                player.stop()
                sessions.release(forget = true)
            }
            // Reached from Settings, which already stopped the stream.
            Screen.Channels, Screen.StartupChannel -> Unit
        }
    }
    // Stopped, not paused: the TV's one hardware decoder and the server's session go free while another app is
    // in front. The item and playWhenReady survive; on return a fresh session is opened for the same channel
    // and intent and the item is prepared at its URL, at the live edge or still paused. A library item's screen
    // reopens it itself, where it was, and the library's other pages hold no stream.
    val currentScreen by rememberUpdatedState(screen)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player.stop()
        sessions.releaseAsync()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (currentScreen.playsLive && player.hasItem && player.status.value.state == PlaybackState.IDLE) {
            captionSync.effectiveUs = captionSync.advanceUs
            scope.launch { resume(player, sessions) }
        }
    }

    // The channel joins Recent, and becomes the one a cold start opens, once it has played for RECENT_AFTER_MS.
    val playing = history.watching.takeIf { screen is Screen.Player }
    LaunchedEffect(playing) {
        if (playing == null) return@LaunchedEffect
        delay(RECENT_AFTER_MS)
        vm.setLastChannel(playing)
    }

    CompositionLocalProvider(
        LocalClockFormat provides (settings?.clockFormat ?: ClockFormat.AUTO),
        LocalGuideDensity provides (settings?.guideDensity ?: GuideDensity.COMFORTABLE),
    ) {
        when (val current = screen) {
            Screen.Settings, Screen.Channels, Screen.StartupChannel -> if (settingsLocked) settings?.let { s ->
                SettingsLockScreen(
                    pinHash = s.settingsPin,
                    apiToken = s.apiToken,
                    onUnlocked = { settingsUnlocked = true },
                    onPinRemoved = {
                        vm.setSettingsPin(null)
                        settingsUnlocked = true
                    },
                    onBack = leaveSettings,
                    lock = settingsLock,
                )
            } else when (current) {
                Screen.Settings -> settings?.let { s ->
                    SettingsScreen(
                        settings = s,
                        onSave = { url, token -> vm.saveServer(url, token); screen = Screen.Guide() },
                        onBack = leaveSettings,
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
                            onCheckForUpdate = { version, again -> vm.checkForUpdate(version, again) },
                            onSettingsPinChanged = { pin ->
                                vm.setSettingsPin(pin)
                                // The page that set the PIN stays open; the lock takes effect on the next visit.
                                settingsUnlocked = true
                            },
                        ),
                        pairing = PairingUi(busy = pairingBusy, failure = pairingFailure, onPair = pair),
                        update = update,
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
                        onChannelsHiddenChanged = { guideIds, hide -> vm.setChannelsHidden(guideIds, hide) },
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
                else -> Unit
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
                    onChannelChanged = { history = history.tuned(it.guideId) },
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
                    recentGuideIds = settings?.recentChannels.orEmpty(),
                    onToggleFavorite = { vm.toggleFavorite(it.guideId) },
                    keyLegendShown = settings?.keyLegendShown ?: AppSettings.KEY_LEGEND_BARS,
                    onKeyLegendShown = { vm.setKeyLegendShown(it) },
                )
            } else {
                LaunchedEffect(Unit) { screen = Screen.Guide() }
            }
            is Screen.Vod -> {
                val detail by remember(current.itemId) { libraryVm.detail(current.itemId) }.collectAsStateWithLifecycle()
                val item = (detail as? ItemDetailState.Ready)?.detail?.item
                val leave = { screen = current.parent.returningFrom(current.itemId) }
                BackHandler(enabled = item == null, onBack = leave)
                when {
                    // Keyed by the item, so the next episode starts a fresh screen rather than inheriting this one's end.
                    item != null -> key(item.id) {
                        var next by remember { mutableStateOf<MediaItem?>(null) }
                        val ready = (detail as? ItemDetailState.Ready)?.detail
                        // The tracks the viewer picked in this show start its episodes, read once as the item opens.
                        val choice = remember { item.showId?.let { settings?.trackChoices?.get(it) } }
                        val startAudio = remember { ready?.let { initialAudio(it, choice) } }
                        val startSubtitle = remember { ready?.let { initialSubtitle(it, choice) } }
                        // The tracks the stream carries, so a retry opens the item with them again: an audio track other
                        // than the default, and a picture subtitle drawn in from the first stream.
                        var audio by remember { mutableStateOf(startAudio?.index?.takeIf { it != ready?.let(::defaultAudio)?.index }) }
                        var pictureSubtitle by remember { mutableStateOf(startSubtitle?.takeIf(::isPictureSubtitle)?.index) }
                        val tracks = remember(detail) {
                            (detail as? ItemDetailState.Ready)?.detail?.let { d ->
                                VodTracks(
                                    audio = d.audio.mapIndexed { i, t -> VodTrack(t.index, trackLabel(t, i + 1)) },
                                    subtitles = d.subtitles.mapIndexed { i, t -> VodTrack(t.index, trackLabel(t, i + 1), isPictureSubtitle(t)) },
                                    initialAudio = startAudio?.index ?: defaultAudio(d)?.index,
                                    initialSubtitle = startSubtitle?.index,
                                    onPicked = { audioIndex, subtitleIndex ->
                                        item.showId?.let { vm.setTrackChoice(it, trackChoice(d, audioIndex, subtitleIndex)) }
                                    },
                                    select = { audioIndex, subtitleIndex, position ->
                                        audio = audioIndex
                                        pictureSubtitle = subtitleIndex
                                        libraryVm.switchTracks(item, player.playbackProfile, position, audioIndex, subtitleIndex)
                                    },
                                    loadSubtitles = { index -> libraryVm.subtitles(item.id, index) },
                                )
                            } ?: VodTracks()
                        }
                        VodPlayerScreen(
                            title = item.vodTitle(),
                            player = player,
                            sessions = sessions,
                            open = { libraryVm.play(item, player.playbackProfile, current.fromStart, audio, pictureSubtitle) },
                            onPosition = { position, duration -> libraryVm.progress(item.id, position, duration) },
                            onStopped = { position, duration -> libraryVm.stopped(item.id, position, duration) },
                            onExit = leave,
                            upNext = { libraryVm.upNext(item)?.also { next = it }?.upNext() },
                            onPlayNext = { next?.let { screen = Screen.Vod(it.id, fromStart = false, parent = current.parent) } },
                            tracks = tracks,
                        )
                    }
                    detail is ItemDetailState.Failed -> MessageScreen(
                        message = stringResource(Res.string.library_load_failed),
                        detail = (detail as ItemDetailState.Failed).message,
                        onRetry = { libraryVm.detail(current.itemId, refresh = true) },
                    )
                    else -> MessageScreen(stringResource(Res.string.library_loading), loading = true)
                }
            }
            is Screen.Recording -> {
                val recordings by recordingsVm.state.collectAsStateWithLifecycle()
                // Kept once found, so a list reload while it plays never swaps the title or markers under the player.
                val recording = remember(current.recordingId, recordings is RecordingsState.Ready) { recordingsVm.recording(current.recordingId) }
                BackHandler(enabled = recording == null) { screen = Screen.Recordings }
                when {
                    recording != null -> VodPlayerScreen(
                        title = recording.vodTitle(),
                        player = player,
                        sessions = sessions,
                        open = { recordingsVm.play(recording, player.playbackProfile, current.fromStart) },
                        onPosition = { position, duration -> recordingsVm.progress(recording.id, position, duration) },
                        onStopped = { position, duration -> recordingsVm.stopped(recording.id, position, duration) },
                        onExit = { screen = Screen.Recordings },
                        autoSkipBreaks = settings?.skipCommercials ?: false,
                    )
                    recordings is RecordingsState.Ready || recordings is RecordingsState.Failed -> LaunchedEffect(Unit) { screen = Screen.Recordings }
                    else -> MessageScreen(stringResource(Res.string.loading), loading = true)
                }
            }
            is Screen.LibraryItem -> {
                val detail by remember(current.itemId) { libraryVm.detail(current.itemId) }.collectAsStateWithLifecycle()
                val ready = (detail as? ItemDetailState.Ready)?.detail
                // The season shown under a show: the viewer's pick, else the one with the next episode.
                var pickedSeason by rememberSaveable(current.itemId) { mutableStateOf<String?>(null) }
                val seasonId = pickedSeason ?: ready?.let(::initialSeasonId)
                val season = seasonId?.let { id -> remember(id) { libraryVm.detail(id) }.collectAsStateWithLifecycle().value }
                BackHandler { screen = current.parent.returningFrom(current.itemId) }
                ItemDetailsScreen(
                    state = detail,
                    season = season,
                    selectedSeasonId = seasonId,
                    actions = ItemDetailsActions(
                        onPlay = { item, fromStart -> screen = Screen.Vod(item.id, fromStart, parent = current.copy(returnedFrom = null)) },
                        onSetWatched = libraryVm::setWatched,
                        onSelectSeason = { pickedSeason = it },
                        onRetry = { libraryVm.detail(current.itemId, refresh = true) },
                        onPerson = { screen = Screen.LibrarySearch(query = it, parent = current.copy(returnedFrom = null)) },
                    ),
                    returnedFrom = current.returnedFrom,
                )
            }
            is Screen.Guide, is Screen.Library, is Screen.LibraryGrid, is Screen.LibrarySearch, Screen.Recordings -> {
                val tab = when (current) {
                    is Screen.Guide -> Tab.LIVE
                    Screen.Recordings -> Tab.RECORDINGS
                    else -> Tab.LIBRARY
                }
                val openTab: (Tab) -> Unit = { picked ->
                    if (picked != tab) {
                        screen = when (picked) {
                            Tab.LIVE -> Screen.Guide(returnedFrom = history.watching)
                            Tab.LIBRARY -> Screen.Library()
                            Tab.RECORDINGS -> Screen.Recordings
                        }
                    }
                }
                val settingsFocus = remember { FocusRequester() }
                val tabFocus = remember { FocusRequester() }
                val openSettings = {
                    settingsReturn = if (current is Screen.Guide) Screen.Guide() else current
                    screen = Screen.Settings
                }
                TopLevel(
                    tv = LocalLayoutProfile.current.tv,
                    tabs = TabsModel(tab, libraryShown, capabilities.recordings, openTab),
                    onSettings = openSettings,
                    settingsFocus = settingsFocus,
                ) { showsTabs ->
                    // Keyed on nothing, so clearing the flag does not cancel the request; it runs on each return here.
                    LaunchedEffect(Unit) {
                        if (!focusTabOnReturn) return@LaunchedEffect
                        if (showsTabs) requestFocusWhenAttached(tabFocus)
                        focusTabOnReturn = false
                    }
                    var headerFocused by remember { mutableStateOf(false) }
                    val tabBack: () -> Unit = {
                        when (tabBackAction(hasHeader = showsTabs, headerFocused = headerFocused)) {
                            TabBack.FOCUS_HEADER -> tabFocus.requestFocus()
                            TabBack.OPEN_LIVE -> openTab(Tab.LIVE)
                        }
                    }
                    Column {
                        Header(
                            modifier = Modifier.onFocusChanged { headerFocused = it.hasFocus },
                            refreshing = if (tab == Tab.LIVE) loading && state is LineupState.Ready else false,
                            notice = notice,
                            onSettings = openSettings.takeIf { showsTabs },
                            onRefresh = when (tab) {
                                Tab.LIVE -> vm::refresh
                                Tab.LIBRARY -> libraryVm::refreshHome
                                Tab.RECORDINGS -> recordingsVm::refresh
                            },
                            settingsFocus = settingsFocus,
                            tabs = TabsModel(tab, libraryShown, capabilities.recordings, openTab).takeIf { showsTabs },
                            tabFocus = tabFocus,
                        )
                        when (current) {
                            // The guide's saveable state (filter, cursor, scroll, focused channel) outlives a trip to the player.
                            is Screen.Guide -> guideState.SaveableStateProvider("guide") {
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
                                        recordingMarks = recordingMarks.takeIf { capabilities.recordings },
                                        onRecord = vm.recordings::toggle,
                                        onRecordSeries = vm.recordings::toggleSeries,
                                        headerFocus = tabFocus.takeIf { showsTabs },
                                    )
                                }
                            }
                            is Screen.Library -> guideState.SaveableStateProvider("library") {
                                BackHandler(onBack = tabBack)
                                LibraryHomeScreen(
                                    state = libraryHome,
                                    actions = LibraryHomeActions(
                                        onOpenItem = { screen = Screen.LibraryItem(it.id, parent = Screen.Library()) },
                                        onPlayItem = { screen = Screen.Vod(it.id, parent = Screen.Library()) },
                                        onOpenLibrary = { screen = Screen.LibraryGrid(it.id) },
                                        onRetry = libraryVm::refreshHome,
                                        onSearch = { voice -> screen = Screen.LibrarySearch(voice) },
                                        menu = CardMenu(
                                            onPlayFromStart = { screen = Screen.Vod(it.id, fromStart = true, parent = Screen.Library()) },
                                            onSetWatched = libraryVm::setWatched,
                                        ),
                                    ),
                                    returnedFrom = current.returnedFrom,
                                )
                            }
                            Screen.Recordings -> {
                                val recordings by recordingsVm.state.collectAsStateWithLifecycle()
                                LaunchedEffect(Unit) { recordingsVm.refresh() }
                                RecordingsScreen(
                                    state = recordings,
                                    skipCommercials = settings?.skipCommercials ?: false,
                                    onSkipCommercialsChanged = { vm.setSkipCommercials(it) },
                                    onPlay = { recording, fromStart -> screen = Screen.Recording(recording.id, fromStart) },
                                    onDelete = recordingsVm::delete,
                                    onRetry = recordingsVm::refresh,
                                    onBack = tabBack,
                                    onRuleChange = { rule, newOnly, keep -> recordingsVm.updateRule(rule, newOnly, keep) },
                                    onRuleDelete = recordingsVm::deleteRule,
                                )
                            }
                            // The query outlives a trip to an item's page and back.
                            is Screen.LibrarySearch -> guideState.SaveableStateProvider("library-search:${current.query.orEmpty()}") {
                                BackHandler { screen = current.parent ?: Screen.Library(returnedFrom = SEARCH_FOCUS_KEY) }
                                val here = current.copy(voice = false, returnedFrom = null)
                                LibrarySearchScreen(
                                    actions = LibrarySearchActions(
                                        search = libraryVm::search,
                                        onOpenItem = { screen = Screen.LibraryItem(it.id, parent = here) },
                                        onPlayItem = { screen = Screen.Vod(it.id, parent = here) },
                                        menu = CardMenu(
                                            onPlayFromStart = { screen = Screen.Vod(it.id, fromStart = true, parent = here) },
                                            onSetWatched = libraryVm::setWatched,
                                        ),
                                    ),
                                    voice = current.voice,
                                    returnedFrom = current.returnedFrom,
                                    initialQuery = current.query,
                                )
                            }
                            is Screen.LibraryGrid -> guideState.SaveableStateProvider("grid-${current.libraryId}") {
                                LaunchedEffect(current.libraryId) {
                                    val open = grid
                                    libraryVm.openGrid(current.libraryId, open?.sort ?: LibrarySort.TITLE, open?.unwatched ?: false, open?.takeIf { it.libraryId == current.libraryId }?.genre)
                                }
                                BackHandler { screen = Screen.Library(returnedFrom = libraryFocusKey(current.libraryId)) }
                                val shown = grid?.takeIf { it.libraryId == current.libraryId }
                                val here = Screen.LibraryGrid(current.libraryId)
                                LibraryGridScreen(
                                    library = (libraryHome as? LibraryHomeState.Ready)?.home?.libraries?.firstOrNull { it.id == current.libraryId },
                                    grid = shown,
                                    actions = LibraryGridActions(
                                        onSort = { libraryVm.openGrid(current.libraryId, it, shown?.unwatched ?: false, shown?.genre) },
                                        onUnwatched = { libraryVm.openGrid(current.libraryId, shown?.sort ?: LibrarySort.TITLE, it, shown?.genre) },
                                        onGenre = { libraryVm.openGrid(current.libraryId, shown?.sort ?: LibrarySort.TITLE, shown?.unwatched ?: false, it) },
                                        onOpenItem = { screen = Screen.LibraryItem(it.id, parent = here) },
                                        onPlayItem = { screen = Screen.Vod(it.id, parent = here) },
                                        onLoadMore = libraryVm::loadMore,
                                        menu = CardMenu(
                                            onPlayFromStart = { screen = Screen.Vod(it.id, fromStart = true, parent = here) },
                                            onSetWatched = libraryVm::setWatched,
                                        ),
                                    ),
                                    returnedFrom = current.returnedFrom,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What the player shows for a library item: an episode under its show and number, a movie with its year. */
private fun MediaItem.vodTitle(): VodTitle {
    val subtitle = when (kind) {
        ItemKind.EPISODE -> listOfNotNull(showTitle, episodeLabel).joinToString(" · ").ifEmpty { null }
        else -> year?.toString()
    }
    return VodTitle(title, subtitle, artworkUrl = backdrop ?: poster)
}

/** What the player shows for a recording: its episode and when it aired, with the commercial breaks marked. */
@Composable
private fun DvrRecording.vodTitle(): VodTitle {
    val time = rememberTimeFormatter()
    return VodTitle(
        title = title,
        subtitle = recordingSubtitle(this, time),
        artworkUrl = imageUrl,
        markers = breaks.map { VodMarker(it.startMs, it.endMs, VodMarkerKind.BREAK) },
    )
}

/** The Up next card for an episode: its number and title under the show, with its still. */
private fun MediaItem.upNext(): VodUpNext = VodUpNext(
    title = title,
    subtitle = listOfNotNull(showTitle, episodeLabel).joinToString(" · ").ifEmpty { null },
    artworkUrl = stillOrBackdrop(),
)

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
    recordingMarks: RecordingMarks?,
    onRecord: suspend (Programme) -> Boolean,
    onRecordSeries: suspend (Programme) -> Boolean,
    headerFocus: FocusRequester?,
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
            recordingMarks = recordingMarks,
            onRecord = onRecord,
            onRecordSeries = onRecordSeries,
            headerFocus = headerFocus,
        )
    }
}

/** The app's top-level destinations; Settings sits apart, as a button on a TV and the last item elsewhere. */
private enum class Tab { LIVE, LIBRARY, RECORDINGS }

/** Which destination is open, whether Library and Recordings are offered, and what picking one does. */
private class TabsModel(val selected: Tab, val libraryShown: Boolean, val recordingsShown: Boolean, val onTab: (Tab) -> Unit)

/**
 * Live, Library and Settings around [content]. A TV keeps them in the header, so the guide loses no width and Up
 * from its chips reaches them as it reaches the header's buttons today; [content] gets true to draw them there.
 * Elsewhere Material's navigation suite puts them in a bottom bar on a compact window and a rail on a larger one.
 */
@Composable
private fun TopLevel(tv: Boolean, tabs: TabsModel, onSettings: () -> Unit, settingsFocus: FocusRequester, content: @Composable (showsTabs: Boolean) -> Unit) {
    if (tv) {
        content(true)
        return
    }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(
                selected = tabs.selected == Tab.LIVE,
                onClick = { tabs.onTab(Tab.LIVE) },
                icon = { Icon(PlayerIcons.LiveTv, contentDescription = null) },
                label = { Text(stringResource(Res.string.nav_live)) },
            )
            if (tabs.libraryShown) {
                item(
                    selected = tabs.selected == Tab.LIBRARY,
                    onClick = { tabs.onTab(Tab.LIBRARY) },
                    icon = { Icon(PlayerIcons.VideoLibrary, contentDescription = null) },
                    label = { Text(stringResource(Res.string.nav_library)) },
                )
            }
            if (tabs.recordingsShown) {
                item(
                    selected = tabs.selected == Tab.RECORDINGS,
                    onClick = { tabs.onTab(Tab.RECORDINGS) },
                    icon = { Icon(PlayerIcons.Recordings, contentDescription = null) },
                    label = { Text(stringResource(Res.string.recordings_title)) },
                )
            }
            item(
                selected = false,
                onClick = onSettings,
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                label = { Text(stringResource(Res.string.nav_settings)) },
                modifier = Modifier.focusRequester(settingsFocus),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        content(false)
    }
}

/**
 * The TV's destinations as pills in the header: the open one filled, every one with the D-pad focus cue. Focus
 * coming up from the page lands on the open one ([tabFocus]).
 */
@Composable
private fun HeaderTabs(tabs: TabsModel, tabFocus: FocusRequester) {
    Row(
        Modifier.focusProperties { onEnter = { tabFocus.requestFocus() } }.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderTab(stringResource(Res.string.nav_live), tabs.selected == Tab.LIVE, tabFocus) { tabs.onTab(Tab.LIVE) }
        if (tabs.libraryShown) HeaderTab(stringResource(Res.string.nav_library), tabs.selected == Tab.LIBRARY, tabFocus) { tabs.onTab(Tab.LIBRARY) }
        if (tabs.recordingsShown) HeaderTab(stringResource(Res.string.recordings_title), tabs.selected == Tab.RECORDINGS, tabFocus) { tabs.onTab(Tab.RECORDINGS) }
    }
}

@Composable
private fun HeaderTab(label: String, selected: Boolean, tabFocus: FocusRequester, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { this.selected = selected }.then(if (selected) Modifier.focusRequester(tabFocus) else Modifier).tvFocus(CircleShape),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

/** App bar above the guide and the library, with the TV's [tabs], inside the overscan margins; the mark sits within the buttons' height so no grid row is lost. */
@Composable
private fun Header(
    refreshing: Boolean,
    notice: String?,
    onSettings: (() -> Unit)?,
    onRefresh: () -> Unit,
    settingsFocus: FocusRequester,
    tabs: TabsModel? = null,
    tabFocus: FocusRequester = remember { FocusRequester() },
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().screenPadding(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoMark(40.dp)
        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        tabs?.let { HeaderTabs(it, tabFocus) }
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
        onSettings?.let { SofaButton(onClick = it, modifier = Modifier.focusRequester(settingsFocus)) { Text(stringResource(Res.string.settings)) } }
    }
}

private const val NOTICE_MS = 5_000L
