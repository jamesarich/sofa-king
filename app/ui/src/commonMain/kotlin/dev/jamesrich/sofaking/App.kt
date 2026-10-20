package dev.jamesrich.sofaking

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withStateAtLeast
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import coil3.compose.setSingletonImageLoaderFactory
import dev.jamesrich.sofaking.data.ClockFormat
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.data.PairingRequest
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.ui.LocalAppLifecycleOwner
import dev.jamesrich.sofaking.ui.LocalClockFormat
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.MatchDisplayFrameRate
import dev.jamesrich.sofaking.ui.guide.GuideShortcut
import dev.jamesrich.sofaking.ui.guide.LocalGuideDensity
import dev.jamesrich.sofaking.ui.guide.guideShortcut
import dev.jamesrich.sofaking.ui.player.CaptionSync
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.PlaybackState
import dev.jamesrich.sofaking.ui.player.rememberLivePlayer
import dev.jamesrich.sofaking.ui.rememberLastInput
import dev.jamesrich.sofaking.ui.rememberLayoutProfile
import dev.jamesrich.sofaking.ui.sofaKingImageLoader
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import dev.jamesrich.sofaking.ui.trackLastInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The whole app, the same on every platform: the theme, the window's layout profile and input, then the screen the
 * user is on. [pairing] is a server to pair with from a `sofaking://pair` link the platform received; the app claims
 * it once and calls [onPairingHandled], so a restored activity does not claim it again. [onReady] runs once the first
 * page is chosen, which is when Android lets its splash screen go.
 */
@Composable
fun App(pairing: PairingRequest? = null, onPairingHandled: () -> Unit = {}, onReady: () -> Unit = {}) {
    setSingletonImageLoaderFactory(::sofaKingImageLoader)
    SofaKingTheme {
        val lastInput = rememberLastInput()
        CompositionLocalProvider(LocalLayoutProfile provides rememberLayoutProfile(), LocalLastInput provides lastInput) {
            Surface(Modifier.fillMaxSize().trackLastInput(lastInput), color = MaterialTheme.colorScheme.background) { SofaKingApp(pairing = pairing, onPairingHandled = onPairingHandled, onReady = onReady) }
        }
    }
}

/**
 * The shell: the ViewModels, the one shared player and its session's stop/start lifecycle, the cold-start decision,
 * the tabs and the back stacks Navigation 3 shows. Each page is drawn by its destination (LiveDestinations,
 * LibraryDestinations, RecordingsDestinations, SettingsDestinations), which reads the state it shows itself.
 */
