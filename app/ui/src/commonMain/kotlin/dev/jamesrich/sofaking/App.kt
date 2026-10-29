package dev.jamesrich.sofaking

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import coil3.compose.setSingletonImageLoaderFactory
import dev.jamesrich.sofaking.data.AppLink
import dev.jamesrich.sofaking.data.Appearance
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.data.PairingRequest
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.ui.LocalAppLifecycleOwner
import dev.jamesrich.sofaking.ui.LocalInPictureInPicture
import dev.jamesrich.sofaking.ui.LocalKidsMode
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.LocalTimeZone
import dev.jamesrich.sofaking.ui.LocalWindowFullScreen
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.WindowFullScreen
import dev.jamesrich.sofaking.ui.arrowKeysMoveFocus
import dev.jamesrich.sofaking.ui.blocksLetterShortcut
import dev.jamesrich.sofaking.ui.guide.GuideShortcut
import dev.jamesrich.sofaking.ui.guide.LocalGuideDensity
import dev.jamesrich.sofaking.ui.guide.guideShortcut
import dev.jamesrich.sofaking.ui.player.LocalPlayerVolume
import dev.jamesrich.sofaking.ui.player.MiniPlayer
import dev.jamesrich.sofaking.ui.player.MusicPlayback
import dev.jamesrich.sofaking.ui.player.PlayerVolume
import dev.jamesrich.sofaking.ui.player.volumeKey
import dev.jamesrich.sofaking.ui.preview.PREVIEW_ENABLED
import dev.jamesrich.sofaking.ui.rememberAppLifecycleOwner
import dev.jamesrich.sofaking.ui.rememberLastInput
import dev.jamesrich.sofaking.ui.rememberLayoutProfile
import dev.jamesrich.sofaking.ui.rememberPlayerWindow
import dev.jamesrich.sofaking.ui.rememberSystemTimeZone
import dev.jamesrich.sofaking.ui.sofaKingImageLoader
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import dev.jamesrich.sofaking.ui.theme.rememberDarkTheme
import dev.jamesrich.sofaking.ui.togglesFullScreen
import dev.jamesrich.sofaking.ui.trackLastInput
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * The whole app, the same on every platform: the theme, the window's layout profile and input, then the screen the
 * user is on. [pairing] is a server to pair with from a `sofaking://pair` link the platform received; the app claims
 * it once and calls [onPairingHandle], so a restored activity does not claim it again. [link] is a library item or a
 * live channel to play from a `sofaking://vod` or `sofaking://live` link, such as the Android TV home screen's Watch
 * Next row or a home automation's shortcut, handed back the same way through [onLinkHandle]. [windowFullScreen] is
 * the host window's full screen where the app can switch it (a desktop window, a browser page). [onLaunchReady]
 * runs once the cold start knows its first page, which a platform's launch screen waits for. [onDarkThemeChange] is
 * told whether the app draws dark, so the host can set its system bars' icons to match.
 */
@Composable
fun App(
    modifier: Modifier = Modifier,
    pairing: PairingRequest? = null,
    onPairingHandle: () -> Unit = {},
    link: AppLink? = null,
    onLinkHandle: () -> Unit = {},
    windowFullScreen: WindowFullScreen? = null,
    onLaunchReady: () -> Unit = {},
    onDarkThemeChange: (dark: Boolean) -> Unit = {},
    // The same ViewModel the shell takes below; until the settings load, the theme follows the system.
    settingsVm: LineupViewModel = viewModel { appGraph.lineupViewModel() },
) {
    setSingletonImageLoaderFactory(::sofaKingImageLoader)
    val appearance by remember(settingsVm) { settingsVm.settings.map { it?.appearance ?: Appearance.SYSTEM } }
        .collectAsStateWithLifecycle(Appearance.SYSTEM)
    val currentOnDarkThemeChange by rememberUpdatedState(onDarkThemeChange)
    val dark = rememberDarkTheme(appearance)
    LaunchedEffect(dark) { currentOnDarkThemeChange(dark) }
    SofaKingTheme(appearance) {
        val lastInput = rememberLastInput()
        CompositionLocalProvider(
            LocalLayoutProfile provides rememberLayoutProfile(),
            LocalLastInput provides lastInput,
            LocalWindowFullScreen provides windowFullScreen,
        ) {
            Surface(
                modifier.fillMaxSize().trackLastInput(lastInput).arrowKeysMoveFocus(),
                color = MaterialTheme.colorScheme.background,
            ) {
                SofaKingApp(
                    pairing = pairing,
                    onPairingHandle = onPairingHandle,
                    link = link,
                    onLinkHandle = onLinkHandle,
                    onLaunchReady = onLaunchReady,
                )
            }
        }
    }
}

/**
 * The shell: the ViewModels, the one shared player and its session's stop/start lifecycle, the cold-start decision,
 * the tabs and the back stacks Navigation 3 shows. Each page is drawn by its destination ([RouteDestination]).
 */
