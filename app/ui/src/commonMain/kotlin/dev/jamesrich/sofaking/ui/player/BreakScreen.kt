package dev.jamesrich.sofaking.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.banner_logo
import dev.jamesrich.sofaking.resources.break_ads_dodged
import dev.jamesrich.sofaking.resources.break_corner_again
import dev.jamesrich.sofaking.resources.break_corner_first
import dev.jamesrich.sofaking.resources.break_quip_adjust
import dev.jamesrich.sofaking.resources.break_quip_almost
import dev.jamesrich.sofaking.resources.break_quip_castle
import dev.jamesrich.sofaking.resources.break_quip_cushion
import dev.jamesrich.sofaking.resources.break_quip_patient
import dev.jamesrich.sofaking.resources.break_quip_pillows
import dev.jamesrich.sofaking.resources.break_quip_realm
import dev.jamesrich.sofaking.resources.break_quip_recline
import dev.jamesrich.sofaking.resources.break_quip_snacks
import dev.jamesrich.sofaking.resources.break_quip_stretch
import dev.jamesrich.sofaking.resources.break_quip_throne
import dev.jamesrich.sofaking.resources.break_quip_unknown
import dev.jamesrich.sofaking.resources.break_screen_back_in
import dev.jamesrich.sofaking.resources.break_screen_title
import dev.jamesrich.sofaking.ui.formatPlayTime
import dev.jamesrich.sofaking.ui.hoursAndMinutes
import dev.jamesrich.sofaking.ui.theme.SofaLavender
import dev.jamesrich.sofaking.ui.theme.SofaMauve
import dev.jamesrich.sofaking.ui.theme.SofaPlum
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit

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
        (hitX && (hitY || nearEdge(nextY, roomY))) || (hitY && nearEdge(nextX, roomX)) -> BounceHit.CORNER
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
    val radians = degrees * PI.toFloat() / HALF_TURN_DEGREES
    val c = cos(radians)
    val s = sin(radians)
    return ColorMatrix().apply {
        for (row in LUMA.indices) {
            for (column in LUMA.indices) {
                val identity = if (row == column) 1f else 0f
                this[row, column] = LUMA[column] + c * (identity - LUMA[column]) + s * HUE_SINE[row][column]
            }
        }
    }
}

private const val HALF_TURN_DEGREES = 180f

/** This color turned [degrees] around the hue wheel by [hueRotation], as the logo's parts are in that hue. */
internal fun Color.hueRotated(degrees: Float): Color {
    val matrix = hueRotation(degrees)
    val rgb = floatArrayOf(red, green, blue)
    fun channel(row: Int) = rgb.indices.sumOf { (matrix[row, it] * rgb[it]).toDouble() }.toFloat().coerceIn(0f, 1f)
    return Color(channel(0), channel(1), channel(2), alpha)
}

/** The share of red, green and blue in lightness, which a hue rotation keeps (the Filter Effects spec's weights). */
private val LUMA = floatArrayOf(0.213f, 0.715f, 0.072f)

/** How far each output channel takes each input channel with the sine of the angle, row by row, from the same spec. */
private val HUE_SINE = arrayOf(
    floatArrayOf(-0.213f, -0.715f, 0.928f),
    floatArrayOf(0.143f, 0.140f, -0.283f),
    floatArrayOf(-0.787f, 0.715f, 0.072f),
)

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

/**
 * What the break screen remembers from one break to the next while the app runs: the next line, and the corners seen.
 */
private object BreakTally {
    val quips = Deck(BREAK_QUIPS.size)
    var corners = 0
}

/**
 * The break screen: the crown-on-sofa mark drifting around and bouncing off the edges the way an old DVD player's
 * screensaver did, a new color on every edge and the brand colors back on a corner, with a little pop and a line to
 * mark the occasion. The screen starts black, and each hit paints it the sofa's color from before the hit, a little
 * see-through so the picture faintly shows under it. [remainingMs], when known, counts down in the middle of the
 * screen, over a line picked for the break, [upNext] (the channel's next program) and the ads dodged today,
 * [dodgedBeforeMs] before this break plus the time it has been up. Only the mark moves; the words stay put in the
 * middle, and the changing color behind them keeps them from burning in. When it goes, [onCover] gets the milliseconds
 * it was on screen, counted in drawn frames, so time in the background is left out. It draws only; the player decides
 * whether the sound plays under it.
 */
