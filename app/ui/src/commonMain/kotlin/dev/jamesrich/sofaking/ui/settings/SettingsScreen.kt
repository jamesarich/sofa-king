package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.BuildInfo
import dev.jamesrich.sofaking.PairingFailure
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.BufferPreference
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.ClockFormat
import dev.jamesrich.sofaking.data.DecoderPreference
import dev.jamesrich.sofaking.data.DiscoveredServer
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.data.InstallFailure
import dev.jamesrich.sofaking.data.InstallState
import dev.jamesrich.sofaking.data.LiveBreaks
import dev.jamesrich.sofaking.data.ReleaseNote
import dev.jamesrich.sofaking.data.SeekSteps
import dev.jamesrich.sofaking.data.SkipKind
import dev.jamesrich.sofaking.data.SkipMode
import dev.jamesrich.sofaking.data.StartupTarget
import dev.jamesrich.sofaking.data.SubtitleLook
import dev.jamesrich.sofaking.data.UpdateInstaller
import dev.jamesrich.sofaking.data.UpdateState
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.app_name
import dev.jamesrich.sofaking.resources.licenses_title
import dev.jamesrich.sofaking.resources.settings
import dev.jamesrich.sofaking.resources.settings_channels_loaded
import dev.jamesrich.sofaking.resources.settings_section_about
import dev.jamesrich.sofaking.resources.settings_section_lock
import dev.jamesrich.sofaking.resources.settings_title
import dev.jamesrich.sofaking.resources.settings_tmdb_notice
import dev.jamesrich.sofaking.resources.settings_update_address
import dev.jamesrich.sofaking.resources.settings_update_available
import dev.jamesrich.sofaking.resources.settings_update_check
import dev.jamesrich.sofaking.resources.settings_update_checking
import dev.jamesrich.sofaking.resources.settings_update_current
import dev.jamesrich.sofaking.resources.settings_update_downloading
import dev.jamesrich.sofaking.resources.settings_update_downloading_percent
import dev.jamesrich.sofaking.resources.settings_update_failed
import dev.jamesrich.sofaking.resources.settings_update_failed_checksum
import dev.jamesrich.sofaking.resources.settings_update_failed_download
import dev.jamesrich.sofaking.resources.settings_update_failed_installer
import dev.jamesrich.sofaking.resources.settings_update_failed_installer_silent
import dev.jamesrich.sofaking.resources.settings_update_failed_key
import dev.jamesrich.sofaking.resources.settings_update_failed_package
import dev.jamesrich.sofaking.resources.settings_update_failed_version
import dev.jamesrich.sofaking.resources.settings_update_get
import dev.jamesrich.sofaking.resources.settings_update_install
import dev.jamesrich.sofaking.resources.settings_update_installing
import dev.jamesrich.sofaking.resources.settings_update_more
import dev.jamesrich.sofaking.resources.settings_update_no_package
import dev.jamesrich.sofaking.resources.settings_update_unknown
import dev.jamesrich.sofaking.resources.settings_update_whats_new
import dev.jamesrich.sofaking.resources.settings_version
import dev.jamesrich.sofaking.resources.unconfigured
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.ScrollOnlyWhenHidden
import dev.jamesrich.sofaking.ui.rememberUpdateInstaller
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.supportsNightListening
import dev.jamesrich.sofaking.ui.supportsPlaybackPreferences
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.updateDownloadUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The switches and choices Settings applies at once; each lands in the store as it is changed. */
class SettingsActions(
    val onCaptionsChange: (Boolean) -> Unit = {},
    val onCaptionAdvanceChange: (Int) -> Unit = {},
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
    val onNightListeningChanged: (Boolean) -> Unit = {},
    val onDecoderChanged: (DecoderPreference) -> Unit = {},
    val onBufferChanged: (BufferPreference) -> Unit = {},
    /** A new PIN for the lock, or null to remove it. */
    val onSettingsPinChanged: (String?) -> Unit = {},
    /** Turns kids mode on, which leaves Settings locked, or off. */
    val onKidsModeChanged: (Boolean) -> Unit = {},
    /** Compares the running [version] with the published snapshot; [again] repeats a check already made. */
    val onCheckForUpdate: (version: String, again: Boolean) -> Unit = { _, _ -> },
    val onSkipChanged: (SkipKind, SkipMode) -> Unit = { _, _ -> },
    val onSeekStepsChanged: (SeekSteps) -> Unit = {},
    val onHideSpoilersChanged: (Boolean) -> Unit = {},
    val onSubtitleLookChanged: (SubtitleLook) -> Unit = {},
    val onLiveBreaksChanged: (LiveBreaks) -> Unit = {},
    /** Loads the lineup and the library home again from the server. */
    val onReload: () -> Unit = {},
    /** Opens the open-source licenses page. */
    val onOpenLicenses: () -> Unit = {},
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
    /**
     * The page behind Settings the viewer just came back from ([CHANNELS_FOCUS_KEY],
     * [STARTUP_FOCUS_KEY], [LICENSES_FOCUS_KEY]); its button takes focus.
     */
    returnedFrom: String? = null,
    /** The servers on the network, collected (and so browsed for) only while the server fields are open. */
    discoveredServers: Flow<List<DiscoveredServer>> = noServers,
) {
    var startup by rememberShown(settings.startup)
    var subtitleLook by rememberShown(settings.subtitleLook)
    val server = rememberServerFields(settings, pairing)
    val serverOpen = server.changing
    val unconfigured = remember { settings.serverUrl.isEmpty() }
    val found by remember(serverOpen) {
        if (serverOpen) discoveredServers else noServers
    }.collectAsStateWithLifecycle(emptyList())
    val focus = remember { SettingsFocus() }

    BackHandler { onBack() }
    ArrivalFocus(returnedFrom, focus, if (settings.serverUrl.isEmpty()) server.serverFocus else focus.first())

    val serverSection: LazyListScope.() -> Unit = {
        serverSection(
            server,
            serverOpen,
            settings,
            pairing,
            found,
            serverUnreachable,
            reloading,
            actions.onReload,
            onSave,
        )
    }
    // Focus scrolls only to show what it lands on, so the first setting taking focus keeps the title on screen.
    ScrollOnlyWhenHidden(SETTINGS_FOCUS_EDGE) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .screenPadding()
                .focusRequester(focus.content)
                .focusRestorer()
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { ScreenTitle(stringResource(Res.string.settings_title), onBack, focus.back) }
            // A device with no server starts here; a configured one keeps the server near
            // the end, as it seldom changes.
            if (unconfigured) serverSection()
            generalSection(settings, actions, startup, { startup = it }, channels, libraryOffered, focus.startup)
            guideSection(settings, actions, channels, focus.channels)
            if (recordingsOffered) recordingsSection(settings, actions, withSeekSteps = !libraryOffered)
            if (libraryOffered) {
                librarySection(settings, actions, subtitleLook) { look ->
                    subtitleLook = look
                    actions.onSubtitleLookChanged(look)
                }
            }
            captionsSection(settings, actions)
            if (supportsPlaybackPreferences || supportsNightListening) playbackSection(settings, actions)
            if (!unconfigured) serverSection()
            item { SectionHeader(stringResource(Res.string.settings_section_lock)) }
            item { LockSection(settings, actions.onSettingsPinChanged, actions.onKidsModeChanged) }
            // About: the app, its version and what is loaded, then where the build stands
            // against the published snapshot.
            item { SectionHeader(stringResource(Res.string.settings_section_about)) }
            item { AboutBlock(channelCount, update, actions, focus.licenses) }
        }
    }
}

