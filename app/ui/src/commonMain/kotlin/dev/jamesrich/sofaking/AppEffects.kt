package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.jamesrich.sofaking.data.AppLink
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PairingDecision
import dev.jamesrich.sofaking.data.PairingRequest
import dev.jamesrich.sofaking.data.TopLevelTab
import dev.jamesrich.sofaking.data.decidePairing
import dev.jamesrich.sofaking.data.linkedChannel
import dev.jamesrich.sofaking.data.serverLabel
import dev.jamesrich.sofaking.ui.settings.SwitchServerDialog
import kotlinx.coroutines.delay

/**
 * A scanned QR code pairs at once on a device with no server, or with the server it already uses; a link naming
 * another server waits for a Switch in [SwitchServerDialog] (Cancel drops it). Pairing runs in the ViewModel; its
 * outcome comes back as state, and the shell acts on its [outcome] once, then calls [onOutcomeHandle]: a new server
 * starts over on its guide, a refused code lands in Settings with the reason. [pairing] is claimed once through
 * [onPairingHandle] and paired through [onPair], once [configuredServer] (the saved address, null until settings
 * load) says which of the two it is.
 */
@Composable
internal fun PairingEffects(
    shell: AppShellState,
    pairing: PairingRequest?,
    configuredServer: String?,
    outcome: PairingOutcome?,
    onPairingHandle: () -> Unit,
    onPair: (serverUrl: String, code: String) -> Unit,
    onOutcomeHandle: () -> Unit,
) {
    val currentOnPairingHandle by rememberUpdatedState(onPairingHandle)
    val currentOnPair by rememberUpdatedState(onPair)
    val currentOnOutcomeHandle by rememberUpdatedState(onOutcomeHandle)
    var asking by rememberSaveable(stateSaver = PairingRequestSaver) { mutableStateOf<PairingRequest?>(null) }
    LaunchedEffect(pairing, configuredServer) {
        if (pairing == null || configuredServer == null) return@LaunchedEffect
        currentOnPairingHandle()
        when (decidePairing(configuredServer, pairing)) {
            PairingDecision.PAIR -> currentOnPair(pairing.serverUrl, pairing.code)
            PairingDecision.ASK -> asking = pairing
            PairingDecision.IGNORE -> Unit
        }
    }
    asking?.let { request ->
        SwitchServerDialog(
            requested = serverLabel(request.serverUrl),
            current = serverLabel(configuredServer.orEmpty()),
            onSwitch = {
                asking = null
                currentOnPair(request.serverUrl, request.code)
            },
            onCancel = { asking = null },
        )
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
 * The page the app starts on: a `sofaking://` [link]'s item or channel, claimed once through [onLinkHandle] (a channel
 * once [lineup] lists it), or the cold start's own [launch]; and the open tab, kept through [onLastTab] when it is not
 * the [lastTab] already kept.
 */
@Composable
internal fun LaunchEffects(
    shell: AppShellState,
    launch: LaunchTarget,
    lastTab: TopLevelTab?,
    link: AppLink?,
    onLinkHandle: () -> Unit,
    lineup: Lineup?,
    onLastTab: (TopLevelTab) -> Unit,
    onLaunchReady: () -> Unit,
) {
    val currentOnLaunchReady by rememberUpdatedState(onLaunchReady)
    val currentOnLinkHandle by rememberUpdatedState(onLinkHandle)
    val currentLastTab by rememberUpdatedState(lastTab)
    val currentOnLastTab by rememberUpdatedState(onLastTab)
    // A link plays its item at once, or its channel once the lineup is in, in place of the cold start's own page.
    LaunchedEffect(link, lineup != null) {
        when (link) {
            null -> Unit

            is AppLink.Item -> {
                currentOnLinkHandle()
                shell.playItem(link.itemId, link.startMs)
            }

            is AppLink.Live -> {
                if (lineup == null) return@LaunchedEffect
                currentOnLinkHandle()
                // A channel the lineup does not list leaves the app where it was.
                linkedChannel(lineup, link.channel)?.let { shell.playChannel(it.guideId) }
            }
        }
    }
    // A cold start opens the channel last watched, full screen, once the lineup and settings are in. Decided
    // once per process (restored across configuration changes), and before the guide composes, so the preview
    // never opens a session that the full screen is about to replace.
    LaunchedEffect(launch, shell.launchDecided) { shell.decideLaunch(launch) }
    // The platform's launch screen, if it has one, waits for this.
    LaunchedEffect(shell.launchDecided) { if (shell.launchDecided) currentOnLaunchReady() }
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

/** Keeps a link waiting on its Switch through a configuration change. */
private val PairingRequestSaver = Saver<PairingRequest?, List<String>>(
    save = { request -> request?.let { listOf(it.serverUrl, it.code) } ?: emptyList() },
    restore = { saved -> saved.takeIf { it.size == 2 }?.let { PairingRequest(it[0], it[1]) } },
)

private const val NOTICE_MS = 5_000L