@Composable
private fun SofaKingApp(
    pairing: PairingRequest? = null,
    onPairingHandle: () -> Unit = {},
    link: AppLink? = null,
    onLinkHandle: () -> Unit = {},
    onLaunchReady: () -> Unit = {},
    vm: LineupViewModel = viewModel { appGraph.lineupViewModel() },
    libraryVm: LibraryViewModel = viewModel { appGraph.libraryViewModel() },
    recordingsVm: RecordingsViewModel = viewModel { appGraph.recordingsViewModel() },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val pairingState by vm.pairing.collectAsStateWithLifecycle()
    val libraryHome by libraryVm.home.collectAsStateWithLifecycle()
    val recordingsState by recordingsVm.state.collectAsStateWithLifecycle()
    val shell = rememberAppShellState()
    val top = shell.nav.top
    val kids = settings?.kidsMode == true
    val offered = tabsOffered(capabilities, shell.nav.tab, libraryHome, recordingsState, kids)
    val saved = settings?.serverUrl
    PairingEffects(shell, pairing, saved, pairingState.outcome, onPairingHandle, vm::pair, vm::pairingHandled)
    val launch = launchTarget(state, settings)
    val lineup = (state as? LineupState.Ready)?.lineup
    LaunchEffects(shell, launch, settings?.lastTab, link, onLinkHandle, lineup, vm::setLastTab, onLaunchReady)
    ShellEffects(shell, offered, vm::setLastChannel)

    val playback = rememberAppPlayback()
    VolumeSaveEffect(playback.volume, settingsRead = settings != null, { vm.settings.value }, vm::setVolume)
    val sessions = vm.sessions
    // The window's; in a browser, held to the tab's visibility and slow to count a flash of
    // it (rememberAppLifecycleOwner).
    val lifecycleOwner = rememberAppLifecycleOwner(LocalLifecycleOwner.current)
    StreamReplacedEffect(playback, sessions, lifecycleOwner.lifecycle)
    PlayerPreferenceEffects(playback.player, settings, top)
    val inPictureInPicture = rememberPlayerWindow(playback.player, active = top.playsFullScreen)
    val guidePlays = PREVIEW_ENABLED && LocalLayoutProfile.current.showsPreviewBand
    val music = rememberMusicPlayback(playback, sessions, libraryVm)
    RouteStreamEffects(shell, top, playback, music, sessions, guidePlays)
    val plays = if (top is Route.Guide) guidePlays else top.playsLive
    ForegroundEffects(playback, music, sessions, lifecycleOwner, plays)

    val chrome = rememberTabChrome(shell, offered, LocalLayoutProfile.current.tv, libraryVm::refreshHome)
    val refreshing = shell.nav.tab == Tab.LIVE && loading && state is LineupState.Ready
    val hideSpoilers = settings?.hideSpoilers ?: false
    // The page for a route, built fresh on each recomposition so it reads the current state; the entries
    // Navigation 3 keeps call through this, never a copy captured when the page was opened.
    val page by rememberUpdatedState<@Composable (Route) -> Unit> { route ->
        RouteDestination(
            route,
            shell,
            vm,
            libraryVm,
            recordingsVm,
            playback.player,
            music,
            offered,
            chrome,
            hideSpoilers,
            refreshing,
        )
    }
    val entries = rememberTabEntries(shell.nav) { route -> page(route) }
    CompositionLocalProvider(
        LocalTimeZone provides rememberSystemTimeZone(lifecycleOwner.lifecycle).value,
        LocalGuideDensity provides (settings?.guideDensity ?: GuideDensity.COMFORTABLE),
        LocalAppLifecycleOwner provides lifecycleOwner,
        LocalInPictureInPicture provides inPictureInPicture,
        LocalPlayerVolume provides playback.volume,
        LocalKidsMode provides kids,
    ) {
        AppNavDisplay(shell, chrome, entries, playback.volume, music)
    }
}

/**
 * One back stack per tab, each with its own saved state, so a tab opens again where it was left. Each page has its
 * own ViewModels, cleared when it leaves the back stack, and keeps its entry's own lifecycle; the players read the
 * app's (LocalAppLifecycleOwner) to save their place and free the stream only when the app leaves the foreground, not
 * on every Back.
 */
@Composable
private fun rememberTabEntries(nav: Navigation, page: @Composable (Route) -> Unit): Map<Tab, List<NavEntry<Route>>> {
    val entryProvider: (Route) -> NavEntry<Route> = { route ->
        NavEntry(route, contentKey = route.id, metadata = panesOf(route)) { page(route) }
    }
    return Tab.entries.associateWith { tab ->
        rememberDecoratedNavEntries(
            nav.stack(tab),
            listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider,
        )
    }
}

/**
 * The tabs around the open tab's back stack, shown over Live's so Back from a tab's root lands on the guide. On a wide
 * window the library's grid and the title picked from it share it ([rememberLibraryPanes]).
 */
