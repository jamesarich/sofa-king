package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.data.LiveBreaks
import dev.jamesrich.sofaking.data.StartupTarget
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.settings
import dev.jamesrich.sofaking.resources.settings_ads_dodged
import dev.jamesrich.sofaking.resources.settings_auto_open
import dev.jamesrich.sofaking.resources.settings_auto_open_10
import dev.jamesrich.sofaking.resources.settings_auto_open_30
import dev.jamesrich.sofaking.resources.settings_auto_open_help
import dev.jamesrich.sofaking.resources.settings_auto_open_off
import dev.jamesrich.sofaking.resources.settings_channels
import dev.jamesrich.sofaking.resources.settings_channels_open
import dev.jamesrich.sofaking.resources.settings_guide_rows
import dev.jamesrich.sofaking.resources.settings_live_breaks
import dev.jamesrich.sofaking.resources.settings_live_breaks_help
import dev.jamesrich.sofaking.resources.settings_live_breaks_play
import dev.jamesrich.sofaking.resources.settings_live_breaks_screen
import dev.jamesrich.sofaking.resources.settings_live_breaks_screen_muted
import dev.jamesrich.sofaking.resources.settings_preview_sound
import dev.jamesrich.sofaking.resources.settings_rows_comfortable
import dev.jamesrich.sofaking.resources.settings_rows_compact
import dev.jamesrich.sofaking.resources.settings_startup
import dev.jamesrich.sofaking.resources.settings_startup_channel
import dev.jamesrich.sofaking.resources.settings_startup_channel_none
import dev.jamesrich.sofaking.resources.settings_startup_choose
import dev.jamesrich.sofaking.resources.settings_startup_guide
import dev.jamesrich.sofaking.resources.settings_startup_help
import dev.jamesrich.sofaking.resources.settings_startup_library
import dev.jamesrich.sofaking.resources.settings_startup_resume
import dev.jamesrich.sofaking.ui.hoursAndMinutes
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.localDay
import dev.jamesrich.sofaking.ui.theme.SofaButton
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The Live TV page: what the app opens on ([startup], shown at once through [onStartup]), the startup channel when that
 * is the choice, then the guide's rows.
 */
internal fun LazyListScope.liveTvPage(
    settings: AppSettings,
    actions: SettingsActions,
    startup: StartupTarget,
    onStartup: (StartupTarget) -> Unit,
    channels: List<Channel>,
    libraryOffered: Boolean,
    startupFocus: FocusRequester,
    channelsFocus: FocusRequester,
) {
    item { StartupChoice(settings, startup, libraryOffered, actions, onStartup) }
    if (startup == StartupTarget.CHANNEL) {
        item {
            val chosen = channels.firstOrNull { it.guideId == settings.startupChannel }
            SettingRow(label = chosen?.label() ?: stringResource(Res.string.settings_startup_channel_none)) {
                SofaButton(
                    onClick = actions.onChooseStartupChannel,
                    modifier = Modifier.focusRequester(startupFocus),
                ) {
                    Text(stringResource(Res.string.settings_startup_choose))
                }
            }
        }
    }
    guideRows(settings, actions, channels, channelsFocus)
}

/** What the app opens on. Picking a channel with none chosen opens the channel picker at once. */
@Composable
private fun StartupChoice(
    settings: AppSettings,
    startup: StartupTarget,
    libraryOffered: Boolean,
    actions: SettingsActions,
    onStartup: (StartupTarget) -> Unit,
) {
    ChoiceRow(
        label = stringResource(Res.string.settings_startup),
        help = stringResource(Res.string.settings_startup_help),
        width = WIDE_CHOICE_WIDTH,
        // The library is offered on a server that has one, and kept while it is the choice.
        choices = StartupTarget.entries.filter { it != StartupTarget.LIBRARY || libraryOffered || startup == it },
        selected = startup,
        choiceLabel = {
            stringResource(
                when (it) {
                    StartupTarget.RESUME -> Res.string.settings_startup_resume
                    StartupTarget.GUIDE -> Res.string.settings_startup_guide
                    StartupTarget.LIBRARY -> Res.string.settings_startup_library
                    StartupTarget.CHANNEL -> Res.string.settings_startup_channel
                },
            )
        },
        onSelect = {
            onStartup(it)
            actions.onStartupChanged(it, settings.startupChannel)
            // A channel has to be chosen for this to mean anything, so the picker opens at once.
            if (it == StartupTarget.CHANNEL && settings.startupChannel == null) {
                actions.onChooseStartupChannel()
            }
        },
    )
}

/** The guide's rows: the auto-open delay, the preview's sound, live ad breaks, the row density, the channel manager. */
private fun LazyListScope.guideRows(
    settings: AppSettings,
    actions: SettingsActions,
    channels: List<Channel>,
    channelsFocus: FocusRequester,
) {
    item {
        SettingChoice(
            label = stringResource(Res.string.settings_auto_open),
            help = stringResource(Res.string.settings_auto_open_help),
            stored = settings.autoOpenDelay,
            choices = AutoOpenDelay.entries,
            choiceLabel = {
                stringResource(
                    when (it) {
                        AutoOpenDelay.OFF -> Res.string.settings_auto_open_off
                        AutoOpenDelay.AFTER_10_SECONDS -> Res.string.settings_auto_open_10
                        AutoOpenDelay.AFTER_30_SECONDS -> Res.string.settings_auto_open_30
                    },
                )
            },
            onChange = actions.onAutoOpenDelayChanged,
        )
    }
    item {
        SettingToggle(
            label = stringResource(Res.string.settings_preview_sound),
            stored = settings.previewSound,
            onChange = actions.onPreviewSoundChanged,
        )
    }
    item { LiveBreaksChoice(settings, actions) }
    item {
        SettingChoice(
            label = stringResource(Res.string.settings_guide_rows),
            stored = settings.guideDensity,
            choices = GuideDensity.entries,
            choiceLabel = {
                stringResource(
                    when (it) {
                        GuideDensity.COMFORTABLE -> Res.string.settings_rows_comfortable
                        GuideDensity.COMPACT -> Res.string.settings_rows_compact
                    },
                )
            },
            onChange = actions.onGuideDensityChanged,
        )
    }
    item {
        Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.settings_channels), style = MaterialTheme.typography.titleSmall)
            SofaButton(
                onClick = actions.onManageChannels,
                enabled = channels.isNotEmpty(),
                modifier = Modifier.focusRequester(channelsFocus),
            ) {
                Text(stringResource(Res.string.settings_channels_open))
            }
        }
    }
}

/** What a live channel's ad break shows, with the ads dodged today in its help once there is a minute of them. */
@Composable
private fun LiveBreaksChoice(settings: AppSettings, actions: SettingsActions) {
    SettingChoice(
        label = stringResource(Res.string.settings_live_breaks),
        help = listOfNotNull(
            stringResource(Res.string.settings_live_breaks_help),
            settings.adsDodged.on(localDay()).milliseconds.takeIf { it >= 1.minutes }
                ?.let { stringResource(Res.string.settings_ads_dodged, it.hoursAndMinutes()) },
        ).joinToString(" "),
        stored = settings.liveBreaks,
        choices = LiveBreaks.entries,
        choiceLabel = {
            stringResource(
                when (it) {
                    LiveBreaks.PLAY -> Res.string.settings_live_breaks_play
                    LiveBreaks.SCREEN_MUTED -> Res.string.settings_live_breaks_screen_muted
                    LiveBreaks.SCREEN -> Res.string.settings_live_breaks_screen
                },
            )
        },
        onChange = actions.onLiveBreaksChanged,
    )
}
