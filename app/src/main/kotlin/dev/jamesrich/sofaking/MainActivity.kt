package dev.jamesrich.sofaking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withStateAtLeast
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.ui.LogoMark
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.PopIn
import dev.jamesrich.sofaking.ui.PopOut
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.guide.GuideScreen
import dev.jamesrich.sofaking.ui.player.CaptionSync
import dev.jamesrich.sofaking.ui.player.buildLivePlayer
import dev.jamesrich.sofaking.ui.player.captionParameters
import dev.jamesrich.sofaking.ui.player.currentChannelId
import dev.jamesrich.sofaking.ui.player.PlayerScreen
import dev.jamesrich.sofaking.ui.player.withStream
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.settings.SettingsScreen
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SofaKingTheme {
                Surface(Modifier) { SofaKingApp() }
            }
        }
    }
}

private sealed interface Screen {
    /** [atNow] moves the guide's cursor to the current time on arrival (the Guide key from the player). */
    data class Guide(val atNow: Boolean = false) : Screen
    data class Player(val guideId: String) : Screen
    data object Settings : Screen
}

@Composable
private fun SofaKingApp(vm: LineupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Guide()) }
    // A short word from the player on its way back to the guide: the channel it was asked for is gone.
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(NOTICE_MS)
        notice = null
    }
    val guideState = rememberSaveableStateHolder()
    val lineup = (state as? LineupState.Ready)?.lineup
    // A cold start opens the channel last watched, full screen, once the lineup and settings are in. Decided
    // once per process (restored across configuration changes), and before the guide composes, so the preview
    // never opens a session that the full screen is about to replace.
    var launchDecided by rememberSaveable { mutableStateOf(false) }
    val launch = launchTarget(state, settings)
    LaunchedEffect(launch, launchDecided) {
        if (launchDecided || launch == LaunchTarget.Undecided) return@LaunchedEffect
        launchDecided = true
        if (launch is LaunchTarget.Player) screen = Screen.Player(launch.guideId)
    }

    // One player for the guide preview and the full screen, so OK on the previewed channel continues its stream,
    // and one server session behind it, promoted from preview to watch in place.
    val context = LocalContext.current
    val captionSync = remember { CaptionSync() }
    val player = remember { buildLivePlayer(context, captionSync) }
    val sessions = vm.sessions
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) { onDispose { player.release() } }
    // The server replaced the stream under the held session (new epoch, or a session it dropped and the app
    // reopened): the player reopens the item it holds for that channel at the new URL, metadata intact.
    LaunchedEffect(sessions, player) {
        sessions.replaced.collect { stream ->
            lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
                val item = player.currentMediaItem ?: return@withStateAtLeast
                if (item.mediaId != stream.channelId || item.localConfiguration?.uri?.toString() == stream.url) return@withStateAtLeast
                player.stop()
                captionSync.effectiveUs = captionSync.advanceUs
                player.setMediaItem(item.withStream(stream))
                player.prepare()
            }
        }
    }
    // Before the first prepare: switching text tracks on a playing live stream restarts it.
    val captionsEnabled = settings?.captionsEnabled
    LaunchedEffect(captionsEnabled) {
        if (captionsEnabled != null) player.trackSelectionParameters = captionParameters(player.trackSelectionParameters, captionsEnabled)
    }
    // The preview starts without a caption lead; the full screen sets each channel's own. Settings may change
    // the server, so it releases the session outright rather than keeping it to reopen.
    LaunchedEffect(screen) {
        when (screen) {
            is Screen.Guide -> captionSync.advanceUs = 0L
            Screen.Settings -> {
                player.stop()
                sessions.release(forget = true)
            }
            is Screen.Player -> Unit
        }
    }
    // Stopped, not paused: the TV's one hardware decoder and the server's session go free while another app is
    // in front. The item and playWhenReady survive; on return a fresh session is opened for the same channel
    // and intent and the item is prepared at its URL, at the live edge or still paused.
    val currentScreen by rememberUpdatedState(screen)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player.stop()
        scope.launch { sessions.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (currentScreen != Screen.Settings && player.mediaItemCount > 0 && player.playbackState == Player.STATE_IDLE) {
            captionSync.effectiveUs = captionSync.advanceUs
            scope.launch {
                val stream = try {
                    sessions.reopen()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Preparing the stale item fails on the player, where the screen's own reconnect takes over.
                    null
                }
                val item = player.currentMediaItem ?: return@launch
                if (stream != null && stream.channelId == player.currentChannelId) player.setMediaItem(item.withStream(stream))
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
            }
        }
    }

    when (val current = screen) {
        Screen.Settings -> settings?.let { s ->
            SettingsScreen(
                settings = s,
                onSave = { url, token -> vm.saveServer(url, token); screen = Screen.Guide() },
                onBack = { screen = Screen.Guide() },
                channelCount = lineup?.channels?.size ?: 0,
                onCaptionsChanged = { vm.setCaptionsEnabled(it) },
                onCaptionAdvanceChanged = { vm.setCaptionAdvanceMs(it) },
                onSmoothCaptionsChanged = { vm.setSmoothCaptions(it) },
                onCaptionStyleChanged = { vm.setCaptionStyle(it) },
            )
        }
        is Screen.Player -> if (lineup != null) {
            val start = lineup.channels.indexOfFirst { it.guideId == current.guideId }.coerceAtLeast(0)
            PlayerScreen(
                lineup = lineup,
                startIndex = start,
                player = player,
                sessions = sessions,
                captionSync = captionSync,
                onExit = { screen = Screen.Guide() },
                onGuide = { screen = Screen.Guide(atNow = true) },
                onChannelChanged = { vm.setLastChannel(it.guideId) },
                reloadLineup = vm::reloadLineup,
                onChannelGone = { channel ->
                    notice = context.getString(R.string.channel_gone, channel.label())
                    screen = Screen.Guide()
                },
                captionsEnabled = settings?.captionsEnabled ?: false,
                onCaptionsChanged = { vm.setCaptionsEnabled(it) },
                captionAdvanceFor = { settings?.captionAdvanceFor(it.guideId) ?: AppSettings.DEFAULT_CAPTION_ADVANCE_MS },
                onCaptionAdvanceChanged = { channel, ms -> vm.setChannelCaptionAdvance(channel.guideId, ms) },
                captionStyle = settings?.captionStyle ?: CaptionStyle.SYSTEM,
                smoothCaptions = settings?.smoothCaptions ?: true,
            )
        } else {
            LaunchedEffect(Unit) { screen = Screen.Guide() }
        }
        // The guide's saveable state (filter, cursor, scroll, focused channel) outlives a trip to the player.
        is Screen.Guide -> guideState.SaveableStateProvider("guide") {
            Column {
                val settingsFocus = remember { FocusRequester() }
                Header(
                    refreshing = loading && state is LineupState.Ready,
                    notice = notice,
                    onSettings = { screen = Screen.Settings },
                    onRefresh = vm::refresh,
                    settingsFocus = settingsFocus,
                )
                // With no server to talk to, Settings is the only thing to do, so it takes focus over Refresh.
                val homeFocus = homeFocusFor(state)
                LaunchedEffect(homeFocus) {
                    if (homeFocus == HomeFocus.SETTINGS) requestFocusWhenAttached(settingsFocus)
                }
                // Settings arrive a beat after the cached lineup; the guide waits for them so it can open on the last channel.
                val s = state
                when {
                    s is LineupState.Loading || settings == null || !launchDecided -> MessageScreen(stringResource(R.string.loading), loading = true)
                    s is LineupState.Unconfigured -> MessageScreen(
                        message = stringResource(R.string.unconfigured),
                        hint = stringResource(R.string.unconfigured_hint),
                    )
                    s is LineupState.Failed -> MessageScreen(
                        message = stringResource(R.string.load_failed),
                        detail = s.message,
                        hint = stringResource(R.string.load_failed_hint),
                        onRetry = vm::refresh,
                    )
                    s is LineupState.Ready -> GuideOrEmpty(
                        lineup = s.lineup,
                        favorites = settings?.favorites.orEmpty(),
                        lastChannel = settings?.lastChannel,
                        startAtNow = current.atNow,
                        onPlay = { screen = Screen.Player(it) },
                        onToggleFavorite = vm::toggleFavorite,
                        previewPlayer = player,
                        sessions = sessions,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideOrEmpty(
    lineup: Lineup,
    favorites: Set<String>,
    lastChannel: String?,
    startAtNow: Boolean,
    onPlay: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    previewPlayer: ExoPlayer,
    sessions: PlaybackSessions,
) {
    if (lineup.channels.isEmpty()) {
        MessageScreen(stringResource(R.string.no_channels), hint = stringResource(R.string.no_channels_hint))
    } else {
        GuideScreen(
            lineup = lineup,
            favorites = favorites,
            initialChannelId = lastChannel,
            startAtNow = startAtNow,
            onPlay = { onPlay(it.guideId) },
            onToggleFavorite = { onToggleFavorite(it.guideId) },
            previewPlayer = previewPlayer,
            sessions = sessions,
        )
    }
}

/** App bar above the guide, inside the overscan margins; the mark sits within the buttons' height so no grid row is lost. */
@Composable
private fun Header(refreshing: Boolean, notice: String?, onSettings: () -> Unit, onRefresh: () -> Unit, settingsFocus: FocusRequester) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 27.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoMark(40.dp)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        // The lineup on screen stays usable while it refreshes; the loader only says new data is on its way.
        AnimatedVisibility(
            visible = refreshing,
            enter = PopIn,
            exit = PopOut,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SofaLoader(size = 28.dp)
                Text(
                    stringResource(R.string.refreshing),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = notice != null, enter = PopIn, exit = PopOut) {
            Text(
                notice.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
        Button(onClick = onSettings, modifier = Modifier.focusRequester(settingsFocus)) { Text(stringResource(R.string.settings)) }
    }
}

private const val NOTICE_MS = 5_000L

private val ScreenSaver = Saver<Screen, String>(
    save = {
        when (it) {
            is Screen.Guide -> if (it.atNow) "guide:now" else "guide"
            Screen.Settings -> "settings"
            is Screen.Player -> "player:${it.guideId}"
        }
    },
    restore = {
        when {
            it == "settings" -> Screen.Settings
            it.startsWith("player:") -> Screen.Player(it.removePrefix("player:"))
            it == "guide:now" -> Screen.Guide(atNow = true)
            else -> Screen.Guide()
        }
    },
)
