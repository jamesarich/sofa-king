package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.multiview_busy
import dev.jamesrich.sofaking.resources.multiview_busy_hint
import dev.jamesrich.sofaking.resources.multiview_failed
import dev.jamesrich.sofaking.resources.multiview_failed_hint
import dev.jamesrich.sofaking.resources.multiview_keys_hint
import dev.jamesrich.sofaking.resources.multiview_opening
import dev.jamesrich.sofaking.resources.multiview_sound
import dev.jamesrich.sofaking.resources.multiview_touch_hint
import dev.jamesrich.sofaking.resources.multiview_unavailable
import dev.jamesrich.sofaking.resources.multiview_unavailable_hint
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.KeepScreenOn
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The multiview screen's test tag, as the player's is. */
const val MULTIVIEW_TEST_TAG = "multiview"

/** The picture's shape, which the server draws at. */
private const val PICTURE_ASPECT = 16f / 9f

/**
 * Multiview: a picker of two to four channels from [lineup], starting with [initial], then the one picture the server
 * draws them into on [player]. On the picture the arrows or a tap move the sound between the channels, OK or a double
 * tap watches the one with the sound full screen ([onWatch]), and Back returns to the picker, whose Back leaves
 * ([onExit]). The picker lists the channels picked, the starred ones in [favoritesOrder], the [recent] ones, then the
 * rest.
 */
@Composable
fun MultiviewScreen(
    lineup: Lineup,
    initial: List<String>,
    player: LivePlayer,
    sessions: PlaybackSessions,
    onWatch: (Channel) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    favoritesOrder: List<String> = emptyList(),
    recent: List<String> = emptyList(),
) {
    var picked by remember { mutableStateOf(initial.take(Wire.MULTIVIEW_MAX_TILES)) }
    var watching by remember { mutableStateOf<List<String>?>(null) }
    val play = rememberMultiviewPlay(player, sessions)
    // The player keeps this stream's channel tag; stopped, the live player that takes over tunes afresh.
    val leave = { action: () -> Unit ->
        if (sessions.current.value?.isMultiview == true) player.stop()
        action()
    }
    MultiviewPlayerEffects(player, play, active = watching != null)
    BackHandler {
        if (watching != null) watching = null else leave(onExit)
    }
    Box(modifier.fillMaxSize().background(SofaScrim).testTag(MULTIVIEW_TEST_TAG)) {
        player.Video(Modifier.fillMaxSize(), VideoZoom.FIT) { Box(Modifier.fillMaxSize().background(SofaScrim)) }
        val tiles = watching
        if (tiles == null) {
            MultiviewPicker(
                choices = remember(lineup, favoritesOrder, recent) {
                    multiviewChoices(lineup.channels, initial, favoritesOrder, recent)
                },
                picked = picked,
                onToggle = { picked = togglePick(picked, it.guideId) },
                onWatch = {
                    if (picked != play.channelIds) play.start(picked, play.audio.coerceAtMost(picked.size - 1))
                    watching = picked
                },
            )
        } else {
            val channels = tiles.mapNotNull { id -> lineup.channels.firstOrNull { it.guideId == id } }
            MultiviewPicture(channels, play, onWatch = { channel -> leave { onWatch(channel) } })
        }
    }
}

/** The player's volume, its failures and the screen staying on, while a multiview plays ([active]). */
@Composable
private fun MultiviewPlayerEffects(player: LivePlayer, play: MultiviewPlay, active: Boolean) {
    val volume = LocalPlayerVolume.current
    val currentActive by rememberUpdatedState(active)
    LaunchedEffect(player, volume) { snapshotFlow { volume.gain }.collect { player.volume = it } }
    LaunchedEffect(player, play) {
        player.errors.collect { if (currentActive) play.playerFailed(it.code) }
    }
    val status by player.status.collectAsStateWithLifecycle()
    KeepScreenOn(status.isPlaying)
}

/**
 * The picture of [channels], each in its tile: the keys and taps move the sound, and the tiles' names show for a
 * while after each. A failure covers it with why, until the next try.
 */
