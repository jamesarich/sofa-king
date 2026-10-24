package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.jamesrich.sofaking.data.PairingRequest
import dev.jamesrich.sofaking.data.TopLevelTab
import kotlinx.coroutines.delay

/**
 * A scanned QR code pairs at once. Pairing runs in the ViewModel; its outcome comes back as state, and the shell acts
 * on its [outcome] once, then calls [onOutcomeHandle]: a new server starts over on its guide, a refused code lands in
 * Settings with the reason. [pairing] is claimed once through [onPairingHandle] and paired through [onPair].
 */
@Composable
internal fun PairingEffects(
    shell: AppShellState,
    pairing: PairingRequest?,
    outcome: PairingOutcome?,
    onPairingHandle: () -> Unit,
    onPair: (serverUrl: String, code: String) -> Unit,
    onOutcomeHandle: () -> Unit,
) {
    val currentOnPairingHandle by rememberUpdatedState(onPairingHandle)
    val currentOnPair by rememberUpdatedState(onPair)
    val currentOnOutcomeHandle by rememberUpdatedState(onOutcomeHandle)
    LaunchedEffect(pairing) {
        if (pairing == null) return@LaunchedEffect
        currentOnPairingHandle()
        currentOnPair(pairing.serverUrl, pairing.code)
    }
    LaunchedEffect(outcome) {
        when (outcome ?: return@LaunchedEffect) {
            PairingOutcome.PAIRED -> shell.startOver()
            PairingOutcome.FAILED -> shell.push(Route.Settings)
        }
        currentOnOutcomeHandle()
    }
}

/**
 * The page the app starts on: a Watch Next entry's [playItem], claimed once through [onPlayItemHandle], or the cold
 * start's own [launch]; and the open tab, kept through [onLastTab] when it is not the [lastTab] already kept.
 */
@Composable
internal fun LaunchEffects(
    shell: AppShellState,
    launch: LaunchTarget,
    lastTab: TopLevelTab?,
    playItem: String?,
    onPlayItemHandle: () -> Unit,
    onLastTab: (TopLevelTab) -> Unit,
) {
    val currentOnPlayItemHandle by rememberUpdatedState(onPlayItemHandle)
    val currentLastTab by rememberUpdatedState(lastTab)
    val currentOnLastTab by rememberUpdatedState(onLastTab)
    // A Watch Next entry plays its item at once, in place of the cold start's own page.
    LaunchedEffect(playItem) {
        if (playItem == null) return@LaunchedEffect
        currentOnPlayItemHandle()
        shell.playItem(playItem)
    }
    // A cold start opens the channel last watched, full screen, once the lineup and settings are in. Decided
    // once per process (restored across configuration changes), and before the guide composes, so the preview
    // never opens a session that the full screen is about to replace.
    LaunchedEffect(launch, shell.launchDecided) { shell.decideLaunch(launch) }
    // The open tab is remembered for the next start, once this start has picked its own.
    val tab = shell.nav.tab
    LaunchedEffect(tab, shell.launchDecided) {
        if (shell.launchDecided && currentLastTab != tab.topLevel) currentOnLastTab(tab.topLevel)
    }
}

/**
 * What the shell follows as pages change: a tab the server does not [offered] gives way to Live, the sleep timer and
 * the notice run out, and a channel watched long enough joins Recent through [onRecent].
 */
@Composable
internal fun ShellEffects(shell: AppShellState, offered: TabsOffered, onRecent: (guideId: String) -> Unit) {
    val currentOnRecent by rememberUpdatedState(onRecent)
    val nav = shell.nav
    val top = nav.top
    // A tab the server turns out not to offer, or no longer offers, gives way to Live.
    LaunchedEffect(nav.tab, offered) {
        val withdrawn = when (nav.tab) {
            Tab.LIBRARY -> !offered.library
            Tab.RECORDINGS -> !offered.recordings
            else -> false
        }
        if (withdrawn) shell.nav = shell.nav.open(Tab.LIVE)
    }
    // The sleep timer runs only while a full-screen player is open; leaving for any other page turns it off.
    LaunchedEffect(top.playsFullScreen) {
        if (!top.playsFullScreen) shell.sleep.cancel()
    }
    // A short word from the player on its way back to the guide: the channel it was asked for is gone.
    LaunchedEffect(shell.notice) {
        if (shell.notice == null) return@LaunchedEffect
        delay(NOTICE_MS)
        shell.notice = null
    }
    // The channel joins Recent, and becomes the one a cold start opens, once it has played for RECENT_AFTER_MS.
    val playing = shell.history.watching.takeIf { top is Route.Player }
    LaunchedEffect(playing) {
        if (playing == null) return@LaunchedEffect
        delay(RECENT_AFTER_MS)
        currentOnRecent(playing)
    }
}

private const val NOTICE_MS = 5_000L
