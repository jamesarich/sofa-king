package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.TrailerDwell
import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.data.trailerOf
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalUptimeMs
import dev.jamesrich.sofaking.ui.appLifecycleOwner
import dev.jamesrich.sofaking.ui.player.LivePlayer
import dev.jamesrich.sofaking.ui.player.PlaybackState
import dev.jamesrich.sofaking.ui.player.VideoZoom
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The server's side of a trailer: the one session it may open, and the session the app holds now. */
internal interface TrailerSessions {
    /** The stream the app holds now, the trailer's or anything else's. */
    val current: StateFlow<PlaybackStream?>

    /** Opens [itemId] as a preview if the app holds nothing; null when it does not play. */
    suspend fun preview(itemId: String, profile: PlaybackProfile): PlaybackStream?

    /** Lets [itemId]'s session go, if it is still the one held. */
    fun release(itemId: String)
}

/** The library sessions, as a trailer uses them. */
internal fun PlaybackSessions.trailers(): TrailerSessions = object : TrailerSessions {
    override val current = this@trailers.current

    override suspend fun preview(itemId: String, profile: PlaybackProfile) = this@trailers.preview(itemId, profile)

    override fun release(itemId: String) = releaseAsync(forget = true, itemId = itemId)
}

/** What a movie's or show's page needs to play its trailer: the shared [player], the [sessions], and who else plays. */
@Stable
internal class TrailerSource(
    val player: LivePlayer,
    val sessions: TrailerSessions,
    /** True while a song is queued or held, which a trailer never interrupts. */
    val musicActive: Boolean,
    val kids: Boolean,
)

/**
 * One page's trailer on the app's shared [player], muted behind the title. It borrows the player and the one session
 * and ends as soon as either is taken: [open] only starts it while nothing is held, a real play or a channel replaces
 * the session, and everything that ends it ([ended], [stopForBackground], [leave]) stops the player only while it
 * still holds the trailer's own stream and releases only the trailer's own session.
 */
@Stable
internal class TrailerPlayback(
    private val player: LivePlayer,
    private val sessions: TrailerSessions,
    val dwell: TrailerDwell,
) {
    /** The trailer's stream while its session is held and its picture is the player's; null otherwise. */
    var stream by mutableStateOf<PlaybackStream?>(null)
        private set

    /**
     * Opens [itemId] muted on the player. False when it did not start: nothing came of the open (a server that
     * plays no previews, a full server, something else held) and the page does not try again, or [wanted] said no by
     * the time the session was open, in which case it is released and the page may try again.
     */
    suspend fun open(itemId: String, wanted: () -> Boolean): Boolean {
        val opened = runCatchingCancellable { sessions.preview(itemId, player.playbackProfile) }.getOrNull()
        if (opened == null) {
            dwell.finished()
            return false
        }
        if (!wanted() || sessions.current.value?.sessionId != opened.sessionId) {
            sessions.release(itemId)
            return false
        }
        player.stop()
        player.volume = 0f
        player.setStream(opened)
        player.playWhenReady = true
        player.prepare()
        stream = opened
        dwell.started()
        return true
    }

    /** Follows the player and the sessions while the trailer plays; the page runs it for as long as it is composed. */
    suspend fun watch(): Unit = coroutineScope {
        launch {
            sessions.current.collect { held ->
                val ours = stream ?: return@collect
                if (held?.sessionId != ours.sessionId) ended(release = false)
            }
        }
        launch { player.status.collect { if (it.state == PlaybackState.ENDED && stream != null) ended(true) } }
        launch { player.errors.collect { if (stream != null) ended(true) } }
    }

    /** The trailer is over, or the player was taken: it stops and does not start again for this page. */
    private fun ended(release: Boolean) {
        stop(release)
        dwell.finished()
    }

    /** The app or the page left the front: the session goes free, and the trailer rests again from [nowMs]. */
    fun stopForBackground(nowMs: Long) {
        stop(release = true)
        dwell.stopped(nowMs)
    }

    /** The page is gone. */
    fun leave() {
        stop(release = true)
    }

    private fun stop(release: Boolean) {
        val ours = stream ?: return
        stream = null
        if (player.currentStreamUrl == ours.url) player.stop()
        if (release) ours.itemId?.let(sessions::release)
    }
}

/** The backdrop, with the item's [trailer] playing over it when the page is given one to play. */
@Composable
internal fun DetailsHero(detail: MediaItemDetail, trailer: TrailerSource?, modifier: Modifier = Modifier) {
    val item = detail.item
    Hero(item.backdrop ?: item.poster, modifier) {
        if (trailer != null) TrailerVideo(trailer, detail)
    }
}

/**
 * [detail]'s trailer, drawn over the backdrop it sits in once the page has rested: it opens when [TrailerDwell] says
 * so and the app, this page and the player are free, shows the picture without sound, and leaves with the page. A
 * page without a trailer, or one [source] does not offer, draws nothing.
 */
@Composable
internal fun TrailerVideo(source: TrailerSource, detail: MediaItemDetail) {
    val item = remember(detail, source.kids) { trailerOf(detail, source.kids) } ?: return
    val clock = LocalUptimeMs.current
    val lastInput = LocalLastInput.current
    var played by rememberSaveable(detail.item.id) { mutableStateOf(false) }
    val dwell = remember(detail.item.id) { TrailerDwell(done = played).also { it.restart(clock()) } }
    val trailer = remember(source.player, source.sessions, dwell) {
        TrailerPlayback(source.player, source.sessions, dwell)
    }
    var inFront by remember { mutableStateOf(false) }
    val currentInFront by rememberUpdatedState(inFront)
    InFrontEffect(
        onChange = { front ->
            inFront = front
            if (front) dwell.restart(clock()) else trailer.stopForBackground(clock())
        },
    )
    val held by source.sessions.current.collectAsStateWithLifecycle()
    val currentMusic by rememberUpdatedState(source.musicActive)
    LaunchedEffect(trailer) { trailer.watch() }
    DisposableEffect(trailer) { onDispose(trailer::leave) }
    LaunchedEffect(trailer, item.id, inFront, held != null, source.musicActive) {
        if (!inFront || held != null || source.musicActive) return@LaunchedEffect
        while (true) {
            dwell.onInput(lastInput.atMs)
            val wait = dwell.nextCheckMs(clock()) ?: return@LaunchedEffect
            if (wait <= 0) break
            delay(wait)
        }
        if (trailer.open(item.id) { currentInFront && !currentMusic }) played = true
    }
    if (trailer.stream != null) {
        Box(Modifier.fillMaxSize()) {
            source.player.Video(Modifier.fillMaxSize(), VideoZoom.FILL) {}
        }
    }
}

/**
 * Reports through [onChange] whether the app and this page are both started: once on arrival, then each time that
 * changes. The page's lifecycle stops when another page covers it, the app's when the app leaves the foreground.
 */
@Composable
private fun InFrontEffect(onChange: (Boolean) -> Unit) {
    val app = appLifecycleOwner.lifecycle
    val page = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(onChange)
    DisposableEffect(app, page) {
        var front: Boolean? = null
        val observer = LifecycleEventObserver { _, _ ->
            val now = app.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                page.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (now != front) {
                front = now
                current(now)
            }
        }
        app.addObserver(observer)
        page.addObserver(observer)
        onDispose {
            app.removeObserver(observer)
            page.removeObserver(observer)
        }
    }
}
