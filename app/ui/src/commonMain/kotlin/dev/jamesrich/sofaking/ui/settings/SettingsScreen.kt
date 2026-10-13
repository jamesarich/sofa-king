package dev.jamesrich.sofaking.ui.settings

import dev.jamesrich.sofaking.ui.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.jamesrich.sofaking.BuildInfo
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaSwitch
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.ServerAddress
import dev.jamesrich.sofaking.ui.LogoMark
import dev.jamesrich.sofaking.ui.TvTextField
import dev.jamesrich.sofaking.ui.player.CaptionPolicy
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached

private val FIELD_WIDTH = 640.dp

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (serverUrl: String, apiToken: String) -> Unit,
    onBack: () -> Unit,
    channelCount: Int,
    modifier: Modifier = Modifier,
    onCaptionsChanged: (Boolean) -> Unit = {},
    onCaptionAdvanceChanged: (Int) -> Unit = {},
    onSmoothCaptionsChanged: (Boolean) -> Unit = {},
    onCaptionStyleChanged: (CaptionStyle) -> Unit = {},
    onAutoOpenDelayChanged: (AutoOpenDelay) -> Unit = {},
    onPreviewSoundChanged: (Boolean) -> Unit = {},
) {
    var captionAdvance by remember(settings.captionAdvanceMs) { mutableIntStateOf(settings.captionAdvanceMs) }
    var smoothCaptions by remember(settings.smoothCaptions) { mutableStateOf(settings.smoothCaptions) }
    var captionStyle by remember(settings.captionStyle) { mutableStateOf(settings.captionStyle) }
    var serverUrl by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var apiToken by remember(settings.apiToken) { mutableStateOf(settings.apiToken) }
    var captionsOn by remember(settings.captionsEnabled) { mutableStateOf(settings.captionsEnabled) }
    var autoOpen by remember(settings.autoOpenDelay) { mutableStateOf(settings.autoOpenDelay) }
    var previewSound by remember(settings.previewSound) { mutableStateOf(settings.previewSound) }
    val serverFocus = remember { FocusRequester() }
    val tokenFocus = remember { FocusRequester() }
    // Save only takes an address the server's API paths can be appended to and a token a header can carry;
    // the fields say what is missing.
    val validUrl = ServerAddress.normalizeUrl(serverUrl)
    val validToken = ServerAddress.normalizeToken(apiToken)
    val canSave = validUrl != null && validToken != null
    val save = { if (validUrl != null && validToken != null) onSave(validUrl, validToken) }

    BackHandler { onBack() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(serverFocus) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .focusRestorer(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LogoMark(40.dp)
                Text(stringResource(Res.string.settings_title), style = MaterialTheme.typography.headlineMedium)
            }
        }
        // Above the fields: always on screen, and one Up from the URL field, with the keyboard closed.
        item {
            Row(
                modifier = Modifier.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SofaButton(onClick = save, enabled = canSave) {
                    Text(stringResource(Res.string.settings_save))
                }
                SofaButton(onClick = onBack) {
                    Text(stringResource(Res.string.settings_back))
                }
            }
        }
        item { SectionHeader(stringResource(Res.string.settings_section_server)) }
        item {
            SettingField(
                label = stringResource(Res.string.settings_server_url),
                stored = settings.serverUrl,
                onValueChange = { serverUrl = it },
                problem = stringResource(Res.string.settings_server_url_invalid).takeIf { validUrl == null },
                focusRequester = serverFocus,
                imeAction = ImeAction.Next,
                onAction = { tokenFocus.requestFocus(FocusDirection.Enter) },
            )
        }
        item {
            SettingField(
                label = stringResource(Res.string.settings_api_token),
                stored = settings.apiToken,
                onValueChange = { apiToken = it },
                problem = stringResource(Res.string.settings_api_token_invalid).takeIf { validToken == null },
                focusRequester = tokenFocus,
                imeAction = ImeAction.Done,
                onAction = save,
                maskUnfocused = ::maskToken,
            )
        }
        item { HelpText(stringResource(Res.string.settings_api_token_help)) }
        item { SectionHeader(stringResource(Res.string.settings_section_captions)) }
        item {
            SettingRow(label = stringResource(Res.string.captions_setting)) {
                SofaSwitch(
                    checked = captionsOn,
                    onCheckedChange = {
                        captionsOn = it
                        onCaptionsChanged(it)
                    },
                )
            }
        }
        item {
            SettingRow(label = stringResource(Res.string.caption_smooth)) {
                SofaSwitch(
                    checked = smoothCaptions,
                    onCheckedChange = {
                        smoothCaptions = it
                        onSmoothCaptionsChanged(it)
                    },
                )
            }
        }
        item { HelpText(stringResource(Res.string.caption_smooth_help)) }
        item {
            SettingRow(label = stringResource(Res.string.caption_style)) {
                SofaButton(
                    onClick = {
                        captionStyle = if (captionStyle == CaptionStyle.SYSTEM) {
                            CaptionStyle.LARGE_HIGH_CONTRAST
                        } else {
                            CaptionStyle.SYSTEM
                        }
                        onCaptionStyleChanged(captionStyle)
                    },
                ) {
                    Text(
                        stringResource(
                            if (captionStyle == CaptionStyle.SYSTEM) Res.string.caption_style_system else Res.string.caption_style_large,
                        ),
                    )
                }
            }
        }
        item {
            val lessLabel = stringResource(Res.string.settings_caption_timing_less)
            val moreLabel = stringResource(Res.string.settings_caption_timing_more)
            SettingRow(label = stringResource(Res.string.caption_timing)) {
                SofaButton(
                    onClick = {
                        captionAdvance = CaptionPolicy.stepAdvance(captionAdvance, direction = -1)
                        onCaptionAdvanceChanged(captionAdvance)
                    },
                    enabled = captionAdvance > AppSettings.CAPTION_ADVANCE_RANGE_MS.first,
                    modifier = Modifier.semantics { contentDescription = lessLabel },
                ) { Text("−") }
                Text(
                    stringResource(Res.string.caption_timing_value, CaptionPolicy.advanceLabel(captionAdvance)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                SofaButton(
                    onClick = {
                        captionAdvance = CaptionPolicy.stepAdvance(captionAdvance, direction = 1)
                        onCaptionAdvanceChanged(captionAdvance)
                    },
                    enabled = captionAdvance < AppSettings.CAPTION_ADVANCE_RANGE_MS.last,
                    modifier = Modifier.semantics { contentDescription = moreLabel },
                ) { Text("+") }
            }
        }
        item { HelpText(stringResource(Res.string.caption_timing_help)) }
        item { SectionHeader(stringResource(Res.string.settings_section_guide)) }
        item {
            val offLabel = stringResource(Res.string.settings_auto_open_off)
            val tenLabel = stringResource(Res.string.settings_auto_open_10)
            val thirtyLabel = stringResource(Res.string.settings_auto_open_30)
            SettingRow(label = stringResource(Res.string.settings_auto_open)) {
                SofaButton(
                    onClick = {
                        autoOpen = AutoOpenDelay.entries[(autoOpen.ordinal + 1) % AutoOpenDelay.entries.size]
                        onAutoOpenDelayChanged(autoOpen)
                    },
                ) {
                    Text(
                        when (autoOpen) {
                            AutoOpenDelay.OFF -> offLabel
                            AutoOpenDelay.AFTER_10_SECONDS -> tenLabel
                            AutoOpenDelay.AFTER_30_SECONDS -> thirtyLabel
                        },
                    )
                }
            }
        }
        item { HelpText(stringResource(Res.string.settings_auto_open_help)) }
        item {
            SettingRow(label = stringResource(Res.string.settings_preview_sound)) {
                SofaSwitch(
                    checked = previewSound,
                    onCheckedChange = {
                        previewSound = it
                        onPreviewSoundChanged(it)
                    },
                )
            }
        }
        item { SectionHeader(stringResource(Res.string.settings_section_about)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LogoMark(64.dp)
                Column {
                    Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(Res.string.settings_version, BuildInfo.VERSION),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        pluralStringResource(Res.plurals.settings_channels_loaded, channelCount, channelCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun HelpText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(FIELD_WIDTH))
}

/** A label with its control(s) beside it; one focus group so Left/Right stay inside the row. */
@Composable
private fun SettingRow(label: String, controls: @Composable () -> Unit) {
    Row(
        modifier = Modifier.focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        controls()
    }
}

@Composable
private fun SettingField(
    label: String,
    stored: String,
    onValueChange: (String) -> Unit,
    problem: String?,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    onAction: () -> Unit,
    maskUnfocused: ((String) -> String)? = null,
) {
    // The field owns what is typed; only a change to the saved [stored] value replaces it, so an echo of
    // earlier keystrokes can never overwrite newer ones.
    val state = rememberTextFieldState(stored)
    LaunchedEffect(stored) {
        if (state.text.toString() != stored) state.setTextAndPlaceCursorAtEnd(stored)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect(onValueChange)
    }

    Column(Modifier.width(FIELD_WIDTH)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        TvTextField(
            state = state,
            focusRequester = focusRequester,
            imeAction = imeAction,
            onAction = onAction,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            maskUnfocused = maskUnfocused,
        )
        if (problem != null) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
