package dev.jamesrich.sofaking

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
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
import dev.jamesrich.sofaking.ui.LocalAppLifecycleOwner
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
import dev.jamesrich.sofaking.ui.SofaLoaderLabel
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.guide.GuideScreen
import dev.jamesrich.sofaking.ui.guide.GuideShortcut
import dev.jamesrich.sofaking.ui.guide.guideShortcut
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    val recordingsState by recordingsVm.state.collectAsStateWithLifecycle()
    // Where the viewer is: a back stack per tab, shown by Navigation 3. It survives a restore; the one-shot
    // arrival details beside it do not.
    var nav by rememberSerializable { mutableStateOf(Navigation()) }
    val top = nav.top
    // The Library tab shows when the server's bootstrap says it serves libraries and it has at least one, and the
    // Recordings tab when it records. A start on either shows its tab before the bootstrap answers.
    val libraryShown = (capabilities.library || nav.tab == Tab.LIBRARY) && when (val home = libraryHome) {
        LibraryHomeState.Unavailable -> false
        is LibraryHomeState.Ready -> home.home.libraries.isNotEmpty()
        else -> true
    }
    val recordingsShown = capabilities.recordings || nav.tab == Tab.RECORDINGS && recordingsState != RecordingsState.Unavailable
    // A tab the server turns out not to offer, or no longer offers, gives way to Live.
    LaunchedEffect(nav.tab, libraryShown, recordingsShown) {
        if (nav.tab == Tab.LIBRARY && !libraryShown || nav.tab == Tab.RECORDINGS && !recordingsShown) nav = nav.open(Tab.LIVE)
    }
    // Leaving Settings lands on the page it was opened from, with focus on the header's open tab, as the Settings
    // button it left from sits beside it.
    var focusTabOnReturn by remember { mutableStateOf(false) }
    val leaveSettings = {
        focusTabOnReturn = true
        nav = nav.closeSettings()
    }
    val pairingScope = rememberCoroutineScope()
    // Why the last pairing attempt failed; Settings shows it until the next attempt.
    var pairingFailure by remember { mutableStateOf<PairingFailure?>(null) }
    val pair: (String, String) -> Unit = { url, code ->
        pairingScope.launch {
            pairingFailure = vm.pair(url, code)
            nav = if (pairingFailure == null) Navigation() else nav.push(Route.Settings)
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
        when (launch) {
            is LaunchTarget.Player -> nav = Navigation().push(Route.Player(launch.guideId))
            is LaunchTarget.OpenTab -> nav = Navigation().open(launch.tab)
            else -> Unit
        }
    }
    // The open tab is remembered for the next start, once this start has picked its own.
    LaunchedEffect(nav.tab, launchDecided) {
        if (launchDecided && settings?.lastTab != nav.tab.topLevel) vm.setLastTab(nav.tab.topLevel)
    }
    // Back one page. The player hands the guide the channel it was left on, which may not be the one it opened.
    val back = { nav = nav.pop(if (nav.top is Route.Player) history.watching else nav.top.focusKey) }
    // From the player to the guide by its Guide or Search key: the guide opens at Now or with search open.
    val toGuide = { arrival: Arrival -> nav = nav.pop(history.watching).let { it.copy(arrival = arrival.copy(returnedFrom = it.arrival.returnedFrom)) } }

    // One player for the guide preview and the full screen, so OK on the previewed channel continues its stream,
    // and one server session behind it, promoted from preview to watch in place.
    val captionSync = remember { CaptionSync() }
    val player = rememberLivePlayer(captionSync)
    val sessions = vm.sessions
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
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
    MatchDisplayFrameRate(player, enabled = playback?.matchFrameRate == true && top.playsFullScreen)
    // The preview starts without a caption lead; the full screen sets each channel's own. Settings may change
    // the server, so it releases the session outright rather than keeping it to reopen.
    LaunchedEffect(top) {
        if (!top.isSettings) settingsUnlocked = false
        when (top) {
            Route.Guide -> captionSync.advanceUs = 0L
            Route.Settings -> {
                player.stop()
                sessions.release(forget = true)
            }
            is Route.Player, is Route.Vod, is Route.Recording -> Unit
            // The library and the recordings play nothing until one is picked, so the guide's preview stops on the way in.
            Route.Library, is Route.LibraryGrid, is Route.LibrarySearch, is Route.LibraryItem, Route.Recordings -> {
                player.stop()
                sessions.release(forget = true)
            }
            // Reached from Settings, which already stopped the stream.
            Route.Channels, Route.StartupChannel -> Unit
        }
    }
    // Stopped, not paused: the TV's one hardware decoder and the server's session go free while another app is
    // in front. The item and playWhenReady survive; on return a fresh session is opened for the same channel
    // and intent and the item is prepared at its URL, at the live edge or still paused. A library item's screen
    // reopens it itself, where it was, and the library's other pages hold no stream.
    val currentTop by rememberUpdatedState(top)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player.stop()
        sessions.releaseAsync()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (currentTop.playsLive && player.hasItem && player.status.value.state == PlaybackState.IDLE) {
            captionSync.effectiveUs = captionSync.advanceUs
            scope.launch { resume(player, sessions) }
        }
    }

    // The channel joins Recent, and becomes the one a cold start opens, once it has played for RECENT_AFTER_MS.
    val playing = history.watching.takeIf { top is Route.Player }
    LaunchedEffect(playing) {
        if (playing == null) return@LaunchedEffect
        delay(RECENT_AFTER_MS)
        vm.setLastChannel(playing)
    }

    val tv = LocalLayoutProfile.current.tv
    val settingsFocus = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    val tabs = TabsModel(nav.tab, libraryShown, recordingsShown) { picked ->
        // The library's shelves are read again each time its tab is opened, as the recordings are.
        if (picked == Tab.LIBRARY && nav.tab != Tab.LIBRARY) libraryVm.refreshHome()
        nav = nav.closeSettings().open(picked, if (picked == Tab.LIVE) Arrival(returnedFrom = history.watching) else Arrival())
    }
    val openSettings = { nav = nav.push(Route.Settings) }
    // Back from another tab's pill: Live opens with focus kept on its pill, so one more Back exits.
    val backToLive = {
        focusTabOnReturn = true
        nav = nav.open(Tab.LIVE, Arrival(returnedFrom = history.watching, inHeader = true))
    }
    // The Guide key from the Library or Recordings pages: the guide at Now, on the channel last watched.
    val guideFromElsewhere = { nav = nav.open(Tab.LIVE, Arrival(returnedFrom = history.watching, atNow = true)) }

    // The page for a route, built fresh on each recomposition so it reads the current state; the entries
    // Navigation 3 keeps call through this, never a copy captured when the page was opened.
    val page by rememberUpdatedState<@Composable (Route) -> Unit> { route ->
        val arrival = if (route == nav.top) nav.arrival else Arrival()
        when (val current = route) {
            Route.Settings, Route.Channels, Route.StartupChannel -> if (settingsLocked) settings?.let { s ->
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
                Route.Settings -> settings?.let { s ->
                    SettingsScreen(
                        settings = s,
                        onSave = { url, token -> vm.saveServer(url, token); nav = Navigation() },
                        onBack = leaveSettings,
                        channelCount = lineup?.channels?.size ?: 0,
                        channels = lineup?.channels.orEmpty(),
                        libraryOffered = libraryShown,
                        recordingsOffered = recordingsShown,
                        reloading = loading,
                        actions = SettingsActions(
                            onCaptionsChanged = { vm.setCaptionsEnabled(it) },
                            onCaptionAdvanceChanged = { vm.setCaptionAdvanceMs(it) },
                            onSmoothCaptionsChanged = { vm.setSmoothCaptions(it) },
                            onCaptionStyleChanged = { vm.setCaptionStyle(it) },
                            onAutoOpenDelayChanged = { vm.setAutoOpenDelay(it) },
                            onPreviewSoundChanged = { vm.setPreviewSound(it) },
                            onStartupChanged = { target, guideId -> vm.setStartup(target, guideId) },
                            onChooseStartupChannel = { nav = nav.push(Route.StartupChannel) },
                            onClockFormatChanged = { vm.setClockFormat(it) },
                            onGuideDensityChanged = { vm.setGuideDensity(it) },
                            onManageChannels = { nav = nav.push(Route.Channels) },
                            onMatchFrameRateChanged = { vm.setMatchFrameRate(it) },
                            onDecoderChanged = { vm.setDecoder(it) },
                            onBufferChanged = { vm.setBuffer(it) },
                            onCheckForUpdate = { version, again -> vm.checkForUpdate(version, again) },
                            onSkipCommercialsChanged = { vm.setSkipCommercials(it) },
                            onReload = {
                                vm.refresh()
                                libraryVm.refreshHome()
                            },
                            onSettingsPinChanged = { pin ->
                                vm.setSettingsPin(pin)
                                // The page that set the PIN stays open; the lock takes effect on the next visit.
                                settingsUnlocked = true
                            },
                        ),
                        pairing = PairingUi(busy = pairingBusy, failure = pairingFailure, onPair = pair),
                        update = update,
                        returnedFrom = arrival.returnedFrom,
                    )
                }
                Route.Channels -> settings?.let { s ->
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
                        onBack = back,
                    )
                }
                Route.StartupChannel -> settings?.let { s ->
                    ChannelPickerScreen(
                        channels = lineup?.channels.orEmpty(),
                        selected = s.startupChannel,
                        onPick = { channel ->
                            vm.setStartup(StartupTarget.CHANNEL, channel.guideId)
                            back()
                        },
                        onBack = back,
                    )
                }
                else -> Unit
            }
            is Route.Player -> if (lineup != null && visibleLineup != null) {
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
                    onExit = back,
                    onGuide = { toGuide(Arrival(atNow = true)) },
                    onSearch = { toGuide(Arrival(search = true)) },
                    onChannelChanged = { history = history.tuned(it.guideId) },
                    reloadLineup = vm::reloadLineup,
                    onChannelGone = { channel ->
                        notice = formatTemplate(channelGone, channel.label())
                        back()
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
                LaunchedEffect(Unit) { back() }
            }
            is Route.Vod -> {
                val detail by remember(current.itemId) { libraryVm.detail(current.itemId) }.collectAsStateWithLifecycle()
                val item = (detail as? ItemDetailState.Ready)?.detail?.item
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
                            onExit = back,
                            upNext = { libraryVm.upNext(item)?.also { next = it }?.upNext() },
                            onPlayNext = { next?.let { nav = nav.replaceTop(Route.Vod(it.id)) } },
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
            is Route.Recording -> {
                val recordings by recordingsVm.state.collectAsStateWithLifecycle()
                // Kept once found, so a list reload while it plays never swaps the title or markers under the player.
                val recording = remember(current.recordingId, recordings is RecordingsState.Ready) { recordingsVm.recording(current.recordingId) }
                when {
                    recording != null -> VodPlayerScreen(
                        title = recording.vodTitle(),
                        player = player,
                        sessions = sessions,
                        open = { recordingsVm.play(recording, player.playbackProfile, current.fromStart) },
                        onPosition = { position, duration -> recordingsVm.progress(recording.id, position, duration) },
                        onStopped = { position, duration -> recordingsVm.stopped(recording.id, position, duration) },
                        onExit = back,
                        autoSkipBreaks = settings?.skipCommercials ?: false,
                    )
                    recordings is RecordingsState.Ready || recordings is RecordingsState.Failed -> LaunchedEffect(Unit) { back() }
                    else -> MessageScreen(stringResource(Res.string.loading), loading = true)
                }
            }
            is Route.LibraryItem -> {
                val detail by remember(current.itemId) { libraryVm.detail(current.itemId) }.collectAsStateWithLifecycle()
                val ready = (detail as? ItemDetailState.Ready)?.detail
                // The season shown under a show: the viewer's pick, else the one with the next episode.
                var pickedSeason by rememberSaveable(current.itemId) { mutableStateOf<String?>(null) }
                val seasonId = pickedSeason ?: ready?.let(::initialSeasonId)
                val season = seasonId?.let { id -> remember(id) { libraryVm.detail(id) }.collectAsStateWithLifecycle().value }
                ItemDetailsScreen(
                    state = detail,
                    season = season,
                    selectedSeasonId = seasonId,
                    actions = ItemDetailsActions(
                        onPlay = { item, fromStart -> nav = nav.push(Route.Vod(item.id, fromStart)) },
                        onSetWatched = libraryVm::setWatched,
                        onSelectSeason = { pickedSeason = it },
                        onRetry = { libraryVm.detail(current.itemId, refresh = true) },
                        onPerson = { nav = nav.push(Route.LibrarySearch(query = it)) },
                        onOpenShow = { nav = nav.push(Route.LibraryItem(it)) },
                    ),
                    returnedFrom = arrival.returnedFrom,
                )
            }
            Route.Guide, Route.Library, is Route.LibraryGrid, is Route.LibrarySearch, Route.Recordings -> TabPage(
                tv = tv,
                tabs = tabs,
                tabFocus = tabFocus,
                settingsFocus = settingsFocus,
                focusTabOnReturn = focusTabOnReturn,
                onTabFocusRequested = { focusTabOnReturn = false },
                refreshing = if (nav.tab == Tab.LIVE) loading && state is LineupState.Ready else false,
                notice = notice,
                onSettings = openSettings,
                onBackToLive = backToLive,
            ) { showsTabs, tabBack ->
                when (current) {
                    Route.Guide -> {
                        // With no server to talk to, Settings is the only thing to do, so it takes focus.
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
                                startAtNow = arrival.atNow,
                                startSearch = arrival.search,
                                returnedFrom = arrival.returnedFrom,
                                onPlay = { guideId, scope -> nav = nav.push(Route.Player(guideId, scope)) },
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
                                startInHeader = arrival.inHeader && showsTabs,
                            )
                        }
                    }
                    Route.Library -> {
                        BackHandler(onBack = tabBack)
                        LibraryHomeScreen(
                            state = libraryHome,
                            actions = LibraryHomeActions(
                                onOpenItem = { nav = nav.push(Route.LibraryItem(it.id)) },
                                onPlayItem = { nav = nav.push(Route.Vod(it.id)) },
                                onOpenLibrary = { nav = nav.push(Route.LibraryGrid(it.id)) },
                                onRetry = libraryVm::refreshHome,
                                onSearch = { voice -> nav = nav.push(Route.LibrarySearch(), Arrival(search = voice)) },
                                menu = CardMenu(
                                    onPlayFromStart = { nav = nav.push(Route.Vod(it.id, fromStart = true)) },
                                    onSetWatched = libraryVm::setWatched,
                                ),
                            ),
                            returnedFrom = arrival.returnedFrom,
                        )
                    }
                    Route.Recordings -> {
                        val recordings by recordingsVm.state.collectAsStateWithLifecycle()
                        LaunchedEffect(Unit) { recordingsVm.refresh() }
                        RecordingsScreen(
                            state = recordings,
                            onPlay = { recording, fromStart -> nav = nav.push(Route.Recording(recording.id, fromStart)) },
                            onDelete = recordingsVm::delete,
                            onRetry = recordingsVm::refresh,
                            onBack = tabBack,
                            onRuleChange = { rule, newOnly, keep -> recordingsVm.updateRule(rule, newOnly, keep) },
                            onRuleDelete = recordingsVm::deleteRule,
                        )
                    }
                    // The query outlives a trip to an item's page and back, in the entry's saved state.
                    is Route.LibrarySearch -> LibrarySearchScreen(
                        actions = LibrarySearchActions(
                            search = libraryVm::search,
                            onOpenItem = { nav = nav.push(Route.LibraryItem(it.id)) },
                            onPlayItem = { nav = nav.push(Route.Vod(it.id)) },
                            menu = CardMenu(
                                onPlayFromStart = { nav = nav.push(Route.Vod(it.id, fromStart = true)) },
                                onSetWatched = libraryVm::setWatched,
                            ),
                        ),
                        voice = arrival.search,
                        returnedFrom = arrival.returnedFrom,
                        initialQuery = current.query,
                    )
                    is Route.LibraryGrid -> {
                        LaunchedEffect(current.libraryId) {
                            val open = grid
                            libraryVm.openGrid(current.libraryId, open?.sort ?: LibrarySort.TITLE, open?.unwatched ?: false, open?.takeIf { it.libraryId == current.libraryId }?.genre)
                        }
                        val shown = grid?.takeIf { it.libraryId == current.libraryId }
                        LibraryGridScreen(
                            library = (libraryHome as? LibraryHomeState.Ready)?.home?.libraries?.firstOrNull { it.id == current.libraryId },
                            grid = shown,
                            actions = LibraryGridActions(
                                onSort = { libraryVm.openGrid(current.libraryId, it, shown?.unwatched ?: false, shown?.genre) },
                                onUnwatched = { libraryVm.openGrid(current.libraryId, shown?.sort ?: LibrarySort.TITLE, it, shown?.genre) },
                                onGenre = { libraryVm.openGrid(current.libraryId, shown?.sort ?: LibrarySort.TITLE, shown?.unwatched ?: false, it) },
                                onOpenItem = { nav = nav.push(Route.LibraryItem(it.id)) },
                                onPlayItem = { nav = nav.push(Route.Vod(it.id)) },
                                onLoadMore = libraryVm::loadMore,
                                menu = CardMenu(
                                    onPlayFromStart = { nav = nav.push(Route.Vod(it.id, fromStart = true)) },
                                    onSetWatched = libraryVm::setWatched,
                                ),
                            ),
                            returnedFrom = arrival.returnedFrom,
                        )
                    }
                    else -> Unit
                }
            }
        }
    }

    // One back stack per tab, each with its own saved state, so a tab opens again where it was left. Each page
    // keeps its entry's own lifecycle; the players read the app's (LocalAppLifecycleOwner) to save their place and
    // free the stream only when the app leaves the foreground, not on every Back.
    val entryProvider: (Route) -> NavEntry<Route> = { route -> NavEntry(route, contentKey = route.id) { page(route) } }
    val entries = Tab.entries.associateWith { tab ->
        rememberDecoratedNavEntries(nav.stack(tab), listOf(rememberSaveableStateHolderNavEntryDecorator()), entryProvider)
    }
    // A TV cuts between pages: focus lands at once and nothing slides under the remote. Elsewhere pages cross-fade
    // on the theme's effects spring.
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val cut: AnimatedContentTransitionScope<Scene<Route>>.() -> ContentTransform = {
        if (tv) ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
        else ContentTransform(fadeIn(fade), fadeOut(fade), sizeTransform = null)
    }

    CompositionLocalProvider(
        LocalClockFormat provides (settings?.clockFormat ?: ClockFormat.AUTO),
        LocalGuideDensity provides (settings?.guideDensity ?: GuideDensity.COMFORTABLE),
        LocalAppLifecycleOwner provides lifecycleOwner,
    ) {
        TopLevel(
            tv = tv,
            tabs = tabs,
            shown = !top.playsFullScreen,
            settingsOpen = top.isSettings,
            onSettings = openSettings,
            settingsFocus = settingsFocus,
        ) {
            NavDisplay(
                entries = entries.getValue(Tab.LIVE) + if (nav.tab == Tab.LIVE) emptyList() else entries.getValue(nav.tab),
                modifier = Modifier.onPreviewKeyEvent { event ->
                    // Guide (or Menu) means live TV from any page a viewer browses; the players and Settings keep their keys.
                    val browsing = nav.tab != Tab.LIVE && !nav.top.playsFullScreen && !nav.top.isSettings
                    if (!browsing || event.key.guideShortcut() != GuideShortcut.NOW) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) guideFromElsewhere()
                    true
                },
                onBack = back,
                transitionSpec = cut,
                popTransitionSpec = cut,
                predictivePopTransitionSpec = { cut() },
            )
        }
    }
}

