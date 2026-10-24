package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.data.StartupTarget
import dev.jamesrich.sofaking.ui.settings.ChannelManagerScreen
import dev.jamesrich.sofaking.ui.settings.ChannelPickerScreen
import dev.jamesrich.sofaking.ui.settings.PairingUi
import dev.jamesrich.sofaking.ui.settings.SettingsActions
import dev.jamesrich.sofaking.ui.settings.SettingsLockScreen
import dev.jamesrich.sofaking.ui.settings.SettingsScreen

/** Settings, the channel manager and the startup channel picker ([route]), behind the PIN when one is set. */
@Composable
internal fun SettingsDestination(
    route: Route,
    arrival: Arrival,
    shell: AppShellState,
    vm: LineupViewModel,
    libraryVm: LibraryViewModel,
    offered: TabsOffered,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val s = settings ?: return
    val lineup = (state as? LineupState.Ready)?.lineup
    if (s.settingsLocked && !shell.settingsUnlocked) {
        SettingsLockScreen(
            pinHash = s.settingsPin,
            apiToken = s.apiToken,
            onUnlock = { shell.settingsUnlocked = true },
            onPinRemove = {
                vm.setSettingsPin(null)
                shell.settingsUnlocked = true
            },
            onBack = shell::leaveSettings,
            lock = shell.settingsLock,
        )
        return
    }
    when (route) {
        Route.Settings -> {
            val loading by vm.loading.collectAsStateWithLifecycle()
            val update by vm.update.collectAsStateWithLifecycle()
            val pairing by vm.pairing.collectAsStateWithLifecycle()
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
                actions = SettingsActions(
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
                    onSkipCommercialsChanged = vm::setSkipCommercials,
                    onAutoSkipIntrosChanged = vm::setAutoSkipIntros,
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
                ),
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

        Route.Channels -> ChannelManagerScreen(
            channels = lineup?.channels.orEmpty(),
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
            channels = lineup?.channels.orEmpty(),
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
