package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.BufferPreference
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.DecoderPreference
import dev.jamesrich.sofaking.data.SeekSteps
import dev.jamesrich.sofaking.data.SkipKind
import dev.jamesrich.sofaking.data.SkipMode
import dev.jamesrich.sofaking.data.SubtitleLook
import dev.jamesrich.sofaking.data.SubtitleRaise
import dev.jamesrich.sofaking.data.SubtitleSize
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.caption_smooth
import dev.jamesrich.sofaking.resources.caption_smooth_help
import dev.jamesrich.sofaking.resources.caption_style
import dev.jamesrich.sofaking.resources.caption_style_large
import dev.jamesrich.sofaking.resources.caption_style_system
import dev.jamesrich.sofaking.resources.caption_timing
import dev.jamesrich.sofaking.resources.caption_timing_help
import dev.jamesrich.sofaking.resources.captions_setting
import dev.jamesrich.sofaking.resources.recordings_skip_commercials
import dev.jamesrich.sofaking.resources.recordings_skip_commercials_help
import dev.jamesrich.sofaking.resources.recordings_title
import dev.jamesrich.sofaking.resources.settings
import dev.jamesrich.sofaking.resources.settings_buffer
import dev.jamesrich.sofaking.resources.settings_buffer_fast
import dev.jamesrich.sofaking.resources.settings_buffer_help
import dev.jamesrich.sofaking.resources.settings_buffer_smooth
import dev.jamesrich.sofaking.resources.settings_buffer_standard
import dev.jamesrich.sofaking.resources.settings_caption_engine
import dev.jamesrich.sofaking.resources.settings_caption_system
import dev.jamesrich.sofaking.resources.settings_caption_timing_less
import dev.jamesrich.sofaking.resources.settings_caption_timing_more
import dev.jamesrich.sofaking.resources.settings_decoder
import dev.jamesrich.sofaking.resources.settings_decoder_auto
import dev.jamesrich.sofaking.resources.settings_decoder_help
import dev.jamesrich.sofaking.resources.settings_decoder_software
import dev.jamesrich.sofaking.resources.settings_hide_spoilers
import dev.jamesrich.sofaking.resources.settings_hide_spoilers_help
import dev.jamesrich.sofaking.resources.settings_match_frame_rate
import dev.jamesrich.sofaking.resources.settings_match_frame_rate_help
import dev.jamesrich.sofaking.resources.settings_section_captions
import dev.jamesrich.sofaking.resources.settings_section_library
import dev.jamesrich.sofaking.resources.settings_section_playback
import dev.jamesrich.sofaking.resources.settings_seek_back
import dev.jamesrich.sofaking.resources.settings_seek_forward
import dev.jamesrich.sofaking.resources.settings_seek_help
import dev.jamesrich.sofaking.resources.settings_seek_seconds
import dev.jamesrich.sofaking.resources.settings_skip_credits
import dev.jamesrich.sofaking.resources.settings_skip_credits_help
import dev.jamesrich.sofaking.resources.settings_skip_intros
import dev.jamesrich.sofaking.resources.settings_skip_intros_help
import dev.jamesrich.sofaking.resources.settings_skip_mode_auto
import dev.jamesrich.sofaking.resources.settings_skip_mode_button
import dev.jamesrich.sofaking.resources.settings_skip_mode_off
import dev.jamesrich.sofaking.resources.settings_subtitle_box
import dev.jamesrich.sofaking.resources.settings_subtitle_box_help
import dev.jamesrich.sofaking.resources.settings_subtitle_look_help
import dev.jamesrich.sofaking.resources.settings_subtitle_raise
import dev.jamesrich.sofaking.resources.settings_subtitle_raise_high
import dev.jamesrich.sofaking.resources.settings_subtitle_raise_low
import dev.jamesrich.sofaking.resources.settings_subtitle_raise_none
import dev.jamesrich.sofaking.resources.settings_subtitle_size
import dev.jamesrich.sofaking.resources.settings_subtitle_size_large
import dev.jamesrich.sofaking.resources.settings_subtitle_size_medium
import dev.jamesrich.sofaking.resources.settings_subtitle_size_small
import dev.jamesrich.sofaking.ui.captionEngineName
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.player.CaptionPolicy
import dev.jamesrich.sofaking.ui.player.captionTimingText
import dev.jamesrich.sofaking.ui.rememberSystemCaptionSettings
import dev.jamesrich.sofaking.ui.supportsCaptionSync
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Recordings, on a server that records: skipping commercials, and the seek steps [withSeekSteps] (when there is no
 * Library section to hold them).
 */
internal fun LazyListScope.recordingsSection(settings: AppSettings, actions: SettingsActions, withSeekSteps: Boolean) {
    item { SectionHeader(stringResource(Res.string.recordings_title)) }
    item {
        SkipChoice(
            SkipKind.COMMERCIAL_BREAKS,
            stringResource(Res.string.recordings_skip_commercials),
            stringResource(Res.string.recordings_skip_commercials_help),
            settings,
            actions,
        )
    }
    if (withSeekSteps) seekStepRows(settings, actions)
}