/** Where focus goes on Settings: Back, the list itself, and the buttons of the pages behind it. */
private class SettingsFocus {
    val back = FocusRequester()
    val content = FocusRequester()
    val channels = FocusRequester()
    val startup = FocusRequester()
    val licenses = FocusRequester()

    /** Back, or where no Back button is drawn, the first setting. */
    @Composable
    fun first(): FocusRequester = if (showsBackButton()) back else content
}

/** Focus on arrival: the button of the page the viewer came back from, else [initial]. */
@Composable
private fun ArrivalFocus(returnedFrom: String?, focus: SettingsFocus, initial: FocusRequester) {
    LaunchedEffect(Unit) {
        val returnTo = when (returnedFrom) {
            CHANNELS_FOCUS_KEY -> focus.channels
            STARTUP_FOCUS_KEY -> focus.startup
            LICENSES_FOCUS_KEY -> focus.licenses
            else -> null
        }
        if (returnTo != null && requestFocusWhenAttached(returnTo)) return@LaunchedEffect
        requestFocusWhenAttached(initial)
    }
}

/**
 * About: the app, its version and the channels loaded, the TMDB notice, the way to the open-source licenses and the
 * update check, which runs once the version is known.
 */
@Composable
private fun AboutBlock(
    channelCount: Int,
    update: UpdateState?,
    actions: SettingsActions,
    licensesFocus: FocusRequester,
) {
    val onCheckForUpdate = actions.onCheckForUpdate
    val suffix = versionSuffix()
    val version = BuildInfo.VERSION + suffix.orEmpty()
    val currentOnCheckForUpdate by rememberUpdatedState(onCheckForUpdate)
    // The suffix file is read before the check, so a snapshot compares as one.
    LaunchedEffect(suffix) { if (suffix != null) currentOnCheckForUpdate(version, false) }
    // A TV scrolls only as far as the focused button, so the TMDB notice sits above the update check's
    // buttons, which end the page, and focus anywhere in About brings the whole block into view.
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
        // TMDB's terms ask apps that show its data to say so.
        Text(
            stringResource(Res.string.settings_tmdb_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SofaButton(onClick = actions.onOpenLicenses, modifier = Modifier.focusRequester(licensesFocus)) {
            Text(stringResource(Res.string.licenses_title))
        }
        UpdateRow(update = update, onCheckAgain = { onCheckForUpdate(version, true) }, keepInView = about)
    }
}

private val noServers: Flow<List<DiscoveredServer>> = flowOf(emptyList())

/** The snapshot suffix the build wrote beside the resources, empty for a release; null until it has been read. */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun versionSuffix(): String? {
    val suffix by produceState<String?>(null) {
        value =
            Res.readBytes(BuildInfo.VERSION_SUFFIX_FILE).decodeToString().trim()
    }
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
        Text(updateStatus(update), style = MaterialTheme.typography.bodyMedium)
        // Above the buttons, since a TV scrolls only as far as the focused button and text below it stays off screen.
        if (update is UpdateState.Available && update.changes.isNotEmpty()) WhatsNew(update.changes)
        if (install != null && install != InstallState.Idle) HelpText(installText(install))
        UpdateButtons(
            update,
            installer,
            onGet = downloadUrl?.let { url -> { runCatching { uriHandler.openUri(url) }.onFailure { address = url } } },
            onCheckAgain = onCheckAgain,
        )
        when {
            address != null -> HelpText(stringResource(Res.string.settings_update_address, address!!))

            update is UpdateState.Available && downloadUrl == null -> HelpText(
                stringResource(Res.string.settings_update_no_package),
            )
        }
    }
}

