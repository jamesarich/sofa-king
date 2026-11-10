package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackPreferences
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.multiview_keys_hint
import dev.jamesrich.sofaking.resources.multiview_sound
import dev.jamesrich.sofaking.resources.multiview_touch_hint
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.KeepScreenOn
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.formatTemplate
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource

/** The multiview screen's test tag, as the player's is. */
const val MULTIVIEW_TEST_TAG = "multiview"

/** The shape the panes are laid out in. */
private const val PICTURE_ASPECT = 16f / 9f

/** The players a multiview plays on beside the app's own: the app's [player] and session, and a session per pane. */
class MultiviewPlayers(
    val player: LivePlayer,
    val sessions: PlaybackSessions,
    val paneSessions: List<PlaybackSessions>,
)

/**
 * Multiview: two to four channels from [lineup] side by side, each a pane with a player and a session of its own
 * ([players]), starting with [initial]. A list beside the panes picks them, and each plays as soon as it is ticked.
 * With the list away, the arrows or a tap move the sound between the panes, which changes nothing but their loudness;
 * OK or a double tap watches the one with the sound full screen ([onWatch]), and Back returns to the list, whose Back
 * leaves ([onExit]). The app's own player is stopped and its session released for as long as multiview is up. The
 * list groups the channels multiview opened on, the starred ones in [favoritesOrder], the [recent] ones and the rest.
 */