/** How far one press back and one press forward move a library item or a recording. */
private fun LazyListScope.seekStepRows(settings: AppSettings, actions: SettingsActions) {
    val steps = settings.seekSteps
    item {
        SettingChoice(
            label = stringResource(Res.string.settings_seek_back),
            stored = steps.backSeconds,
            choices = SeekSteps.CHOICES,
            choiceLabel = { stringResource(Res.string.settings_seek_seconds, it) },
            onChange = { actions.onSeekStepsChanged(steps.copy(backSeconds = it)) },
        )
    }
    item {
        SettingChoice(
            label = stringResource(Res.string.settings_seek_forward),
            help = stringResource(Res.string.settings_seek_help),
            stored = steps.forwardSeconds,
            choices = SeekSteps.CHOICES,
            choiceLabel = { stringResource(Res.string.settings_seek_seconds, it) },
            onChange = { actions.onSeekStepsChanged(steps.copy(forwardSeconds = it)) },
        )
    }
}

/** How the players meet [kind]: skipped by itself, offered on a button, or neither. */
@Composable
private fun SkipChoice(kind: SkipKind, label: String, help: String, settings: AppSettings, actions: SettingsActions) {
    SettingChoice(
        label = label,
        help = help,
        stored = settings.skips.of(kind),
        choices = SkipMode.entries,
        choiceLabel = {
            stringResource(
                when (it) {
                    SkipMode.AUTO -> Res.string.settings_skip_mode_auto
                    SkipMode.BUTTON -> Res.string.settings_skip_mode_button
                    SkipMode.OFF -> Res.string.settings_skip_mode_off
                },
            )
        },
        onChange = { actions.onSkipChanged(kind, it) },
    )
}

/**
 * Library, on a server that has one: skipping intros and credits, the seek steps, hiding spoilers, and the subtitles'
 * look ([subtitleLook], shown at once through [onLook]), which its three rows change a part of each.
 */
internal fun LazyListScope.librarySection(
    settings: AppSettings,
    actions: SettingsActions,
    subtitleLook: SubtitleLook,
    onLook: (SubtitleLook) -> Unit,
) {
    item { SectionHeader(stringResource(Res.string.settings_section_library)) }
    item {
        SkipChoice(
            SkipKind.INTROS,
            stringResource(Res.string.settings_skip_intros),
            stringResource(Res.string.settings_skip_intros_help),
            settings,
            actions,
        )
    }
    item {
        SkipChoice(
            SkipKind.CREDITS,
            stringResource(Res.string.settings_skip_credits),
            stringResource(Res.string.settings_skip_credits_help),
            settings,
            actions,
        )
    }
    seekStepRows(settings, actions)
    item {
        SettingToggle(
            label = stringResource(Res.string.settings_hide_spoilers),
            help = stringResource(Res.string.settings_hide_spoilers_help),
            stored = settings.hideSpoilers,
            onChange = actions.onHideSpoilersChanged,
        )
    }
    item { SubtitleSizeChoice(subtitleLook, onLook) }
    item {
        ChoiceRow(
            label = stringResource(Res.string.settings_subtitle_raise),
            choices = SubtitleRaise.entries,
            selected = subtitleLook.raise,
            choiceLabel = {
                stringResource(
                    when (it) {
                        SubtitleRaise.NONE -> Res.string.settings_subtitle_raise_none
                        SubtitleRaise.LOW -> Res.string.settings_subtitle_raise_low
                        SubtitleRaise.HIGH -> Res.string.settings_subtitle_raise_high
                    },
                )
            },
            onSelect = { onLook(subtitleLook.copy(raise = it)) },
        )
    }
    item {
        ToggleRow(
            label = stringResource(Res.string.settings_subtitle_box),
            help = stringResource(Res.string.settings_subtitle_box_help),
            checked = subtitleLook.box,
            onCheckedChange = { onLook(subtitleLook.copy(box = it)) },
        )
    }
}

/** The library subtitles' size, a part of [look]. */
@Composable
private fun SubtitleSizeChoice(look: SubtitleLook, onLook: (SubtitleLook) -> Unit) {
    ChoiceRow(
        label = stringResource(Res.string.settings_subtitle_size),
        choices = SubtitleSize.entries,
        selected = look.size,
        choiceLabel = {
            stringResource(
                when (it) {
                    SubtitleSize.SMALL -> Res.string.settings_subtitle_size_small
                    SubtitleSize.MEDIUM -> Res.string.settings_subtitle_size_medium
                    SubtitleSize.LARGE -> Res.string.settings_subtitle_size_large
                },
            )
        },
        onSelect = { onLook(look.copy(size = it)) },
        help = stringResource(Res.string.settings_subtitle_look_help),
    )
}

/**
 * Captions: on or off, smoothing and timing where the engine can move them, the style, and the platform's own
 * caption settings.
 */