@Composable
internal fun BreakScreen(
    modifier: Modifier = Modifier,
    remainingMs: (() -> Long?)? = null,
    upNext: String? = null,
    dodgedBeforeMs: Long = 0,
    onCover: (Long) -> Unit = {},
) {
    val label = stringResource(Res.string.break_screen_title)
    val density = LocalDensity.current
    val speedPxPerSecond = with(density) { BOUNCE_SPEED.toPx() }
    val cornerSlop = with(density) { CORNER_SLOP.toPx() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val logoSize = (minOf(maxWidth, maxHeight) * LOGO_SHARE).coerceIn(MIN_LOGO, MAX_LOGO)
        val area = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        val unit = with(density) { logoSize.roundToPx() }
        val lineWidth = maxWidth * LINE_SHARE
        val motion = remember {
            BreakMotion(Bounce(area.width * START_X, area.height * START_Y, right = true, down = true))
        }
        val quip = remember { BreakTally.quips.next() }
        LaunchedEffect(motion.corner) { motion.endCornerLine() }
        val dodgedMinutes = rememberDodgedMinutes(motion, dodgedBeforeMs, onCover)
        LaunchedEffect(area, unit) { motion.drift(area, IntSize(unit, unit), speedPxPerSecond, cornerSlop) }
        val backdrop by animateColorAsState(breakBackdrop(motion.backdrop), tween(BACKDROP_FADE_MS))
        val ink by animateColorAsState(breakInk(motion.backdrop), tween(BACKDROP_FADE_MS))
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(backdrop) }
                .semantics {
                    contentDescription = label
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            BouncingMark(motion, logoSize)
            val left = remainingMs?.invoke()
            BreakLines(
                if (left != null) stringResource(Res.string.break_screen_back_in, formatPlayTime(left)) else label,
                breakLine(motion.corner, left, quip),
                upNext,
                dodgedMinutes,
                ink,
                lineWidth = lineWidth,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** The mark alone, [size] square, where [motion] has it, in its hue and with its corner pop. */
@Composable
private fun BouncingMark(motion: BreakMotion, size: Dp) {
    val tint = motion.tint
    val filter = remember(tint) { if (tint == 0) null else ColorFilter.colorMatrix(hueRotation(BOUNCE_HUES[tint])) }
    Image(
        painterResource(Res.drawable.banner_logo),
        contentDescription = null,
        colorFilter = filter,
        modifier = Modifier
            .offset { IntOffset(motion.bounce.x.roundToInt(), motion.bounce.y.roundToInt()) }
            .size(size)
            .graphicsLayer {
                // The pop grows out of the corner just hit, which the mark is now heading
                // away from, so it stays on screen.
                val bounce = motion.bounce
                transformOrigin = TransformOrigin(if (bounce.right) 0f else 1f, if (bounce.down) 0f else 1f)
                scaleX = motion.pop.value
                scaleY = motion.pop.value
            },
    )
}

/**
 * The screen behind the mark: black before its first hit, then the sofa's lavender (its body and arms in the logo) in
 * [tint], the hue it wore into its last hit, either way a little see-through.
 */
internal fun breakBackdrop(tint: Int?): Color =
    (if (tint == null) SofaScrim else SofaLavender.hueRotated(BOUNCE_HUES[tint])).copy(alpha = BACKDROP_ALPHA)

/** The words' color over [breakBackdrop] of [tint]: the brand's mauve on black, the mark's dark ink on a sofa color. */
internal fun breakInk(tint: Int?): Color = if (tint == null) SofaMauve else SofaPlum

/**
 * The minutes of ads dodged today: [dodgedBeforeMs] before this break plus the time [motion] has been drawn, which
 * [onCover] gets when the break screen goes.
 */
@Composable
private fun rememberDodgedMinutes(motion: BreakMotion, dodgedBeforeMs: Long, onCover: (Long) -> Unit): Long {
    val dodgedBefore = remember { dodgedBeforeMs }
    val dodgedMinutes by remember {
        derivedStateOf { (dodgedBefore.milliseconds + motion.coveredNanos.nanoseconds).inWholeMinutes }
    }
    val currentOnCover by rememberUpdatedState(onCover)
    DisposableEffect(Unit) { onDispose { currentOnCover(motion.coveredNanos.nanoseconds.inWholeMilliseconds) } }
    return dodgedMinutes
}

/**
 * The mark's drift: where it is and which way it heads ([bounce]), its [tint], the tint it wore into its last hit
 * ([backdrop], null before the first), its [pop] on a corner, the [corner] just hit while its line shows, and how long
 * it has been drawn ([coveredNanos]).
 */
@Stable
private class BreakMotion(start: Bounce) {
    var bounce by mutableStateOf(start)
    var tint by mutableIntStateOf(0)
        private set
    var backdrop by mutableStateOf<Int?>(null)
        private set
    val pop = Animatable(1f)
    var corner by mutableStateOf<Int?>(null)
        private set
    var coveredNanos by mutableLongStateOf(0L)
        private set

    /** Takes the corner's line down once it has shown a while. */
    suspend fun endCornerLine() {
        if (corner == null) return
        delay(CORNER_LINE_MS)
        corner = null
    }

    /** Moves the mark, [unit] in size, around [area] at [speedPxPerSecond], one step a frame, until canceled. */
    suspend fun drift(area: IntSize, unit: IntSize, speedPxPerSecond: Float, cornerSlop: Float): Unit = coroutineScope {
        val roomX = (area.width - unit.width).toFloat()
        val roomY = (area.height - unit.height).toFloat()
        bounce =
            bounce.copy(
                x = bounce.x.coerceIn(0f, roomX.coerceAtLeast(0f)),
                y = bounce.y.coerceIn(0f, roomY.coerceAtLeast(0f)),
            )
        var last = 0L
        while (true) {
            val hit = withFrameNanos { now ->
                val gap = if (last == 0L) 0L else now - last
                last = now
                // A long gap (the app in the background) moves the mark no more than one frame's worth, and does
                // not count as covered.
                if (gap < MAX_COVERED_GAP_NANOS) coveredNanos += gap
                val seconds = gap.nanoseconds.toDouble(DurationUnit.SECONDS).toFloat()
                    .coerceAtMost(MAX_FRAME_SECONDS)
                val (next, hit) = bounce.step(speedPxPerSecond * seconds, roomX, roomY, cornerSlop)
                bounce = next
                hit
            }
            if (hit != BounceHit.NONE) {
                backdrop = tint
                tint = bounce.tint
            }
            if (hit == BounceHit.CORNER) {
                launch {
                    pop.snapTo(CORNER_POP)
                    pop.animateTo(1f, tween(CORNER_POP_MS))
                }
                corner = ++BreakTally.corners
            }
        }
    }
}

/** The line under the countdown: a corner's cheer while it shows, else almost back, else the break's [quip]. */
@Composable
private fun breakLine(corner: Int?, leftMs: Long?, quip: Int): String = when {
    corner == 1 -> stringResource(Res.string.break_corner_first)
    corner != null -> stringResource(Res.string.break_corner_again, corner)
    leftMs == null -> stringResource(Res.string.break_quip_unknown)
    leftMs <= ALMOST_BACK_MS -> stringResource(Res.string.break_quip_almost)
    else -> stringResource(BREAK_QUIPS[quip])
}

/**
 * The countdown, the [line] under it, then fainter, what is on next and the ads dodged today, all in [ink] and each at
 * most [lineWidth] wide.
 */
@Composable
@Suppress("LongParameterList") // One argument per line drawn, plus their look.
private fun BreakLines(
    countdown: String,
    line: String,
    upNext: String?,
    dodgedMinutes: Long,
    ink: Color,
    lineWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val width = Modifier.widthIn(max = lineWidth)
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            countdown,
            style = MaterialTheme.typography.titleLarge,
            color = ink,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            line,
            style = MaterialTheme.typography.bodyLarge,
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = width,
        )
        val faint = ink.copy(alpha = 0.7f)
        if (upNext != null) {
            Text(
                upNext,
                style = MaterialTheme.typography.bodySmall,
                color = faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = width,
            )
        }
        if (dodgedMinutes > 0) {
            Text(
                stringResource(Res.string.break_ads_dodged, dodgedMinutes.minutes.hoursAndMinutes()),
                style = MaterialTheme.typography.bodySmall,
                color = faint,
                maxLines = 1,
                modifier = width,
            )
        }
    }
}

/** How fast the mark drifts, on each axis. */
private val BOUNCE_SPEED = 72.dp

/** The mark's size, as a share of the screen's shorter side, and its bounds on a phone and a large TV. */
private const val LOGO_SHARE = 0.22f
private val MIN_LOGO = 96.dp
private val MAX_LOGO = 240.dp

/** How wide the words in the middle may run, as a share of the screen's width. */
private const val LINE_SHARE = 0.6f

/** How much of the picture the screen lets through: hardly any, so the stream only just shows. */
private const val BACKDROP_ALPHA = 0.9f

/** How long the screen takes to turn the new color after a hit. */
private const val BACKDROP_FADE_MS = 400

/** Where the mark starts, as a share of the screen's width and height. */
private const val START_X = 0.3f
private const val START_Y = 0.2f

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
