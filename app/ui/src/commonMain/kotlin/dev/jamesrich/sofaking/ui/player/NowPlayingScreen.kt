package dev.jamesrich.sofaking.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.data.Lyrics
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MusicQueue
import dev.jamesrich.sofaking.data.Repeat
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.music_favorite
import dev.jamesrich.sofaking.resources.music_lyrics
import dev.jamesrich.sofaking.resources.music_next
import dev.jamesrich.sofaking.resources.music_next_look
import dev.jamesrich.sofaking.resources.music_now_playing
import dev.jamesrich.sofaking.resources.music_off
import dev.jamesrich.sofaking.resources.music_on
import dev.jamesrich.sofaking.resources.music_previous
import dev.jamesrich.sofaking.resources.music_repeat
import dev.jamesrich.sofaking.resources.music_repeat_all
import dev.jamesrich.sofaking.resources.music_repeat_one
import dev.jamesrich.sofaking.resources.music_shuffle
import dev.jamesrich.sofaking.resources.music_visualizer
import dev.jamesrich.sofaking.ui.Artwork
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.formatPlayTime
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.OverVideo
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.TvFocusControl
import dev.jamesrich.sofaking.ui.visualizer.MusicVisualizer
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.random.Random

/**
 * The song [music] plays: its cover, its title and artist, the seek bar, and previous, play or pause, next, shuffle
 * and repeat, and Lyrics when [loadLyrics] finds the song's, which then take the cover's place. Beside the cover on a
 * wide window, under it on a tall one. It closes ([onClose]) once the music ends. On a TV it rests after a while
 * without a key, so a still cover does not mark the screen.
 */
@Composable
internal fun NowPlayingScreen(
    music: MusicPlayback,
    onClose: () -> Unit,
    loadLyrics: suspend (String) -> Lyrics? = { null },
) {
    val song = music.song
    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(song == null) { if (song == null) currentOnClose() }
    if (song == null) return
    val currentLoadLyrics by rememberUpdatedState(loadLyrics)
    val lyrics by produceState<Lyrics?>(null, song.id) { value = currentLoadLyrics(song.id) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val choice = lyrics?.let { LyricsChoice(it, showLyrics) { showLyrics = !showLyrics } }
    var showVisuals by rememberSaveable { mutableStateOf(false) }
    val tap = music.audioTap
    var skips by rememberSaveable { mutableIntStateOf(0) }
    val visuals = tap?.let { VisualsChoice(showVisuals, { showVisuals = !showVisuals }, { skips++ }) }
    // Over the visualizer's dark scrim the controls draw in the dark scheme, whatever the app's.
    OverVideo(dark = tap != null && showVisuals) {
        Box(Modifier.fillMaxSize()) {
            if (tap != null && showVisuals) {
                MusicVisualizer(tap, song.id, skips, Modifier.fillMaxSize())
                // The controls stay legible over a bright picture.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = VISUALS_SCRIM)))
            }
            RestWhenIdle(LocalLayoutProfile.current.tv) { rest ->
                NowPlayingLayout(music, song, choice, visuals, rest)
            }
            if (LocalLastInput.current.kind == InputKind.POINTER) {
                Box(Modifier.align(Alignment.TopStart).screenPadding()) { PlayerBackControl(onClose) }
            }
        }
    }
}

/**
 * Whether the visualizer shows behind the page, the button that [toggle]s it and the one that asks for the [next] look;
 * where the engine can be tapped.
 */
private class VisualsChoice(val shown: Boolean, val toggle: () -> Unit, val next: () -> Unit)

/** A song's [lyrics], [shown] in the cover's place or not, and the button that [toggle]s them. */
private class LyricsChoice(val lyrics: Lyrics, val shown: Boolean, val toggle: () -> Unit)

/** The cover, or the lyrics, beside the song's controls on a wide window, above them on a tall one. */
@Composable
private fun NowPlayingLayout(
    music: MusicPlayback,
    song: MediaItem,
    lyrics: LyricsChoice?,
    visuals: VisualsChoice?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().screenPadding()) {
        val wide = maxWidth > maxHeight
        val side = if (wide) min(maxHeight * COVER_SHARE, maxWidth * 0.45f) else min(maxWidth, maxHeight * 0.45f)
        if (wide) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverOrLyrics(music, song, lyrics, side)
                SongControls(music, song, lyrics, visuals, Modifier.widthIn(max = CONTROLS_WIDTH))
            }
        } else {
            // Room under the pointer Back button, and a scroll for a window too short for the cover and the buttons.
            val backRoom = if (LocalLastInput.current.kind == InputKind.POINTER) CONTROL_SIZE else 0.dp
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = backRoom)
                    .heightIn(min = maxHeight - backRoom),
                verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CoverOrLyrics(music, song, lyrics, side)
                SongControls(music, song, lyrics, visuals, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CoverOrLyrics(music: MusicPlayback, song: MediaItem, lyrics: LyricsChoice?, side: Dp) {
    Box(Modifier.size(side).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (lyrics?.shown == true) {
            LyricsPanel(music, lyrics.lyrics)
        } else {
            Artwork(song.poster, label = null)
        }
    }
}

/**
 * The song's lyrics, the line being sung brighter than the rest and kept near the top as the song goes on; lyrics
 * that are not synced are shown as written.
 */
@Composable
private fun LyricsPanel(music: MusicPlayback, lyrics: Lyrics) {
    val position = if (lyrics.synced) rememberMusicPosition(music) else 0L
    val current = lyrics.lineAt(position)
    val list = rememberLazyListState()
    LaunchedEffect(current) { if (current >= 0) list.animateScrollToItem((current - LINES_ABOVE).coerceAtLeast(0)) }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            val sung = i == current
            Text(
                line.text,
                style = if (lyrics.synced) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                fontWeight = if (sung) FontWeight.Bold else null,
                color = if (sung || !lyrics.synced) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** The song's names, the seek bar with its times, and the buttons. */
@Composable
private fun SongControls(
    music: MusicPlayback,
    song: MediaItem,
    lyrics: LyricsChoice?,
    visuals: VisualsChoice?,
    modifier: Modifier = Modifier,
) {
    val position = rememberMusicPosition(music)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(Res.string.music_now_playing),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            song.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        songLine(song)?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        music.failure?.let {
            Text(stringResource(failureReasonRes(it, live = false)), color = MaterialTheme.colorScheme.error)
        }
        val length = music.durationMs
        if (length != null && length > 0) {
            VodSeekSlider({ position }, length, emptyList(), onScrub = {}, onSeek = music::seek)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatPlayTime(position), style = MaterialTheme.typography.labelLarge)
                Text(formatPlayTime(length), style = MaterialTheme.typography.labelLarge)
            }
        }
        MusicButtons(music, lyrics, visuals)
    }
}