@Composable
private fun updateStatus(update: UpdateState?): String = when (update) {
    null, UpdateState.Checking -> stringResource(Res.string.settings_update_checking)
    is UpdateState.Available -> stringResource(Res.string.settings_update_available, update.latest.version)
    is UpdateState.UpToDate -> stringResource(Res.string.settings_update_current)
    is UpdateState.Unknown -> stringResource(Res.string.settings_update_unknown, update.latest.version)
    UpdateState.Failed -> stringResource(Res.string.settings_update_failed)
}

/**
 * For an available update, Install where [installer] can put it on this device, else Get, which [onGet] opens when
 * there is a package to open; then Check again.
 */
@Composable
private fun UpdateButtons(
    update: UpdateState?,
    installer: UpdateInstaller?,
    onGet: (() -> Unit)?,
    onCheckAgain: () -> Unit,
) {
    Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (update is UpdateState.Available) {
            if (installer != null && update.latest.apkUrl != null) {
                // Stays enabled while busy, since a disabled button drops focus to another
                // row; install ignores the press.
                SofaButton(onClick = { installer.install(update.latest) }) {
                    Text(stringResource(Res.string.settings_update_install))
                }
            } else if (onGet != null) {
                SofaButton(onClick = onGet) {
                    Text(stringResource(Res.string.settings_update_get))
                }
            }
        }
        SofaButton(onClick = onCheckAgain, enabled = update != UpdateState.Checking) {
            Text(stringResource(Res.string.settings_update_check))
        }
    }
}

/**
 * The published snapshot's new features, then its fixes, for the builds newer than this one, as at most
 * [WHATS_NEW_LINES] lines and a count of the rest, so the buttons below stay on a TV's screen.
 */
@Composable
private fun WhatsNew(changes: List<ReleaseNote>) {
    val ordered = changes.sortedBy { it.fix }
    val shown = ordered.take(WHATS_NEW_LINES)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.settings_update_whats_new), style = MaterialTheme.typography.titleSmall)
        shown.forEach { HelpText("• ${it.text}") }
        val rest = ordered.size - shown.size
        if (rest > 0) HelpText(pluralStringResource(Res.plurals.settings_update_more, rest, rest))
    }
}

private const val WHATS_NEW_LINES = 6

/** What an in-app install is doing, or why it stopped. */
@Composable
private fun installText(install: InstallState): String = when (install) {
    InstallState.Idle -> ""

    is InstallState.Downloading -> install.percent?.let {
        stringResource(Res.string.settings_update_downloading_percent, "$it%")
    }
        ?: stringResource(Res.string.settings_update_downloading)

    InstallState.Installing -> stringResource(Res.string.settings_update_installing)

    is InstallState.Failed -> when (install.failure) {
        InstallFailure.DOWNLOAD -> stringResource(Res.string.settings_update_failed_download)

        InstallFailure.CHECKSUM -> stringResource(Res.string.settings_update_failed_checksum)

        InstallFailure.NOT_THIS_APP -> stringResource(Res.string.settings_update_failed_package)

        InstallFailure.DIFFERENT_KEY -> stringResource(Res.string.settings_update_failed_key)

        InstallFailure.NOT_NEWER -> stringResource(Res.string.settings_update_failed_version)

        InstallFailure.INSTALLER -> install.detail?.let {
            stringResource(Res.string.settings_update_failed_installer, it)
        }
            ?: stringResource(Res.string.settings_update_failed_installer_silent)
    }
}

/** What Settings' `returnedFrom` holds for the channel manager and the startup channel picker. */
internal const val CHANNELS_FOCUS_KEY = "channels"
internal const val STARTUP_FOCUS_KEY = "startup-channel"
