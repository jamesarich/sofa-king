package dev.jamesrich.sofaking.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.resources.*
import dev.jamesrich.sofaking.ui.formatPlayTime
import dev.jamesrich.sofaking.ui.hoursAndMinutes
import dev.jamesrich.sofaking.ui.theme.SofaMauve
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Where the break screen's logo is, which way it is heading on each axis, and which of [BOUNCE_HUES] it wears. */
internal data class Bounce(val x: Float, val y: Float, val right: Boolean, val down: Boolean, val tint: Int = 0)

/** What one [Bounce.step] ran into. */
internal enum class BounceHit { NONE, EDGE, CORNER }

/**
 * The hue turns, in degrees, the logo cycles through on each edge it hits. A corner lands on the first, the brand's
 * own amber crown on a lavender sofa, so the rare corner is the one moment it looks like itself.
 */
internal val BOUNCE_HUES = listOf(0f, 60f, 120f, 180f, 240f, 300f)

/**
 * Moves the logo [distance] pixels along both axes inside a [roomX] by [roomY] area (the screen less the logo),
 * reflecting off each edge it reaches. An edge hit moves the tint on; hitting both edges in one step, or one edge
 * within [cornerSlop] of the other, is a corner and puts the brand colors back.
 */
internal fun Bounce.step(distance: Float, roomX: Float, roomY: Float, cornerSlop: Float): Pair<Bounce, BounceHit> {
    val (nextX, right, hitX) = reflect(x, right, distance, roomX)
    val (nextY, down, hitY) = reflect(y, down, distance, roomY)
    fun nearEdge(position: Float, room: Float) = position <= cornerSlop || position >= room - cornerSlop
    val hit = when {
        hitX && (hitY || nearEdge(nextY, roomY)) || hitY && nearEdge(nextX, roomX) -> BounceHit.CORNER
        hitX || hitY -> BounceHit.EDGE
        else -> BounceHit.NONE
    }
    val nextTint = when (hit) {
        BounceHit.CORNER -> 0
        BounceHit.EDGE -> tint % (BOUNCE_HUES.size - 1) + 1
        BounceHit.NONE -> tint
    }
    return Bounce(nextX, nextY, right, down, nextTint) to hit
}

/** One axis of [step]: the new position, heading and whether it hit an edge. A room of zero or less pins it at 0. */
private fun reflect(position: Float, forward: Boolean, distance: Float, room: Float): Triple<Float, Boolean, Boolean> {
    if (room <= 0f) return Triple(0f, forward, false)
    val next = if (forward) position + distance else position - distance
    return when {
        next >= room -> Triple((2 * room - next).coerceIn(0f, room), false, true)
        next <= 0f -> Triple((-next).coerceIn(0f, room), true, true)
        else -> Triple(next, forward, false)
    }
}