/**
 * Gives [content] a modifier that, once [enabled] and [REST_AFTER_MS] have passed without a key, dims the page and
 * moves it a little every [DRIFT_MS], as a screen saver does, so nothing stays lit in one place. The next key wakes it
 * and does nothing else.
 */
@Composable
private fun RestWhenIdle(enabled: Boolean, content: @Composable (rest: Modifier) -> Unit) {
    var keys by remember { mutableIntStateOf(0) }
    var resting by remember { mutableStateOf(false) }
    var drift by remember { mutableStateOf(DpOffset.Zero) }
    LaunchedEffect(enabled, keys) {
        resting = false
        drift = DpOffset.Zero
        if (!enabled) return@LaunchedEffect
        delay(REST_AFTER_MS)
        resting = true
        while (true) {
            drift = DpOffset(DRIFT.times(Random.nextFloat() * 2 - 1), DRIFT.times(Random.nextFloat() * 2 - 1))
            delay(DRIFT_MS)
        }
    }
    val alpha by animateFloatAsState(if (resting) REST_ALPHA else 1f, tween(FADE_MS))
    val x by animateDpAsState(drift.x, tween(FADE_MS))
    val y by animateDpAsState(drift.y, tween(FADE_MS))
    val rest = Modifier
        .onPreviewKeyEvent {
            val woke = resting
            if (it.type == KeyEventType.KeyDown) keys++
            woke
        }
        .offset(x, y)
        .graphicsLayer { this.alpha = alpha }
    content(rest)
}