/**
 * A page under the tabs: the header above [content], with the TV's tabs in it. [content] gets whether the header
 * shows the tabs, and what Back does on a tab's first page: climb to the header's open tab, then open Live.
 */
@Composable
private fun TabPage(
    tv: Boolean,
    tabs: TabsModel,
    tabFocus: FocusRequester,
    settingsFocus: FocusRequester,
    focusTabOnReturn: Boolean,
    onTabFocusRequested: () -> Unit,
    refreshing: Boolean,
    notice: String?,
    onSettings: () -> Unit,
    onBackToLive: () -> Unit,
    content: @Composable (showsTabs: Boolean, tabBack: () -> Unit) -> Unit,
) {
    val showsTabs = tv
    // Keyed on nothing, so clearing the flag does not cancel the request; it runs on each return here.
    LaunchedEffect(Unit) {
        if (!focusTabOnReturn) return@LaunchedEffect
        if (showsTabs) requestFocusWhenAttached(tabFocus)
        onTabFocusRequested()
    }
    var headerFocused by remember { mutableStateOf(false) }
    val tabBack: () -> Unit = {
        when (tabBackAction(hasHeader = showsTabs, headerFocused = headerFocused)) {
            TabBack.FOCUS_HEADER -> tabFocus.requestFocus()
            TabBack.OPEN_LIVE -> onBackToLive()
        }
    }
    Column {
        Header(
            modifier = Modifier.onFocusChanged { headerFocused = it.hasFocus },
            refreshing = refreshing,
            notice = notice,
            onSettings = onSettings.takeIf { showsTabs },
            settingsFocus = settingsFocus,
            tabs = tabs.takeIf { showsTabs },
            tabFocus = tabFocus,
        )
        content(showsTabs, tabBack)
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
    startInHeader: Boolean,
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
            startInHeader = startInHeader,
        )
    }
}