internal fun LazyListScope.captionsSection(settings: AppSettings, actions: SettingsActions) {
    item { SectionHeader(stringResource(Res.string.settings_section_captions)) }
    item {
        SettingToggle(
            label = stringResource(Res.string.captions_setting),
            stored = settings.captionsEnabled,
            onChange = actions.onCaptionsChange,
        )
    }
    if (supportsCaptionSync) {
        item {
            SettingToggle(
                label = stringResource(Res.string.caption_smooth),
                help = stringResource(Res.string.caption_smooth_help),
                stored = settings.smoothCaptions,
                onChange = actions.onSmoothCaptionsChanged,
            )
        }
    }
    item {
        SettingChoice(
            label = stringResource(Res.string.caption_style),
            stored = settings.captionStyle,
            choices = CaptionStyle.entries,
            choiceLabel = {
                stringResource(
                    when (it) {
                        CaptionStyle.SYSTEM -> Res.string.caption_style_system
                        CaptionStyle.LARGE_HIGH_CONTRAST -> Res.string.caption_style_large
                    },
                )
            },
            onChange = actions.onCaptionStyleChanged,
        )
    }
    if (supportsCaptionSync) {
        item { CaptionTimingRow(settings.captionAdvanceMs, actions.onCaptionAdvanceChange) }
        item { HelpText(stringResource(Res.string.caption_timing_help)) }
    }
    item {
        // The platform draws the captions and keeps its own style page; the row opens it where there is one.
        val openSystemCaptions = rememberSystemCaptionSettings()
        Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (openSystemCaptions != null) {
                SofaButton(onClick = openSystemCaptions) {
                    Text(stringResource(Res.string.settings_caption_system))
                }
            }
            HelpText(stringResource(Res.string.settings_caption_engine, captionEngineName()))
        }
    }
}

/** The caption timing, stepped earlier or later; a button that reaches its end of the range hands focus across. */
@Composable
private fun CaptionTimingRow(stored: Int, onChange: (Int) -> Unit) {
    var captionAdvance by rememberShown(stored)
    val range = AppSettings.CAPTION_ADVANCE_RANGE_MS
    val lessLabel = stringResource(Res.string.settings_caption_timing_less)
    val moreLabel = stringResource(Res.string.settings_caption_timing_more)
    // A button that disables at its end of the range hands focus to the other, not to another row.
    val lessFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    SettingRow(label = stringResource(Res.string.caption_timing)) {
        SofaButton(
            onClick = {
                captionAdvance = CaptionPolicy.stepAdvance(captionAdvance, direction = -1)
                onChange(captionAdvance)
                if (captionAdvance <= range.first) moreFocus.requestFocus()
            },
            enabled = captionAdvance > range.first,
            modifier = Modifier.focusRequester(lessFocus).semantics { contentDescription = lessLabel },
        ) { Icon(SofaIcons.Remove, null) }
        Text(captionTimingText(captionAdvance), style = MaterialTheme.typography.bodyLarge)
        SofaButton(
            onClick = {
                captionAdvance = CaptionPolicy.stepAdvance(captionAdvance, direction = 1)
                onChange(captionAdvance)
                if (captionAdvance >= range.last) lessFocus.requestFocus()
            },
            enabled = captionAdvance < range.last,
            modifier = Modifier.focusRequester(moreFocus).semantics { contentDescription = moreLabel },
        ) { Icon(Icons.Filled.Add, null) }
    }
}

/** Playback, where the platform takes these: matching the frame rate, the decoder and the buffer. */
internal fun LazyListScope.playbackSection(settings: AppSettings, actions: SettingsActions) {
    item { SectionHeader(stringResource(Res.string.settings_section_playback)) }
    item {
        SettingToggle(
            label = stringResource(Res.string.settings_match_frame_rate),
            help = stringResource(Res.string.settings_match_frame_rate_help),
            stored = settings.playback.matchFrameRate,
            onChange = actions.onMatchFrameRateChanged,
        )
    }
    item {
        SettingChoice(
            label = stringResource(Res.string.settings_decoder),
            help = stringResource(Res.string.settings_decoder_help),
            stored = settings.playback.decoder,
            choices = DecoderPreference.entries,
            choiceLabel = {
                stringResource(
                    when (it) {
                        DecoderPreference.AUTO -> Res.string.settings_decoder_auto
                        DecoderPreference.SOFTWARE -> Res.string.settings_decoder_software
                    },
                )
            },
            onChange = actions.onDecoderChanged,
        )
    }
    item {
        SettingChoice(
            label = stringResource(Res.string.settings_buffer),
            help = stringResource(Res.string.settings_buffer_help),
            stored = settings.playback.buffer,
            choices = BufferPreference.entries,
            choiceLabel = {
                stringResource(
                    when (it) {
                        BufferPreference.FAST_START -> Res.string.settings_buffer_fast
                        BufferPreference.STANDARD -> Res.string.settings_buffer_standard
                        BufferPreference.SMOOTH -> Res.string.settings_buffer_smooth
                    },
                )
            },
            onChange = actions.onBufferChanged,
        )
    }
}
