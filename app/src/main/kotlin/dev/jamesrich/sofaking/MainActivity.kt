package dev.jamesrich.sofaking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.ui.LogoMark
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.guide.GuideScreen
import dev.jamesrich.sofaking.ui.player.PlayerScreen
import dev.jamesrich.sofaking.ui.settings.SettingsScreen
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme

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
    data object Guide : Screen
    data class Player(val guideId: String) : Screen
    data object Settings : Screen
}

@Composable
private fun SofaKingApp(vm: LineupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Guide) }
    val guideState = rememberSaveableStateHolder()
    val lineup = (state as? LineupState.Ready)?.lineup

    when (val current = screen) {
        Screen.Settings -> settings?.let { s ->
            SettingsScreen(
                settings = s,
                onSave = { url, profile -> vm.saveServer(url, profile); screen = Screen.Guide },
                onBack = { screen = Screen.Guide },
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
                onExit = { screen = Screen.Guide },
                onChannelChanged = { vm.setLastChannel(it.guideId) },
                captionsEnabled = settings?.captionsEnabled ?: false,
                onCaptionsChanged = { vm.setCaptionsEnabled(it) },
                captionAdvanceFor = { settings?.captionAdvanceFor(it.guideId) ?: AppSettings.DEFAULT_CAPTION_ADVANCE_MS },
                onCaptionAdvanceChanged = { channel, ms -> vm.setChannelCaptionAdvance(channel.guideId, ms) },
                captionStyle = settings?.captionStyle ?: CaptionStyle.SYSTEM,
                smoothCaptions = settings?.smoothCaptions ?: true,
            )
        } else {
            LaunchedEffect(Unit) { screen = Screen.Guide }
        }
        // The guide's saveable state (filter, cursor, scroll, focused channel) outlives a trip to the player.
        Screen.Guide -> guideState.SaveableStateProvider("guide") {
            Column {
                Header(onSettings = { screen = Screen.Settings }, onRefresh = vm::refresh)
                when (val s = state) {
                    LineupState.Loading -> MessageScreen(stringResource(R.string.loading))
                    is LineupState.Failed -> MessageScreen(
                        message = stringResource(R.string.load_failed),
                        detail = s.message,
                        hint = stringResource(R.string.load_failed_hint),
                        onRetry = vm::refresh,
                    )
                    is LineupState.Ready -> GuideOrEmpty(
                        lineup = s.lineup,
                        favorites = settings?.favorites.orEmpty(),
                        lastChannel = settings?.lastChannel,
                        onPlay = { screen = Screen.Player(it) },
                        onToggleFavorite = vm::toggleFavorite,
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
    onPlay: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    if (lineup.channels.isEmpty()) {
        MessageScreen(stringResource(R.string.no_channels), hint = stringResource(R.string.no_channels_hint))
    } else {
        GuideScreen(
            lineup = lineup,
            favorites = favorites,
            initialChannelId = lastChannel,
            onPlay = { onPlay(it.guideId) },
            onToggleFavorite = { onToggleFavorite(it.guideId) },
        )
    }
}

/** App bar above the guide, inside the overscan margins; the mark sits within the buttons' height so no grid row is lost. */
@Composable
private fun Header(onSettings: () -> Unit, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 27.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoMark(40.dp)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        Button(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
        Button(onClick = onSettings) { Text(stringResource(R.string.settings)) }
    }
}

private val ScreenSaver = Saver<Screen, String>(
    save = {
        when (it) {
            Screen.Guide -> "guide"
            Screen.Settings -> "settings"
            is Screen.Player -> "player:${it.guideId}"
        }
    },
    restore = {
        when {
            it == "settings" -> Screen.Settings
            it.startsWith("player:") -> Screen.Player(it.removePrefix("player:"))
            else -> Screen.Guide
        }
    },
)