/** Which destination is open, whether Library and Recordings are offered, and what picking one does. */
private class TabsModel(val selected: Tab, val libraryShown: Boolean, val recordingsShown: Boolean, val onTab: (Tab) -> Unit)

/**
 * Live, Library, Recordings and Settings around [content]. A TV keeps them in the header, so the guide loses no
 * width and Up from its chips reaches them as it reaches the header's buttons; [TabPage] draws them there.
 * Elsewhere Material's navigation suite puts them in a short bar on a compact window and a rail on a larger one,
 * and slides it away while [shown] is false, over the full-screen players.
 */
@Composable
private fun TopLevel(
    tv: Boolean,
    tabs: TabsModel,
    shown: Boolean,
    settingsOpen: Boolean,
    onSettings: () -> Unit,
    settingsFocus: FocusRequester,
    content: @Composable () -> Unit,
) {
    if (tv) {
        content()
        return
    }
    val type = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfoV2())
    val state = rememberNavigationSuiteScaffoldState(if (shown) NavigationSuiteScaffoldValue.Visible else NavigationSuiteScaffoldValue.Hidden)
    LaunchedEffect(shown) { if (shown) state.show() else state.hide() }
    NavigationSuiteScaffold(
        navigationItems = {
            NavigationSuiteItem(
                selected = !settingsOpen && tabs.selected == Tab.LIVE,
                onClick = { tabs.onTab(Tab.LIVE) },
                icon = { Icon(PlayerIcons.LiveTv, contentDescription = null) },
                label = { Text(stringResource(Res.string.nav_live)) },
                navigationSuiteType = type,
            )
            if (tabs.libraryShown) {
                NavigationSuiteItem(
                    selected = !settingsOpen && tabs.selected == Tab.LIBRARY,
                    onClick = { tabs.onTab(Tab.LIBRARY) },
                    icon = { Icon(PlayerIcons.VideoLibrary, contentDescription = null) },
                    label = { Text(stringResource(Res.string.nav_library)) },
                    navigationSuiteType = type,
                )
            }
            if (tabs.recordingsShown) {
                NavigationSuiteItem(
                    selected = !settingsOpen && tabs.selected == Tab.RECORDINGS,
                    onClick = { tabs.onTab(Tab.RECORDINGS) },
                    icon = { Icon(PlayerIcons.Recordings, contentDescription = null) },
                    label = { Text(stringResource(Res.string.recordings_title)) },
                    navigationSuiteType = type,
                )
            }
            NavigationSuiteItem(
                selected = settingsOpen,
                onClick = { if (!settingsOpen) onSettings() },
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                label = { Text(stringResource(Res.string.nav_settings)) },
                modifier = Modifier.focusRequester(settingsFocus),
                navigationSuiteType = type,
            )
        },
        navigationSuiteType = type,
        containerColor = MaterialTheme.colorScheme.background,
        state = state,
        content = content,
    )
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
        // Status sits right of the tabs in whatever Settings leaves, so a long notice ellipsizes rather than
        // squeezing the button.
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
                SofaLoaderLabel(stringResource(Res.string.refreshing))
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
        // Settings is the header's one button; the lineup, library and recordings reload by themselves, and
        // Settings › Server has Reload.
        onSettings?.let {
            SofaButton(onClick = it, modifier = Modifier.focusRequester(settingsFocus)) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(Res.string.settings))
            }
        }
    }
}

private const val NOTICE_MS = 5_000L