/** A hue rotation that keeps lightness, the matrix CSS `hue-rotate()` uses. */
internal fun hueRotation(degrees: Float): ColorMatrix {
    val radians = degrees * PI.toFloat() / 180f
    val c = cos(radians)
    val s = sin(radians)
    return ColorMatrix(
        floatArrayOf(
            0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
            0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
            0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/**
 * Deals the numbers 0 until [size] in a shuffled order, and shuffles again once all are dealt, so a break screen line
 * comes back only after the rest have had a turn, and never twice running.
 */
internal class Deck(private val size: Int, private val random: Random = Random) {
    private val cards = ArrayDeque<Int>()
    private var last = -1

    fun next(): Int {
        if (cards.isEmpty()) {
            cards += (0 until size).shuffled(random)
            if (cards.size > 1 && cards.first() == last) cards.addLast(cards.removeFirst())
        }
        return cards.removeFirst().also { last = it }
    }
}

/** The lines a break picks from, one per break, under the countdown. */
private val BREAK_QUIPS = listOf(
    Res.string.break_quip_throne,
    Res.string.break_quip_realm,
    Res.string.break_quip_adjust,
    Res.string.break_quip_snacks,
    Res.string.break_quip_castle,
    Res.string.break_quip_cushion,
    Res.string.break_quip_stretch,
    Res.string.break_quip_patient,
    Res.string.break_quip_pillows,
    Res.string.break_quip_recline,
)

/** What the break screen remembers from one break to the next while the app runs: the next line, and the corners seen. */
private object BreakTally {
    val quips = Deck(BREAK_QUIPS.size)
    var corners = 0
}

/**
 * The break screen: black, with the crown-on-sofa mark drifting around and bouncing off the edges the way an old DVD
 * player's screensaver did, a new color on every edge and the brand colors back on a corner, with a little pop and a
 * line to mark the occasion. [remainingMs], when known, counts down under the mark, over a line picked for the break,
 * [upNext] (the channel's next program) and the ads dodged today, [dodgedBeforeMs] before this break plus the time
 * it has been up. Everything moves with the mark, so nothing sits still long enough to burn in. When it goes,
 * [onCovered] gets the milliseconds it was on screen, counted in drawn frames, so time in the background is left out.
 * It draws only; the player decides whether the sound plays under it.
 */
@Composable
internal fun BreakScreen(
    modifier: Modifier = Modifier,
    remainingMs: (() -> Long?)? = null,
    upNext: String? = null,
    dodgedBeforeMs: Long = 0,
    onCovered: (Long) -> Unit = {},
) {
    val label = stringResource(Res.string.break_screen_title)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(SofaScrim)
            .semantics { contentDescription = label; liveRegion = LiveRegionMode.Polite },
    ) {
        val density = LocalDensity.current
        val logoSize = (minOf(maxWidth, maxHeight) * 0.22f).coerceIn(96.dp, 240.dp)
        val speedPxPerSecond = with(density) { BOUNCE_SPEED.toPx() }
        val cornerSlop = with(density) { CORNER_SLOP.toPx() }
        val area = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        var unit by remember { mutableStateOf(IntSize.Zero) }
        var bounce by remember { mutableStateOf(Bounce(area.width * 0.3f, area.height * 0.2f, right = true, down = true)) }
        var tint by remember { mutableIntStateOf(0) }
        val pop = remember { Animatable(1f) }
        val quip = remember { BreakTally.quips.next() }
        // The corner just hit, counted from the app's start, while its line shows.
        var corner by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(corner) {
            if (corner == null) return@LaunchedEffect
            delay(CORNER_LINE_MS)
            corner = null
        }
        var coveredNanos by remember { mutableLongStateOf(0L) }
        val dodgedBefore = remember { dodgedBeforeMs }
        val dodgedMinutes by remember { derivedStateOf { (dodgedBefore + coveredNanos / 1_000_000) / 60_000 } }
        val currentOnCovered by rememberUpdatedState(onCovered)
        DisposableEffect(Unit) { onDispose { currentOnCovered(coveredNanos / 1_000_000) } }
        LaunchedEffect(area, unit) {
            if (unit == IntSize.Zero) return@LaunchedEffect
            val roomX = (area.width - unit.width).toFloat()
            val roomY = (area.height - unit.height).toFloat()
            bounce = bounce.copy(x = bounce.x.coerceIn(0f, roomX.coerceAtLeast(0f)), y = bounce.y.coerceIn(0f, roomY.coerceAtLeast(0f)))
            var last = 0L
            while (true) {
                val hit = withFrameNanos { now ->
                    val gap = if (last == 0L) 0L else now - last
                    last = now
                    // A long gap (the app in the background) moves the mark no more than one frame's worth, and does
                    // not count as covered.
                    if (gap < MAX_COVERED_GAP_NANOS) coveredNanos += gap
                    val seconds = (gap / 1e9f).coerceAtMost(MAX_FRAME_SECONDS)
                    val (next, hit) = bounce.step(speedPxPerSecond * seconds, roomX, roomY, cornerSlop)
                    bounce = next
                    hit
                }
                if (hit != BounceHit.NONE) tint = bounce.tint
                if (hit == BounceHit.CORNER) {
                    launch {
                        pop.snapTo(CORNER_POP)
                        pop.animateTo(1f, tween(CORNER_POP_MS))
                    }
                    corner = ++BreakTally.corners
                }
            }
        }
        val filter = remember(tint) { if (tint == 0) null else ColorFilter.colorMatrix(hueRotation(BOUNCE_HUES[tint])) }
        val lineWidth = Modifier.widthIn(max = logoSize * 2.5f)
        Column(
            modifier = Modifier
                .offset { IntOffset(bounce.x.roundToInt(), bounce.y.roundToInt()) }
                .onSizeChanged { unit = it }
                .graphicsLayer {
                    // The pop grows out of the corner just hit, which the mark is now heading away from, so it stays on screen.
                    transformOrigin = TransformOrigin(if (bounce.right) 0f else 1f, if (bounce.down) 0f else 1f)
                    scaleX = pop.value
                    scaleY = pop.value
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Image(painterResource(Res.drawable.banner_logo), contentDescription = null, colorFilter = filter, modifier = Modifier.size(logoSize))
            val left = remainingMs?.invoke()
            Text(
                if (left != null) stringResource(Res.string.break_screen_back_in, formatPlayTime(left)) else label,
                style = MaterialTheme.typography.titleMedium,
                color = SofaMauve,
                modifier = Modifier.padding(top = 4.dp),
            )
            val cornerNumber = corner
            Text(
                when {
                    cornerNumber == 1 -> stringResource(Res.string.break_corner_first)
                    cornerNumber != null -> stringResource(Res.string.break_corner_again, cornerNumber)
                    left == null -> stringResource(Res.string.break_quip_unknown)
                    left <= ALMOST_BACK_MS -> stringResource(Res.string.break_quip_almost)
                    else -> stringResource(BREAK_QUIPS[quip])
                },
                style = MaterialTheme.typography.bodyLarge,
                color = SofaMauve,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = lineWidth,
            )
            val faint = SofaMauve.copy(alpha = 0.7f)
            if (upNext != null) {
                Text(upNext, style = MaterialTheme.typography.bodySmall, color = faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = lineWidth)
            }
            if (dodgedMinutes > 0) {
                Text(
                    stringResource(Res.string.break_ads_dodged, dodgedMinutes.minutes.hoursAndMinutes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = faint,
                    maxLines = 1,
                    modifier = lineWidth,
                )
            }
        }
    }
}

/** How fast the mark drifts, on each axis. */
private val BOUNCE_SPEED = 72.dp
/** How close to the other edge an edge hit may land and still count as a corner. */
private val CORNER_SLOP = 6.dp
private const val MAX_FRAME_SECONDS = 0.05f
private const val CORNER_POP = 1.25f
private const val CORNER_POP_MS = 600
/** How long a corner's line shows. */
private const val CORNER_LINE_MS = 4_000L
/** A frame gap longer than this was the app away, not the screen up. */
private const val MAX_COVERED_GAP_NANOS = 1_000_000_000L
/** The last stretch of a break, when the line tells everyone to get back on the sofa. */
private const val ALMOST_BACK_MS = 10_000L