@Composable
private fun MultiviewPicture(channels: List<Channel>, play: MultiviewPlay, onWatch: (Channel) -> Unit) {
    val focus = remember { FocusRequester() }
    var touched by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus) }
    val labels by produceLabels(touched)
    val count = channels.size
    val audio = play.audio.coerceIn(0, (count - 1).coerceAtLeast(0))
    val audioChannel = channels.getOrNull(audio)
    val soundTemplate = stringResource(Res.string.multiview_sound)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .semantics {
                audioChannel?.let { contentDescription = formatTemplate(soundTemplate, it.label()) }
            }
            .multiviewKeys(count, audio, { touched++ }, play::moveAudio) { audioChannel?.let(onWatch) },
    ) {
        val width = min(maxWidth, maxHeight * PICTURE_ASPECT)
        val height = width / PICTURE_ASPECT
        Box(
            Modifier
                .align(Alignment.Center)
                .size(width, height)
                .pointerInput(count) {
                    detectTapGestures(
                        onTap = { at ->
                            touched++
                            multiviewTileAt(count, at.x / size.width, at.y / size.height)?.let(play::moveAudio)
                        },
                        onDoubleTap = { at ->
                            multiviewTileAt(count, at.x / size.width, at.y / size.height)
                                ?.let { channels.getOrNull(it) }
                                ?.let(onWatch)
                        },
                    )
                },
        ) {
            channels.forEachIndexed { index, channel ->
                val tile = multiviewTile(count, index)
                val place = Modifier.offset(width * tile.left, height * tile.top)
                Box(place.size(width * tile.width, height * tile.height)) {
                    if (labels || index == audio) TileLabel(channel, sound = index == audio, full = labels)
                }
            }
        }
        MultiviewStatus(play, hint = labels, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * An arrow moves the sound from tile [audio] of [count] by [onAudio], after [onMove]; OK, on its key-up, watches the
 * tile with the sound full screen.
 */
private fun Modifier.multiviewKeys(
    count: Int,
    audio: Int,
    onMove: () -> Unit,
    onAudio: (Int) -> Unit,
    onWatch: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val key = event.key
    when {
        key in ARROWS && event.type == KeyEventType.KeyDown -> {
            onMove()
            onAudio(multiviewMove(count, audio, key))
            true
        }

        key in SELECT && event.type == KeyEventType.KeyUp -> {
            onWatch()
            true
        }

        else -> false
    }
}

/** True for [BAR_IDLE_MS] after each press or tap, counted by [touched], and at first. */
@Composable
private fun produceLabels(touched: Int): androidx.compose.runtime.State<Boolean> {
    val shown = remember { mutableStateOf(true) }
    LaunchedEffect(touched) {
        shown.value = true
        delay(BAR_IDLE_MS)
        shown.value = false
    }
    return shown
}

/** A tile's channel, and a speaker on the one whose sound plays; only the speaker once the names fade ([full]). */
@Composable
private fun TileLabel(channel: Channel, sound: Boolean, full: Boolean) {
    Row(
        Modifier.padding(
            12.dp,
        ).background(SofaOverlayScrim, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sound) {
            Icon(SofaIcons.VolumeUp, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        if (full) {
            Text(
                channel.label(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Why the multiview is not playing, or the keys' hint under the picture while the names show ([hint]). */
@Composable
private fun MultiviewStatus(play: MultiviewPlay, hint: Boolean, modifier: Modifier = Modifier) {
    val failure = play.failure
    val text: Pair<StringResource, StringResource?>? = when {
        failure == null && play.opening -> Res.string.multiview_opening to null
        failure == null -> null
        failure == MULTIVIEW_UNAVAILABLE -> Res.string.multiview_unavailable to Res.string.multiview_unavailable_hint
        failure == Wire.ErrorCode.CAPACITY_EXHAUSTED -> Res.string.multiview_busy to Res.string.multiview_busy_hint
        else -> Res.string.multiview_failed to Res.string.multiview_failed_hint
    }
    val pointer = LocalLastInput.current.kind == InputKind.POINTER
    val lines = when {
        text != null -> listOfNotNull(stringResource(text.first), text.second?.let { stringResource(it) })
        hint -> listOf(stringResource(if (pointer) Res.string.multiview_touch_hint else Res.string.multiview_keys_hint))
        else -> return
    }
    Column(
        modifier.screenPadding().background(SofaOverlayScrim, MaterialTheme.shapes.small).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        lines.forEachIndexed { index, line ->
            Text(
                line,
                style = if (index == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** The server's answer when it does not draw multiview. */
internal const val MULTIVIEW_UNAVAILABLE = "MULTIVIEW_UNAVAILABLE"

private val ARROWS = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)
private val SELECT = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
