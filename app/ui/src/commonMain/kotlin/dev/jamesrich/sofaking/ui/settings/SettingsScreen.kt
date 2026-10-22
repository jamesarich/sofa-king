package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import org.jetbrains.compose.resources.ExperimentalResourceApi
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.BuildInfo
import dev.jamesrich.sofaking.PairingFailure
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.LiveBreaks
import dev.jamesrich.sofaking.data.BufferPreference
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.ClockFormat
import dev.jamesrich.sofaking.data.DecoderPreference
import dev.jamesrich.sofaking.data.DiscoveredServer
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.data.InstallFailure
import dev.jamesrich.sofaking.data.InstallState
import dev.jamesrich.sofaking.data.ServerAddress
import dev.jamesrich.sofaking.data.StartupTarget
import dev.jamesrich.sofaking.data.UpdateState
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.TvTextField
import dev.jamesrich.sofaking.ui.captionEngineName
import dev.jamesrich.sofaking.ui.hoursAndMinutes
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.localDay
import dev.jamesrich.sofaking.ui.pageServerUrl
import dev.jamesrich.sofaking.ui.player.CaptionPolicy
import dev.jamesrich.sofaking.ui.player.captionTimingText
import dev.jamesrich.sofaking.ui.rememberSystemCaptionSettings
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.ScrollOnlyWhenHidden
import dev.jamesrich.sofaking.ui.supportsCaptionSync
import dev.jamesrich.sofaking.ui.supportsPlaybackPreferences
import dev.jamesrich.sofaking.ui.rememberUpdateInstaller
import dev.jamesrich.sofaking.ui.updateDownloadUrl
import dev.jamesrich.sofaking.ui.theme.SofaButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The switches and choices Settings applies at once; each lands in the store as it is changed. */
class SettingsActions(
    val onCaptionsChanged: (Boolean) -> Unit = {},
    val onCaptionAdvanceChanged: (Int) -> Unit = {},
    val onSmoothCaptionsChanged: (Boolean) -> Unit = {},
    val onCaptionStyleChanged: (CaptionStyle) -> Unit = {},
    val onAutoOpenDelayChanged: (AutoOpenDelay) -> Unit = {},
    val onPreviewSoundChanged: (Boolean) -> Unit = {},
    /** The startup target, with the chosen channel kept for [StartupTarget.CHANNEL]. */
    val onStartupChanged: (StartupTarget, String?) -> Unit = { _, _ -> },
    /** Opens the channel picker for [StartupTarget.CHANNEL]. */
    val onChooseStartupChannel: () -> Unit = {},
    val onClockFormatChanged: (ClockFormat) -> Unit = {},
    val onGuideDensityChanged: (GuideDensity) -> Unit = {},
    /** Opens the channel manager (hidden channels and the favorites order). */
    val onManageChannels: () -> Unit = {},
    val onMatchFrameRateChanged: (Boolean) -> Unit = {},
    val onDecoderChanged: (DecoderPreference) -> Unit = {},
    val onBufferChanged: (BufferPreference) -> Unit = {},
    /** A new PIN for the lock, or null to remove it. */
    val onSettingsPinChanged: (String?) -> Unit = {},
    /** Compares the running [version] with the published snapshot; [again] repeats a check already made. */
    val onCheckForUpdate: (version: String, again: Boolean) -> Unit = { _, _ -> },
    val onSkipCommercialsChanged: (Boolean) -> Unit = {},
    val onLiveBreaksChanged: (LiveBreaks) -> Unit = {},
    /** Loads the lineup and the library home again from the server. */
    val onReload: () -> Unit = {},
)