@Composable
private fun SofaKingApp(pairing: PairingRequest? = null, onPairingHandled: () -> Unit = {}, onReady: () -> Unit = {}) {
    val vm = viewModel { appGraph.lineupViewModel() }
    val libraryVm = viewModel { appGraph.libraryViewModel() }
    val recordingsVm = viewModel { appGraph.recordingsViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val pairingState by vm.pairing.collectAsStateWithLifecycle()
    val libraryHome by libraryVm.home.collectAsStateWithLifecycle()
    val recordingsState by recordingsVm.state.collectAsStateWithLifecycle()
    val shell = rememberAppShellState()
    val nav = shell.nav
    val top = nav.top
    val offered = tabsOffered(capabilities, nav.tab, libraryHome, recordingsState)
    // A tab the server turns out not to offer, or no longer offers, gives way to Live.
    LaunchedEffect(nav.tab, offered) {
        if (nav.tab == Tab.LIBRARY && !offered.library || nav.tab == Tab.RECORDINGS && !offered.recordings) shell.nav = shell.nav.open(Tab.LIVE)
    }
    // A scanned QR code pairs at once. Pairing runs in the ViewModel; its outcome comes back as state, and the shell
    // acts on it once: a new server starts over on its guide, a refused code lands in Settings with the reason.
    LaunchedEffect(pairing) {
        if (pairing == null) return@LaunchedEffect
        onPairingHandled()
        vm.pair(pairing.serverUrl, pairing.code)
    }
    LaunchedEffect(pairingState.outcome) {
        when (pairingState.outcome ?: return@LaunchedEffect) {
            PairingOutcome.PAIRED -> shell.startOver()
            PairingOutcome.FAILED -> shell.push(Route.Settings)
        }
        vm.pairingHandled()
    }
    // A short word from the player on its way back to the guide: the channel it was asked for is gone.
    LaunchedEffect(shell.notice) {
        if (shell.notice == null) return@LaunchedEffect
        delay(NOTICE_MS)
        shell.notice = null
    }
    // A cold start opens the channel last watched, full screen, once the lineup and settings are in. Decided
    // once per process (restored across configuration changes), and before the guide composes, so the preview
    // never opens a session that the full screen is about to replace.
    val launch = launchTarget(state, settings)
    LaunchedEffect(launch, shell.launchDecided) { shell.decideLaunch(launch) }
    val currentOnReady by rememberUpdatedState(onReady)
    LaunchedEffect(shell.launchDecided) { if (shell.launchDecided) currentOnReady() }
    // The open tab is remembered for the next start, once this start has picked its own.
    LaunchedEffect(nav.tab, shell.launchDecided) {
        if (shell.launchDecided && settings?.lastTab != nav.tab.topLevel) vm.setLastTab(nav.tab.topLevel)
    }

    // One player for the guide preview and the full screen, so OK on the previewed channel continues its stream,
    // and one server session behind it, promoted from preview to watch in place.
    val captionSync = remember { CaptionSync() }
    val player = rememberLivePlayer(captionSync)
    val sessions = vm.sessions
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
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
        shell.relockUnlessInSettings()
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
    val playing = shell.history.watching.takeIf { top is Route.Player }
    LaunchedEffect(playing) {
        if (playing == null) return@LaunchedEffect
        delay(RECENT_AFTER_MS)
        vm.setLastChannel(playing)
    }

    val tv = LocalLayoutProfile.current.tv
    val settingsFocus = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    val tabs = TabsModel(nav.tab, offered.library, offered.recordings) { picked ->
        // The library's shelves are read again each time its tab is opened, as the recordings are.
        if (picked == Tab.LIBRARY && shell.nav.tab != Tab.LIBRARY) libraryVm.refreshHome()
        shell.openTab(picked)
    }
    val openSettings = { shell.push(Route.Settings) }

    // The page for a route, built fresh on each recomposition so it reads the current state; the entries
    // Navigation 3 keeps call through this, never a copy captured when the page was opened.
    val page by rememberUpdatedState<@Composable (Route) -> Unit> { route ->
        val arrival = if (route == shell.nav.top) shell.nav.arrival else Arrival()
        when (route) {
            Route.Settings, Route.Channels, Route.StartupChannel -> SettingsDestination(route, arrival, shell, vm, libraryVm, offered)
            is Route.Player -> PlayerDestination(route, shell, vm, player)
            is Route.Vod -> VodDestination(route, shell, vm, libraryVm, player)
            is Route.Recording -> RecordingDestination(route, shell, vm, recordingsVm, player)
            is Route.LibraryItem -> LibraryItemDestination(route, arrival, shell, libraryVm)
            Route.Guide, Route.Library, is Route.LibraryGrid, is Route.LibrarySearch, Route.Recordings -> TabPage(
                tv = tv,
                tabs = tabs,
                tabFocus = tabFocus,
                settingsFocus = settingsFocus,
                headerReturn = shell.headerReturn,
                onHeaderFocusRequested = { shell.headerReturn = null },
                refreshing = if (shell.nav.tab == Tab.LIVE) loading && state is LineupState.Ready else false,
                notice = shell.notice,
                onSettings = openSettings,
                onBackToLive = shell::backToLive,
                focusHeaderIfIdle = (route == Route.Library || route == Route.Recordings) && arrival.returnedFrom == null,
            ) { showsTabs, tabBack, headerFocused ->
                when (route) {
                    Route.Guide -> GuideDestination(arrival, shell, vm, player, settingsFocus, headerFocus = tabFocus.takeIf { showsTabs })
                    Route.Library -> LibraryHomeDestination(arrival, shell, libraryVm, tabBack, headerFocused)
                    Route.Recordings -> RecordingsDestination(arrival, shell, recordingsVm, tabBack, headerFocused)
                    is Route.LibrarySearch -> LibrarySearchDestination(route, arrival, shell, libraryVm)
                    is Route.LibraryGrid -> LibraryGridDestination(route, arrival, shell, libraryVm)
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
                    val browsing = shell.nav.tab != Tab.LIVE && !shell.nav.top.playsFullScreen && !shell.nav.top.isSettings
                    if (!browsing || event.key.guideShortcut() != GuideShortcut.NOW) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) shell.guideFromElsewhere()
                    true
                },
                onBack = shell::back,
                transitionSpec = cut,
                popTransitionSpec = cut,
                predictivePopTransitionSpec = { cut() },
            )
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

private const val NOTICE_MS = 5_000L