@Composable
fun MultiviewScreen(
    lineup: Lineup,
    initial: List<String>,
    players: MultiviewPlayers,
    onWatch: (Channel) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    favoritesOrder: List<String> = emptyList(),
    recent: List<String> = emptyList(),
    captions: PlayerCaptions = PlayerCaptions(),
    breaks: PlayerBreaks = PlayerBreaks(),
    playback: PlaybackPreferences = PlaybackPreferences(),
) {
    val state = remember { MultiviewState(initial.take(Wire.MULTIVIEW_MAX_TILES)) }
    val query = remember { TextFieldState() }
    val now by rememberMinuteNow()
    val airing = { channel: Channel -> lineup.nowAndNext(channel, now).first?.title }
    LaunchedEffect(players) {
        players.player.stop()
        // A channel paused when multiview opened plays again when the live player takes over.
        players.player.playWhenReady = true
        players.sessions.release(forget = true)
    }
    KeepScreenOn(state.picked.isNotEmpty())
    val leave = rememberLeave(state, players.paneSessions)
    BackHandler {
        if (!state.back()) leave(onExit)
    }
    LaunchedEffect(state.searching) { if (!state.searching) query.clearText() }
    val channels = state.picked.mapNotNull { id -> lineup.channels.firstOrNull { it.guideId == id } }
    val names by produceLabels(state.touched)
    BoxWithConstraints(modifier.fillMaxSize().background(SofaScrim).testTag(MULTIVIEW_TEST_TAG)) {
        val beside = if (state.picking && maxWidth >= PICKER_WIDTH * 2) PICKER_WIDTH else 0.dp
        val width = min(maxWidth - beside, maxHeight * PICTURE_ASPECT)
        val height = width / PICTURE_ASPECT
        val left = beside + (maxWidth - beside - width) / 2
        val picture = Modifier.offset(left, (maxHeight - height) / 2).size(width, height)
        Panes(
            channels,
            state,
            players.paneSessions,
            airing,
            names = names || state.picking,
            look = PaneLook(captions.enabled, captions.style, breaks.mode, playback),
            modifier = picture,
        )
        if (state.picking) {
            MultiviewPicker(
                groups = remember(lineup, favoritesOrder, recent, query.text, now) {
                    val typed = query.text.toString()
                    multiviewGroups(lineup.channels, state.opened, favoritesOrder, recent, typed, airing)
                },
                picked = state.picked,
                query = query,
                searching = state.searching,
                airing = airing,
                onToggle = { state.toggle(it.guideId) },
                onSearch = { state.searching = true },
                onWatch = state::watch,
            )
        } else {
            PaneControls(
                channels,
                sound = channels.indexOfFirst { it.guideId == state.sound }.coerceAtLeast(0),
                onTouch = { state.touched++ },
                onSound = { index -> channels.getOrNull(index)?.let { state.sound = it.guideId } },
                onWatch = { channel -> leave { onWatch(channel) } },
                modifier = picture,
            )
            if (names) PaneHint(Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * Leaving multiview: the panes close and their sessions are released before the action given runs, so the live player
 * that takes over finds its session slot free on a server whose every slot the panes held. A server that does not
 * answer holds the leave up for no longer than [LEAVE_WAIT_MS].
 */
@Composable
private fun rememberLeave(state: MultiviewState, sessions: List<PlaybackSessions>): (() -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(state, sessions, scope) {
        { then ->
            if (state.close()) {
                // A release finishes on its own even when the wait for it is given up.
                val releases = sessions.map { scope.async { it.release(forget = true) } }
                scope.launch {
                    withTimeoutOrNull(LEAVE_WAIT_MS) { releases.awaitAll() }
                    then()
                }
            }
        }
    }
}

/**
 * What a multiview shows: the channels [picked], in the order they were ticked and starting with those it [opened]
 * on, the player each plays on ([slots]), the one whose [sound] plays, whether the list is up ([picking])
 * and its search box open ([searching]), and a count of the presses and taps that show the names ([touched]).
 */
@Stable
internal class MultiviewState(val opened: List<String>) {
    var picked by mutableStateOf(opened)
        private set
    var slots by mutableStateOf(paneSlots(emptyMap(), opened))
        private set
    var sound by mutableStateOf(opened.firstOrNull())
    var picking by mutableStateOf(true)
    var searching by mutableStateOf(false)
    var touched by mutableIntStateOf(0)

    /** Ticks or unticks [guideId]: its pane opens or closes, and the sound stays where it was while that one shows. */
    fun toggle(guideId: String) {
        picked = togglePick(picked, guideId)
        slots = paneSlots(slots, picked)
        sound = soundAfter(sound, picked)
    }

    private var closed = false

    /** Back: shuts the search box, or brings the list back over the panes; false when it leaves multiview. */
    fun back(): Boolean = when {
        searching -> {
            searching = false
            true
        }

        picking -> false

        else -> {
            picking = true
            true
        }
    }

    /** Closes every pane, once: false when they were closed already. */
    fun close(): Boolean {
        if (closed) return false
        closed = true
        picked = emptyList()
        slots = emptyMap()
        return true
    }

    /** Puts the list away, with the names up. */
    fun watch() {
        touched++
        picking = false
    }
}

/** The panes of [channels] in [modifier]'s bounds, each on its player's session of [sessions]. */
@Composable
private fun Panes(
    channels: List<Channel>,
    state: MultiviewState,
    sessions: List<PlaybackSessions>,
    airing: (Channel) -> String?,
    names: Boolean,
    look: PaneLook,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val width = maxWidth
        val height = maxHeight
        channels.forEachIndexed { index, channel ->
            val slot = state.slots[channel.guideId] ?: return@forEachIndexed
            val tile = multiviewTile(channels.size, index)
            val heard = channel.guideId == state.sound
            key(slot) {
                MultiviewPane(
                    channel,
                    program = airing(channel),
                    sessions = sessions[slot],
                    sound = heard,
                    names = names,
                    framed = heard && channels.size >= MULTIVIEW_MIN_TILES,
                    look = look,
                    modifier = Modifier
                        .offset(width * tile.left, height * tile.top)
                        .size(width * tile.width, height * tile.height),
                )
            }
        }
    }
}

/**
 * The keys and taps over the panes, laid over them in [modifier]'s bounds: an arrow or a tap moves the [sound] to
 * another of [channels] by [onSound], after [onTouch]; OK, on its key-up, or a double tap watches one full screen.
 */
@Composable
private fun PaneControls(
    channels: List<Channel>,
    sound: Int,
    onTouch: () -> Unit,
    onSound: (Int) -> Unit,
    onWatch: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus) }
    val count = channels.size
    val heard = channels.getOrNull(sound)
    val soundTemplate = stringResource(Res.string.multiview_sound)
    Box(
        modifier
            .focusRequester(focus)
            .focusable()
            .semantics { heard?.let { contentDescription = formatTemplate(soundTemplate, it.label()) } }
            .onPreviewKeyEvent { event ->
                val key = event.key
                when {
                    key in ARROWS && event.type == KeyEventType.KeyDown -> {
                        onTouch()
                        onSound(multiviewMove(count, sound, key))
                        true
                    }

                    key in SELECT && event.type == KeyEventType.KeyUp -> {
                        heard?.let(onWatch)
                        true
                    }

                    else -> false
                }
            }
            .pointerInput(count) {
                detectTapGestures(
                    onTap = { at ->
                        onTouch()
                        multiviewTileAt(count, at.x / size.width, at.y / size.height)?.let(onSound)
                    },
                    onDoubleTap = { at ->
                        multiviewTileAt(count, at.x / size.width, at.y / size.height)
                            ?.let { channels.getOrNull(it) }
                            ?.let(onWatch)
                    },
                )
            },
    )
}

/** True for [BAR_IDLE_MS] after each press or tap, counted by [touched], and at first. */
@Composable
private fun produceLabels(touched: Int): State<Boolean> {
    val shown = remember { mutableStateOf(true) }
    LaunchedEffect(touched) {
        shown.value = true
        delay(BAR_IDLE_MS)
        shown.value = false
    }
    return shown
}

/** What the keys or a finger do, under the panes while their names show. */
@Composable
private fun PaneHint(modifier: Modifier = Modifier) {
    val pointer = LocalLastInput.current.kind == InputKind.POINTER
    Text(
        stringResource(if (pointer) Res.string.multiview_touch_hint else Res.string.multiview_keys_hint),
        modifier.screenPadding().background(SofaOverlayScrim, MaterialTheme.shapes.small).padding(HINT_PADDING),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private const val LEAVE_WAIT_MS = 2_000L
private val HINT_PADDING: Dp = 12.dp
private val ARROWS = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)
private val SELECT = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