/** The pairing flow as Settings shows it: whether a claim is running, and why the last one failed. */
class PairingUi(
    val busy: Boolean = false,
    val failure: PairingFailure? = null,
    /** Claims the code at the server URL; the caller saves the server on success. */
    val onPair: (serverUrl: String, code: String) -> Unit = { _, _ -> },
    /** The server block that showed [failure] closed; it is not shown again. */
    val onDismiss: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (serverUrl: String, apiToken: String) -> Unit,
    onBack: () -> Unit,
    channelCount: Int,
    modifier: Modifier = Modifier,
    /** The lineup's channels, for the name of the chosen startup channel. */
    channels: List<Channel> = emptyList(),
    /** Whether the server has a library, so a start can open it. */
    libraryOffered: Boolean = false,
    /** Whether the server records, which shows the Recordings section. */
    recordingsOffered: Boolean = false,
    /** True while a reload asked for here is running. */
    reloading: Boolean = false,
    /** True when the last lineup load from the saved server failed. */
    serverUnreachable: Boolean = false,
    actions: SettingsActions = SettingsActions(),
    pairing: PairingUi = PairingUi(),
    /** The update check's answer, or null while none has run. */
    update: UpdateState? = null,
    /** The page behind Settings the viewer just came back from ([CHANNELS_FOCUS_KEY], [STARTUP_FOCUS_KEY]); its button takes focus. */
    returnedFrom: String? = null,
    /** The servers on the network, collected (and so browsed for) only while the server fields are open. */
    discoveredServers: Flow<List<DiscoveredServer>> = noServers,
) {
    var captionAdvance by remember(settings.captionAdvanceMs) { mutableIntStateOf(settings.captionAdvanceMs) }
    var smoothCaptions by remember(settings.smoothCaptions) { mutableStateOf(settings.smoothCaptions) }
    var captionStyle by remember(settings.captionStyle) { mutableStateOf(settings.captionStyle) }
    val serverUrl = rememberStoredFieldState(settings.serverUrl.ifEmpty { pageServerUrl.orEmpty() })
    val apiToken = rememberStoredFieldState(settings.apiToken)
    var captionsOn by remember(settings.captionsEnabled) { mutableStateOf(settings.captionsEnabled) }
    var autoOpen by remember(settings.autoOpenDelay) { mutableStateOf(settings.autoOpenDelay) }
    var previewSound by remember(settings.previewSound) { mutableStateOf(settings.previewSound) }
    var startup by remember(settings.startup) { mutableStateOf(settings.startup) }
    var clockFormat by remember(settings.clockFormat) { mutableStateOf(settings.clockFormat) }
    var guideDensity by remember(settings.guideDensity) { mutableStateOf(settings.guideDensity) }
    var matchFrameRate by remember(settings.playback.matchFrameRate) { mutableStateOf(settings.playback.matchFrameRate) }
    var decoder by remember(settings.playback.decoder) { mutableStateOf(settings.playback.decoder) }
    var buffer by remember(settings.playback.buffer) { mutableStateOf(settings.playback.buffer) }
    var skipCommercials by remember(settings.skipCommercials) { mutableStateOf(settings.skipCommercials) }
    var liveBreaks by remember(settings.liveBreaks) { mutableStateOf(settings.liveBreaks) }
    val pairingCode = rememberTextFieldState()
    val serverFocus = remember { FocusRequester() }
    val tokenFocus = remember { FocusRequester() }
    val codeFocus = remember { FocusRequester() }
    // Save only takes an address the server's API paths can be appended to and a token a header can carry;
    // the fields say what is missing. It stays off until either differs from what is stored.
    val validUrl = ServerAddress.normalizeUrl(serverUrl.text.toString())
    val validToken = ServerAddress.normalizeToken(apiToken.text.toString())
    val canSave = canSaveServer(settings, validUrl, validToken)
    val save = { if (canSave && validUrl != null && validToken != null) onSave(validUrl, validToken) }
    val validCode = ServerAddress.normalizeCode(pairingCode.text.toString())
    val canPair = canPairServer(validUrl, validCode, pairing.busy)
    val pair = { if (canPair && validUrl != null && validCode != null) pairing.onPair(validUrl, validCode) }

    // The server block opens on its fields only on a device with no server, or while a pairing attempt is being
    // shown; otherwise it reads as one line with Change, and focus lands on Back (or, where no Back button is drawn,
    // the first setting) rather than inside a field.
    var changingServer by remember { mutableStateOf(settings.serverUrl.isEmpty() || pairing.failure != null) }
    LaunchedEffect(pairing.failure) { if (pairing.failure != null) changingServer = true }
    val serverOpen = changingServer
    val unconfigured = remember { settings.serverUrl.isEmpty() }
    val found by remember(serverOpen) { if (serverOpen) discoveredServers else noServers }.collectAsStateWithLifecycle(emptyList())
    val backFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val firstFocus = if (showsBackButton()) backFocus else contentFocus
    val channelsFocus = remember { FocusRequester() }
    val startupFocus = remember { FocusRequester() }

    BackHandler { onBack() }
    LaunchedEffect(Unit) {
        val returnTo = when (returnedFrom) {
            CHANNELS_FOCUS_KEY -> channelsFocus
            STARTUP_FOCUS_KEY -> startupFocus
            else -> null
        }
        if (returnTo != null && requestFocusWhenAttached(returnTo)) return@LaunchedEffect
        requestFocusWhenAttached(if (settings.serverUrl.isEmpty()) serverFocus else firstFocus)
    }
    LaunchedEffect(changingServer) { if (changingServer && settings.serverUrl.isNotEmpty()) requestFocusWhenAttached(serverFocus) }

    // The server block, first on a device with none and after Playback on one that has it.
    val serverSection: LazyListScope.() -> Unit = {
        item { SectionHeader(stringResource(Res.string.settings_section_server)) }
        if (!serverOpen) {
            item {
                val status = if (serverUnreachable) Res.string.settings_server_unreachable else Res.string.settings_server_connected
                SettingRow(label = stringResource(status, settings.serverUrl)) {
                    SofaButton(onClick = { changingServer = true }) { Text(stringResource(Res.string.settings_server_change)) }
                    // A web page that cannot reach a server elsewhere most likely sits behind the proxy that serves it.
                    val page = pageServerUrl?.takeIf { serverUnreachable && it != ServerAddress.normalizeUrl(settings.serverUrl) }
                    if (page != null) {
                        SofaButton(onClick = {
                            serverUrl.setTextAndPlaceCursorAtEnd(page)
                            changingServer = true
                        }) { Text(stringResource(Res.string.settings_server_use_page)) }
                    }
                    // Stays enabled while it runs, so the D-pad focus never drops.
                    SofaButton(onClick = { if (!reloading) actions.onReload() }) {
                        Text(stringResource(if (reloading) Res.string.settings_server_reloading else Res.string.settings_server_reload))
                    }
                }
            }
        } else {
            // Picking a server found on the network fills the address; the pairing code is next.
            if (found.isNotEmpty()) {
                item {
                    Column(Modifier.width(FIELD_WIDTH).focusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HelpText(stringResource(Res.string.settings_discovered_servers))
                        found.forEach { server ->
                            SofaButton(onClick = {
                                serverUrl.setTextAndPlaceCursorAtEnd(server.url)
                                codeFocus.requestFocus()
                            }) {
                                Text(stringResource(Res.string.settings_discovered_server, server.name, server.url.substringAfter("://")))
                            }
                        }
                    }
                }
            }
            item {
                SettingField(
                    label = stringResource(Res.string.settings_server_url),
                    state = serverUrl,
                    // An empty field is not yet wrong; it says so once something is typed.
                    problem = stringResource(Res.string.settings_server_url_invalid).takeIf { serverUrl.text.isNotBlank() && validUrl == null },
                    focusRequester = serverFocus,
                    imeAction = ImeAction.Next,
                    onAction = { codeFocus.requestFocus(FocusDirection.Enter) },
                )
            }
            // The pairing code is the designed path; the token below it is the fallback.
            item {
                // The help sits above the field, where the on-screen keyboard does not cover it.
                Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HelpText(stringResource(Res.string.settings_pairing_help))
                    SettingField(
                        label = stringResource(Res.string.settings_pairing_code),
                        state = pairingCode,
                        problem = stringResource(Res.string.settings_pairing_code_invalid).takeIf { pairingCode.text.isNotEmpty() && validCode == null },
                        focusRequester = codeFocus,
                        imeAction = ImeAction.Done,
                        onAction = pair,
                    )
                    SofaButton(onClick = pair, enabled = canPair) {
                        Text(stringResource(if (pairing.busy) Res.string.settings_pairing else Res.string.settings_pair))
                    }
                    pairing.failure?.let { failure ->
                        Text(
                            stringResource(
                                when (failure) {
                                    PairingFailure.INVALID_CODE -> Res.string.settings_pairing_failed_code
                                    PairingFailure.OLDER_SERVER -> Res.string.settings_pairing_failed_server
                                    PairingFailure.UNREACHABLE -> Res.string.settings_pairing_failed_unreachable
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item {
                Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingField(
                        label = stringResource(Res.string.settings_api_token),
                        state = apiToken,
                        problem = stringResource(Res.string.settings_api_token_invalid).takeIf { validToken == null },
                        focusRequester = tokenFocus,
                        imeAction = ImeAction.Done,
                        onAction = save,
                        maskUnfocused = ::maskToken,
                    )
                    HelpText(stringResource(Res.string.settings_api_token_help))
                    Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SofaButton(onClick = save, enabled = canSave) { Text(stringResource(Res.string.settings_save)) }
                        if (settings.serverUrl.isNotEmpty()) {
                            SofaButton(onClick = {
                                changingServer = false
                                pairing.onDismiss()
                                serverUrl.setTextAndPlaceCursorAtEnd(settings.serverUrl)
                                apiToken.setTextAndPlaceCursorAtEnd(settings.apiToken)
                                pairingCode.clearText()
                            }) {
                                Text(stringResource(Res.string.settings_server_cancel))
                            }
                        }
                    }
                }
            }
        }
    }

    // Focus scrolls only to show what it lands on, so the first setting taking focus keeps the title on screen.
    ScrollOnlyWhenHidden(SETTINGS_FOCUS_EDGE) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .screenPadding()
            .focusRequester(contentFocus)
            .focusRestorer()
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenTitle(stringResource(Res.string.settings_title), onBack, backFocus) }
        // A device with no server starts here; a configured one keeps the server near the end, as it seldom changes.
        if (unconfigured) serverSection()
        item { SectionHeader(stringResource(Res.string.settings_section_general)) }
        item {
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
                    startup = it
                    actions.onStartupChanged(it, settings.startupChannel)
                    // A channel has to be chosen for this to mean anything, so the picker opens at once.
                    if (it == StartupTarget.CHANNEL && settings.startupChannel == null) actions.onChooseStartupChannel()
                },
            )
        }
        if (startup == StartupTarget.CHANNEL) {
            item {
                val chosen = channels.firstOrNull { it.guideId == settings.startupChannel }
                SettingRow(label = chosen?.label() ?: stringResource(Res.string.settings_startup_channel_none)) {
                    SofaButton(onClick = actions.onChooseStartupChannel, modifier = Modifier.focusRequester(startupFocus)) { Text(stringResource(Res.string.settings_startup_choose)) }
                }
            }
        }
        item {
            ChoiceRow(
                label = stringResource(Res.string.settings_time_format),
                choices = ClockFormat.entries,
                selected = clockFormat,
                choiceLabel = {
                    stringResource(
                        when (it) {
                            ClockFormat.AUTO -> Res.string.settings_time_auto
                            ClockFormat.TWELVE_HOUR -> Res.string.settings_time_12
                            ClockFormat.TWENTY_FOUR_HOUR -> Res.string.settings_time_24
                        },
                    )
                },
                onSelect = {
                    clockFormat = it
                    actions.onClockFormatChanged(it)
                },
            )
        }
        item { SectionHeader(stringResource(Res.string.settings_section_guide)) }
        item {
            ChoiceRow(
                label = stringResource(Res.string.settings_auto_open),
                help = stringResource(Res.string.settings_auto_open_help),
                choices = AutoOpenDelay.entries,
                selected = autoOpen,
                choiceLabel = {
                    stringResource(
                        when (it) {
                            AutoOpenDelay.OFF -> Res.string.settings_auto_open_off
                            AutoOpenDelay.AFTER_10_SECONDS -> Res.string.settings_auto_open_10
                            AutoOpenDelay.AFTER_30_SECONDS -> Res.string.settings_auto_open_30
                        },
                    )
                },
                onSelect = {
                    autoOpen = it
                    actions.onAutoOpenDelayChanged(it)
                },
            )
        }
        item {
            ToggleRow(
                label = stringResource(Res.string.settings_preview_sound),
                checked = previewSound,
                onCheckedChange = {
                    previewSound = it
                    actions.onPreviewSoundChanged(it)
                },
            )
        }
        item {
            ChoiceRow(
                label = stringResource(Res.string.settings_live_breaks),
                help = listOfNotNull(
                    stringResource(Res.string.settings_live_breaks_help),
                    settings.adsDodged.on(localDay()).milliseconds.takeIf { it >= 1.minutes }
                        ?.let { stringResource(Res.string.settings_ads_dodged, it.hoursAndMinutes()) },
                ).joinToString(" "),
                choices = LiveBreaks.entries,
                selected = liveBreaks,
                choiceLabel = {
                    stringResource(
                        when (it) {
                            LiveBreaks.PLAY -> Res.string.settings_live_breaks_play
                            LiveBreaks.SCREEN_MUTED -> Res.string.settings_live_breaks_screen_muted
                            LiveBreaks.SCREEN -> Res.string.settings_live_breaks_screen
                        },
                    )
                },
                onSelect = {
                    liveBreaks = it
                    actions.onLiveBreaksChanged(it)
                },
            )
        }
        item {
            ChoiceRow(
                label = stringResource(Res.string.settings_guide_rows),
                choices = GuideDensity.entries,
                selected = guideDensity,
                choiceLabel = {
                    stringResource(
                        when (it) {
                            GuideDensity.COMFORTABLE -> Res.string.settings_rows_comfortable
                            GuideDensity.COMPACT -> Res.string.settings_rows_compact
                        },
                    )
                },
                onSelect = {
                    guideDensity = it
                    actions.onGuideDensityChanged(it)
                },
            )
        }
        item {
            Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.settings_channels), style = MaterialTheme.typography.titleSmall)
                SofaButton(onClick = actions.onManageChannels, enabled = channels.isNotEmpty(), modifier = Modifier.focusRequester(channelsFocus)) {
                    Text(stringResource(Res.string.settings_channels_open))
                }
            }
        }
        if (recordingsOffered) {
            item { SectionHeader(stringResource(Res.string.recordings_title)) }
            item {
                ToggleRow(
                    label = stringResource(Res.string.recordings_skip_commercials),
                    help = stringResource(Res.string.recordings_skip_commercials_help),
                    checked = skipCommercials,
                    onCheckedChange = {
                        skipCommercials = it
                        actions.onSkipCommercialsChanged(it)
                    },
                )
            }
        }
        item { SectionHeader(stringResource(Res.string.settings_section_captions)) }
        item {
            ToggleRow(
                label = stringResource(Res.string.captions_setting),
                checked = captionsOn,
                onCheckedChange = {
                    captionsOn = it
                    actions.onCaptionsChanged(it)
                },
            )
        }
        if (supportsCaptionSync) {
            item {
                ToggleRow(
                    label = stringResource(Res.string.caption_smooth),
                    help = stringResource(Res.string.caption_smooth_help),
                    checked = smoothCaptions,
                    onCheckedChange = {
                        smoothCaptions = it
                        actions.onSmoothCaptionsChanged(it)
                    },
                )
            }
        }
        item {
            ChoiceRow(
                label = stringResource(Res.string.caption_style),
                choices = CaptionStyle.entries,
                selected = captionStyle,
                choiceLabel = {
                    stringResource(
                        when (it) {
                            CaptionStyle.SYSTEM -> Res.string.caption_style_system
                            CaptionStyle.LARGE_HIGH_CONTRAST -> Res.string.caption_style_large
                        },
                    )
                },
                onSelect = {
                    captionStyle = it
                    actions.onCaptionStyleChanged(it)
                },
            )
        }
        if (supportsCaptionSync) {
            item {
                val lessLabel = stringResource(Res.string.settings_caption_timing_less)
                val moreLabel = stringResource(Res.string.settings_caption_timing_more)
                // A button that disables at its end of the range hands focus to the other, not to another row.
                val lessFocus = remember { FocusRequester() }
                val moreFocus = remember { FocusRequester() }
                SettingRow(label = stringResource(Res.string.caption_timing)) {
                    SofaButton(
                        onClick = {
                            captionAdvance = CaptionPolicy.stepAdvance(captionAdvance, direction = -1)
                            actions.onCaptionAdvanceChanged(captionAdvance)
                            if (captionAdvance <= AppSettings.CAPTION_ADVANCE_RANGE_MS.first) moreFocus.requestFocus()
                        },
                        enabled = captionAdvance > AppSettings.CAPTION_ADVANCE_RANGE_MS.first,
                        modifier = Modifier.focusRequester(lessFocus).semantics { contentDescription = lessLabel },
                    ) { Icon(SofaIcons.Remove, null) }
                    Text(captionTimingText(captionAdvance), style = MaterialTheme.typography.bodyLarge)
                    SofaButton(
                        onClick = {
                            captionAdvance = CaptionPolicy.stepAdvance(captionAdvance, direction = 1)
                            actions.onCaptionAdvanceChanged(captionAdvance)
                            if (captionAdvance >= AppSettings.CAPTION_ADVANCE_RANGE_MS.last) lessFocus.requestFocus()
                        },
                        enabled = captionAdvance < AppSettings.CAPTION_ADVANCE_RANGE_MS.last,
                        modifier = Modifier.focusRequester(moreFocus).semantics { contentDescription = moreLabel },
                    ) { Icon(Icons.Filled.Add, null) }
                }
            }
            item { HelpText(stringResource(Res.string.caption_timing_help)) }
        }
        item {
            // The platform draws the captions and keeps its own style page; the row opens it where there is one.
            val openSystemCaptions = rememberSystemCaptionSettings()
            Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (openSystemCaptions != null) {
                    SofaButton(onClick = openSystemCaptions) { Text(stringResource(Res.string.settings_caption_system)) }
                }
                HelpText(stringResource(Res.string.settings_caption_engine, captionEngineName()))
            }
        }
        if (supportsPlaybackPreferences) {
            item { SectionHeader(stringResource(Res.string.settings_section_playback)) }
            item {
                ToggleRow(
                    label = stringResource(Res.string.settings_match_frame_rate),
                    help = stringResource(Res.string.settings_match_frame_rate_help),
                    checked = matchFrameRate,
                    onCheckedChange = {
                        matchFrameRate = it
                        actions.onMatchFrameRateChanged(it)
                    },
                )
            }
            item {
                ChoiceRow(
                    label = stringResource(Res.string.settings_decoder),
                    help = stringResource(Res.string.settings_decoder_help),
                    choices = DecoderPreference.entries,
                    selected = decoder,
                    choiceLabel = {
                        stringResource(
                            when (it) {
                                DecoderPreference.AUTO -> Res.string.settings_decoder_auto
                                DecoderPreference.SOFTWARE -> Res.string.settings_decoder_software
                            },
                        )
                    },
                    onSelect = {
                        decoder = it
                        actions.onDecoderChanged(it)
                    },
                )
            }
            item {
                ChoiceRow(
                    label = stringResource(Res.string.settings_buffer),
                    help = stringResource(Res.string.settings_buffer_help),
                    choices = BufferPreference.entries,
                    selected = buffer,
                    choiceLabel = {
                        stringResource(
                            when (it) {
                                BufferPreference.FAST_START -> Res.string.settings_buffer_fast
                                BufferPreference.STANDARD -> Res.string.settings_buffer_standard
                                BufferPreference.SMOOTH -> Res.string.settings_buffer_smooth
                            },
                        )
                    },
                    onSelect = {
                        buffer = it
                        actions.onBufferChanged(it)
                    },
                )
            }
        }
        if (!unconfigured) serverSection()
        item { SectionHeader(stringResource(Res.string.settings_section_lock)) }
        item { LockSection(locked = settings.settingsLocked, onPinChanged = actions.onSettingsPinChanged) }
        // About: the app, its version and what is loaded, then where the build stands against the published snapshot.
        item { SectionHeader(stringResource(Res.string.settings_section_about)) }
        item {
            val suffix = versionSuffix()
            val version = BuildInfo.VERSION + suffix.orEmpty()
            // The suffix file is read before the check, so a snapshot compares as one.
            LaunchedEffect(suffix) { if (suffix != null) actions.onCheckForUpdate(version, false) }
            // The last buttons on the page sit above the TMDB notice, and a TV scrolls only as far as the focused
            // button, so focus anywhere in About brings the whole block, down to the page's last line, into view.
            val about = remember { BringIntoViewRequester() }
            val scope = rememberCoroutineScope()
            Column(
                Modifier
                    .width(FIELD_WIDTH)
                    .bringIntoViewRequester(about)
                    .onFocusChanged { if (it.hasFocus) scope.launch { about.bringIntoView() } },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    listOf(
                        stringResource(Res.string.app_name),
                        stringResource(Res.string.settings_version, version),
                        pluralStringResource(Res.plurals.settings_channels_loaded, channelCount, channelCount),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateRow(update = update, onCheckAgain = { actions.onCheckForUpdate(version, true) }, keepInView = about)
                // TMDB's terms ask apps that show its data to say so.
                Text(
                    stringResource(Res.string.settings_tmdb_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    }
}

private val noServers: Flow<List<DiscoveredServer>> = flowOf(emptyList())

/** Save & reload is on only for a valid address and token that differ from what is stored, since saving the same server reloads for nothing. */
internal fun canSaveServer(stored: AppSettings, url: String?, token: String?): Boolean =
    url != null && token != null && (url != stored.serverUrl || token != stored.apiToken)

/** Pair is on for a valid address and a six-digit code while no claim is running. */
internal fun canPairServer(url: String?, code: String?, busy: Boolean): Boolean = url != null && code != null && !busy

/**
 * The one state of a field's text, starting at the saved [stored] value; only a change to that value replaces what
 * is typed. The screen reads and resets the field through it.
 */
@Composable
private fun rememberStoredFieldState(stored: String): TextFieldState {
    val state = rememberTextFieldState(stored)
    LaunchedEffect(stored) {
        if (state.text.toString() != stored) state.setTextAndPlaceCursorAtEnd(stored)
    }
    return state
}

@Composable
private fun SettingField(
    label: String,
    state: TextFieldState,
    problem: String?,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    onAction: () -> Unit,
    maskUnfocused: ((String) -> String)? = null,
) {
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

/** The snapshot suffix the build wrote beside the resources, empty for a release; null until it has been read. */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun versionSuffix(): String? {
    val suffix by produceState<String?>(null) { value = Res.readBytes(BuildInfo.VERSION_SUFFIX_FILE).decodeToString().trim() }
    return suffix
}

/**
 * The update check's state as one line, with Check again, and a way to the newer snapshot when one is published:
 * Install update where the app installs it itself (Android), otherwise Get the update, which opens the platform's
 * package or the release page; where neither can open, the address is shown to type.
 */
@Composable
private fun UpdateRow(update: UpdateState?, onCheckAgain: () -> Unit, keepInView: BringIntoViewRequester) {
    val uriHandler = LocalUriHandler.current
    var address by remember { mutableStateOf<String?>(null) }
    val installer = rememberUpdateInstaller()
    val install = installer?.let { it.state.collectAsStateWithLifecycle().value }
    val latest = when (update) {
        is UpdateState.Available -> update.latest
        is UpdateState.UpToDate -> update.latest
        is UpdateState.Unknown -> update.latest
        else -> null
    }
    val downloadUrl = latest?.let(::updateDownloadUrl)
    // The status grows above the focused button, so the list is scrolled to keep the whole block in view as it changes.
    LaunchedEffect(install) { if (install != null && install != InstallState.Idle) keepInView.bringIntoView() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when (update) {
                null, UpdateState.Checking -> stringResource(Res.string.settings_update_checking)
                is UpdateState.Available -> stringResource(Res.string.settings_update_available, update.latest.version)
                is UpdateState.UpToDate -> stringResource(Res.string.settings_update_current)
                is UpdateState.Unknown -> stringResource(Res.string.settings_update_unknown, update.latest.version)
                UpdateState.Failed -> stringResource(Res.string.settings_update_failed)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        // Above the buttons, since a TV scrolls only as far as the focused button and text below it stays off screen.
        if (install != null && install != InstallState.Idle) HelpText(installText(install))
        Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (update is UpdateState.Available) {
                if (installer != null && update.latest.apkUrl != null) {
                    // Stays enabled while busy, since a disabled button drops focus to another row; install ignores the press.
                    SofaButton(onClick = { installer.install(update.latest) }) {
                        Text(stringResource(Res.string.settings_update_install))
                    }
                } else if (downloadUrl != null) {
                    SofaButton(onClick = { runCatching { uriHandler.openUri(downloadUrl) }.onFailure { address = downloadUrl } }) {
                        Text(stringResource(Res.string.settings_update_get))
                    }
                }
            }
            SofaButton(onClick = onCheckAgain, enabled = update != UpdateState.Checking) { Text(stringResource(Res.string.settings_update_check)) }
        }
        when {
            address != null -> HelpText(stringResource(Res.string.settings_update_address, address!!))
            update is UpdateState.Available && downloadUrl == null -> HelpText(stringResource(Res.string.settings_update_no_package))
        }
    }
}

/** What an in-app install is doing, or why it stopped. */
@Composable
private fun installText(install: InstallState): String = when (install) {
    InstallState.Idle -> ""
    is InstallState.Downloading -> install.percent?.let { stringResource(Res.string.settings_update_downloading_percent, "$it%") }
        ?: stringResource(Res.string.settings_update_downloading)
    InstallState.Installing -> stringResource(Res.string.settings_update_installing)
    is InstallState.Failed -> when (install.failure) {
        InstallFailure.DOWNLOAD -> stringResource(Res.string.settings_update_failed_download)
        InstallFailure.CHECKSUM -> stringResource(Res.string.settings_update_failed_checksum)
        InstallFailure.NOT_THIS_APP -> stringResource(Res.string.settings_update_failed_package)
        InstallFailure.DIFFERENT_KEY -> stringResource(Res.string.settings_update_failed_key)
        InstallFailure.NOT_NEWER -> stringResource(Res.string.settings_update_failed_version)
        InstallFailure.INSTALLER -> install.detail?.let { stringResource(Res.string.settings_update_failed_installer, it) }
            ?: stringResource(Res.string.settings_update_failed_installer_silent)
    }
}

/** What Settings' `returnedFrom` holds for the channel manager and the startup channel picker. */
internal const val CHANNELS_FOCUS_KEY = "channels"
internal const val STARTUP_FOCUS_KEY = "startup-channel"
