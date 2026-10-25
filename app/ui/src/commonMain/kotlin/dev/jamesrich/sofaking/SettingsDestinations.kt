package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.StartupTarget
import dev.jamesrich.sofaking.ui.settings.ChannelManagerScreen
import dev.jamesrich.sofaking.ui.settings.ChannelPickerScreen
import dev.jamesrich.sofaking.ui.settings.PairingUi
import dev.jamesrich.sofaking.ui.settings.SettingsActions
import dev.jamesrich.sofaking.ui.settings.SettingsLockScreen
import dev.jamesrich.sofaking.ui.settings.SettingsScreen

/** Settings, behind the PIN when one is set. */
@Composable
internal fun SettingsDestination(
    arrival: Arrival,
    shell: AppShellState,
    vm: LineupViewModel,
    libraryVm: LibraryViewModel,
    offered: TabsOffered,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val s = settings ?: return
    val lineup = (state as? LineupState.Ready)?.lineup
    SettingsLockGate(s, shell, onPinRemove = { vm.setSettingsPin(null) }) {
        SettingsScreen(
            settings = s,
            onSave = { url, token ->
                vm.saveServer(url, token)
                shell.startOver()
            },
            discoveredServers = vm.discoveredServers,
            onBack = shell::leaveSettings,
            channelCount = lineup?.channels?.size ?: 0,
            channels = lineup?.channels.orEmpty(),
            libraryOffered = offered.library,
            recordingsOffered = offered.recordings,
            reloading = loading,
            serverUnreachable = state is LineupState.Failed,
            actions = settingsActions(shell, vm, libraryVm),
            pairing = PairingUi(
                busy = pairing.busy,
                failure = pairing.failure,
                onPair = vm::pair,
                onDismiss = vm::pairingDismissed,
            ),
            update = update,
            returnedFrom = arrival.returnedFrom,
        )
    }
}

/** The channel manager and the startup channel picker ([route]) behind Settings, behind its PIN too. */
@Composable
internal fun ChannelsDestination(route: Route, shell: AppShellState, vm: LineupViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val s = settings ?: return
    val channels = (state as? LineupState.Ready)?.lineup?.channels.orEmpty()
    SettingsLockGate(s, shell, onPinRemove = { vm.setSettingsPin(null) }) {
        when (route) {
            Route.Channels -> ChannelManagerScreen(
                channels = channels,
                favorites = s.favorites,
                favoritesOrder = s.favoritesOrder,
                hidden = s.hiddenChannels,
                zapSkipsHidden = s.zapSkipsHidden,
                onFavoritesOrderChange = vm::setFavoritesOrder,
                onChannelHiddenChange = vm::setChannelHidden,
                onChannelsHiddenChange = vm::setChannelsHidden,
                onZapSkipsHiddenChange = vm::setZapSkipsHidden,
                onBack = shell::back,
            )

            Route.StartupChannel -> ChannelPickerScreen(
                channels = channels,
                selected = s.startupChannel,
                onPick = { channel ->
                    vm.setStartup(StartupTarget.CHANNEL, channel.guideId)
                    shell.back()
                },
                onBack = shell::back,
            )

            else -> Unit
        }
    }
}

/**
 * The lock page in place of [content] while Settings is locked: the PIN unlocks it for the visit, and the API token
 * removes the PIN through [onPinRemove].
 */
@Composable
private fun SettingsLockGate(
    settings: AppSettings,
    shell: AppShellState,
    onPinRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (settings.settingsLocked && !shell.settingsUnlocked) {
        SettingsLockScreen(
            pinHash = settings.settingsPin,
            apiToken = settings.apiToken,
            onUnlock = { shell.settingsUnlocked = true },
            onPinRemove = {
                onPinRemove()
                shell.settingsUnlocked = true
            },
            onBack = shell::leaveSettings,
            lock = shell.settingsLock,
        )
    } else {
        content()
    }
}

/** What each Settings row changes: the settings in [vm], and the pages [shell] opens. */
private fun settingsActions(shell: AppShellState, vm: LineupViewModel, libraryVm: LibraryViewModel): SettingsActions =
    SettingsActions(
        onCaptionsChange = vm::setCaptionsEnabled,
        onCaptionAdvanceChange = vm::setCaptionAdvanceMs,
        onSmoothCaptionsChanged = vm::setSmoothCaptions,
        onCaptionStyleChanged = vm::setCaptionStyle,
        onAutoOpenDelayChanged = vm::setAutoOpenDelay,
        onPreviewSoundChanged = vm::setPreviewSound,
        onStartupChanged = vm::setStartup,
        onChooseStartupChannel = { shell.push(Route.StartupChannel) },
        onClockFormatChanged = vm::setClockFormat,
        onGuideDensityChanged = vm::setGuideDensity,
        onManageChannels = { shell.push(Route.Channels) },
        onMatchFrameRateChanged = vm::setMatchFrameRate,
        onDecoderChanged = vm::setDecoder,
        onBufferChanged = vm::setBuffer,
        onCheckForUpdate = vm::checkForUpdate,
        onSkipChanged = vm::setSkip,
        onSeekStepsChanged = vm::setSeekSteps,
        onHideSpoilersChanged = vm::setHideSpoilers,
        onSubtitleLookChanged = vm::setSubtitleLook,
        onLiveBreaksChanged = vm::setLiveBreaks,
        onReload = {
            vm.refresh()
            libraryVm.refreshHome()
        },
        onSettingsPinChanged = { pin ->
            vm.setSettingsPin(pin)
            // The page that set the PIN stays open; the lock takes effect on the next visit.
            shell.settingsUnlocked = true
        },
    )