@Composable
private fun AppNavDisplay(
    shell: AppShellState,
    chrome: TabChrome,
    entries: Map<Tab, List<NavEntry<Route>>>,
    volume: PlayerVolume,
    music: MusicPlayback,
) {
    val nav = shell.nav
    val top = nav.top
    val cut = pageCut(chrome.tv)
    val pop = pagePop(chrome.tv)
    val windowFullScreen = LocalWindowFullScreen.current
    // Until the cold start is decided no page is composed: one that showed the guide's loader first would cross-fade
    // into the player (or the tab) the start opens, and show the logo twice.
    if (!shell.launchDecided) {
        MessageScreen(stringResource(Res.string.loading), loading = true)
        return
    }
    TopLevel(
        tv = chrome.tv,
        tabs = chrome.tabs,
        shown = !top.hidesTabs,
        settingsOpen = top.isSettings,
        onSettings = chrome.onSettings,
        settingsFocus = chrome.settingsFocus,
        tabFocus = chrome.tabFocus,
    ) {
        Column {
            NavDisplay(
                entries = entries.getValue(Tab.LIVE) +
                    if (nav.tab == Tab.LIVE) emptyList() else entries.getValue(nav.tab),
                modifier = Modifier.weight(1f).appKeys(shell, volume, windowFullScreen).playerVolumeWheel(volume) {
                    shell.nav.top.playsFullScreen
                },
                sceneStrategies = listOf(rememberLibraryPanes(chrome.tv)),
                onBack = shell::back,
                transitionSpec = cut,
                popTransitionSpec = pop,
                predictivePopTransitionSpec = { pop() },
            )
            // The music playing on while the viewer browses; a TV has no room under its pages, so its music shows
            // on Now Playing alone.
            if (!chrome.tv && !top.hidesTabs) MiniPlayer(music, onOpen = { shell.openNowPlaying() })
        }
    }
}

/**
 * A TV cuts between pages: focus lands at once and nothing slides under the remote. Elsewhere pages cross-fade on the
 * theme's effects spring.
 */
@Composable
private fun pageCut(tv: Boolean): AnimatedContentTransitionScope<Scene<Route>>.() -> ContentTransform {
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    return {
        if (tv) {
            ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
        } else {
            ContentTransform(fadeIn(fade), fadeOut(fade), sizeTransform = null)
        }
    }
}

/**
 * Back is a cut on a TV too. Elsewhere the page left shrinks away over the one it returns to, which fades in; a
 * swipe back drives the same transition.
 */
@Composable
private fun pagePop(tv: Boolean): AnimatedContentTransitionScope<Scene<Route>>.() -> ContentTransform {
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    return {
        if (tv) {
            ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
        } else {
            ContentTransform(
                fadeIn(fade),
                fadeOut(fade) + scaleOut(spatial, targetScale = POP_SCALE),
                targetContentZIndex = -1f,
                sizeTransform = null,
            )
        }
    }
}

private const val POP_SCALE = 0.92f

/**
 * The keys the shell takes before any page: full screen for the window where the app can switch it, a player's
 * volume keys, and Guide (or Menu) back to live TV from the pages a viewer browses.
 */
private fun Modifier.appKeys(
    shell: AppShellState,
    volume: PlayerVolume,
    windowFullScreen: WindowFullScreen?,
): Modifier = onPreviewKeyEvent { event ->
    if (event.blocksLetterShortcut()) return@onPreviewKeyEvent false
    if (windowFullScreen != null && event.key.togglesFullScreen(inPlayer = shell.nav.top.playsFullScreen)) {
        if (event.type == KeyEventType.KeyUp) windowFullScreen.toggle()
        return@onPreviewKeyEvent true
    }
    // A player's volume keys, held to repeat; where the system's volume rules they go on to the player.
    val volumeKey = event.key.volumeKey()
    if (volumeKey != null && shell.nav.top.playsFullScreen && volume.adjustable) {
        if (event.type == KeyEventType.KeyDown) volume.press(volumeKey)
        return@onPreviewKeyEvent true
    }
    // Guide (or Menu) means live TV from any page a viewer browses; the
    // players and Settings keep their keys.
    val browsing = shell.nav.tab != Tab.LIVE && !shell.nav.top.playsFullScreen && !shell.nav.top.isSettings
    if (!browsing || event.key.guideShortcut() != GuideShortcut.NOW) return@onPreviewKeyEvent false
    if (event.type == KeyEventType.KeyDown) shell.guideFromElsewhere()
    true
}

/**
 * The mouse wheel or a two-finger scroll over a full screen player steps its volume, up away from the viewer, where
 * the app draws the control. A scroll a panel took (the channel list, a track card) is left alone, and a trackpad's
 * small deltas add up to one step.
 */
private fun Modifier.playerVolumeWheel(volume: PlayerVolume, inPlayer: () -> Boolean): Modifier =
    if (!volume.adjustable) {
        this
    } else {
        pointerInput(volume) {
            var scrolled = 0f
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.takeIf { event.type == PointerEventType.Scroll && inPlayer() }
                        ?.firstOrNull { !it.isConsumed } ?: continue
                    scrolled += change.scrollDelta.y
                    if (abs(scrolled) >= 1f) {
                        volume.step(if (scrolled < 0f) 1 else -1)
                        scrolled = 0f
                    }
                    change.consume()
                }
            }
        }
    }