/** Favorite, which likes the song or unlikes it, then Lyrics and the Visualizer where the song has them. */
@Composable
private fun MoreToggles(music: MusicPlayback, lyrics: LyricsChoice?, visuals: VisualsChoice?) {
    music.song?.let { song ->
        val liked = music.likes.isFavorite(song)
        Toggle(
            if (liked) SofaIcons.Favorite else SofaIcons.FavoriteBorder,
            stringResource(Res.string.music_favorite),
            stringResource(if (liked) Res.string.music_on else Res.string.music_off),
            liked,
            { music.likes.toggle(song) },
        )
    }
    lyrics?.let {
        Toggle(
            SofaIcons.Subtitles,
            stringResource(Res.string.music_lyrics),
            stringResource(if (it.shown) Res.string.music_on else Res.string.music_off),
            it.shown,
            it.toggle,
        )
    }
    visuals?.let {
        Toggle(
            SofaIcons.GraphicEq,
            stringResource(Res.string.music_visualizer),
            stringResource(if (it.shown) Res.string.music_on else Res.string.music_off),
            it.shown,
            it.toggle,
        )
        // Next look keeps its slot while the visualizer is off, so the row does not re-space when it appears.
        if (it.shown) {
            ControlIcon(SofaIcons.AutoAwesome, stringResource(Res.string.music_next_look), it.next)
        } else {
            Spacer(Modifier.size(CONTROL_SIZE))
        }
    }
}

/**
 * Shuffle, previous, play or pause, next, repeat and Favorite, in one row, then Lyrics and the Visualizer where there
 * are some.
 */
@Composable
private fun MusicButtons(music: MusicPlayback, lyrics: LyricsChoice?, visuals: VisualsChoice?) {
    val queue = music.queue ?: return
    val status by music.status.collectAsStateWithLifecycle()
    // Focus starts on Play or Pause, for a remote.
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { requestFocusWhenAttached(focus) }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Toggle(
            SofaIcons.Shuffle,
            stringResource(Res.string.music_shuffle),
            stringResource(if (queue.shuffled) Res.string.music_on else Res.string.music_off),
            queue.shuffled,
            { music.reorder { it.shuffle(!it.shuffled) } },
        )
        ControlIcon(SofaIcons.SkipPrevious, stringResource(Res.string.music_previous), music::previous)
        PlayPauseControl(
            paused = !status.playWhenReady || music.held,
            failed = music.failure != null,
            onPlayPause = music::togglePause,
            onRetry = music::togglePause,
            primary = true,
            modifier = Modifier.focusRequester(focus),
        )
        ControlIcon(SofaIcons.SkipNext, stringResource(Res.string.music_next), music::next)
        Toggle(
            if (queue.repeat == Repeat.ONE) SofaIcons.RepeatOne else SofaIcons.Repeat,
            stringResource(Res.string.music_repeat),
            stringResource(
                when (queue.repeat) {
                    Repeat.OFF -> Res.string.music_off
                    Repeat.ALL -> Res.string.music_repeat_all
                    Repeat.ONE -> Res.string.music_repeat_one
                },
            ),
            queue.repeat != Repeat.OFF,
            { music.reorder(MusicQueue::nextRepeat) },
        )
        MoreToggles(music, lyrics, visuals)
    }
}

/** A button that is on or off, tinted while on; [state] says which to a screen reader. */
@Composable
private fun Toggle(icon: ImageVector, label: String, state: String, on: Boolean, onClick: () -> Unit) {
    TvFocusControl(IconButtonDefaults.standardShape) { ringed ->
        IconToggleButton(
            checked = on,
            onCheckedChange = { onClick() },
            modifier = ringed.semantics { stateDescription = state },
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}

/** A song's artist and album, as one line. */
internal fun songLine(song: MediaItem): String? =
    listOfNotNull(song.trackArtist ?: song.artist, song.album).joinToString(" · ").ifEmpty { null }

/** Where [music]'s song is, read a few times a second while shown. */
@Composable
internal fun rememberMusicPosition(music: MusicPlayback): Long {
    var position by remember(music) { mutableLongStateOf(music.positionMs) }
    LaunchedEffect(music) {
        while (true) {
            position = music.positionMs
            delay(POSITION_READ_MS)
        }
    }
    return position
}

private const val POSITION_READ_MS = 250L

/** How long Now playing on a TV waits for a key before it rests, how often it moves then, and how far. */
private const val REST_AFTER_MS = 30_000L
private const val DRIFT_MS = 60_000L
private val DRIFT = 32.dp
private const val REST_ALPHA = 0.35f
private const val FADE_MS = 2_000

/** The cover's side as a share of a wide window's height. */
private const val COVER_SHARE = 0.7f

private val CONTROLS_WIDTH = 480.dp

/** How dark the scrim is over the visualizer. */
private const val VISUALS_SCRIM = 0.35f

/** How many sung lines stay above the one being sung. */
private const val LINES_ABOVE = 2
