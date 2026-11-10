package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.player_sleep_card
import dev.jamesrich.sofaking.resources.player_sleep_dismiss
import dev.jamesrich.sofaking.resources.player_sleep_extend
import dev.jamesrich.sofaking.resources.player_sleep_keep
import dev.jamesrich.sofaking.resources.player_sleep_soon
import dev.jamesrich.sofaking.resources.player_sleep_title
import dev.jamesrich.sofaking.ui.isOk
import dev.jamesrich.sofaking.ui.theme.SofaButton
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** The sleep timer's warning card comes up this many seconds before the player leaves. */
internal const val SLEEP_WARNING_SECONDS = 60

/** What OK on the warning card adds to the timer. */
internal const val SLEEP_EXTEND_MS = 15 * 60_000L

private const val MS_PER_SECOND = 1_000L

/**
 * The seconds the warning card shows, rounded up, or null while it stays away: no stop time is set, it is more than
 * [SLEEP_WARNING_SECONDS] off or already past, or the viewer dismissed the card for this very [stopAtMs]
 * ([dismissedFor]). A new stop time, by extending or a new step of the timer, brings the card back.
 */
internal fun sleepWarningSeconds(stopAtMs: Long?, nowMs: Long, dismissedFor: Long?): Int? {
    val stop = stopAtMs ?: return null
    val leftMs = stop - nowMs
    if (stop == dismissedFor || leftMs <= 0) return null
    return ((leftMs + MS_PER_SECOND - 1) / MS_PER_SECOND).toInt().takeIf { it <= SLEEP_WARNING_SECONDS }
}

/** The warning card's state over [sleep]: the seconds it shows, and what OK and Back do while it is up. */
@Stable
internal class SleepWarning(private val sleep: SleepTimer, private val held: () -> Boolean = { false }) {
    /** The seconds left while the card is due, null otherwise. */
    var secondsLeft by mutableStateOf<Int?>(null)
        private set

    private var dismissedFor: Long? = null

    /** The card is up: due, and not held back by another card ([held], such as Up next). */
    val showing: Boolean get() = secondsLeft != null && !held()

    /** The system Back while the card is up dismisses it; false when there is no card for Back to dismiss. */
    fun back(): Boolean {
        if (!showing) return false
        dismiss()
        return true
    }

    fun tick(nowMs: Long) {
        secondsLeft = sleepWarningSeconds(sleep.stopAtMs, nowMs, dismissedFor)
    }

    /** Back: the card goes away and the timer runs on. */
    fun dismiss() {
        dismissedFor = sleep.stopAtMs
        secondsLeft = null
    }

    /** OK: the player stays [SLEEP_EXTEND_MS] longer. */
    fun extend() {
        sleep.extend(SLEEP_EXTEND_MS)
        secondsLeft = null
    }

    /**
     * OK while the card is up extends the timer on its key-up, unless the bar's buttons have focus and take OK
     * themselves. True when the key is taken.
     */
    fun onKey(key: Key, type: KeyEventType, buttonsFocused: Boolean): Boolean {
        if (!showing || buttonsFocused || !key.isOk) return false
        if (type == KeyEventType.KeyUp) extend()
        return true
    }
}

/** The [SleepWarning] for [sleep], read again every second while a stop time is set. */
@Composable
internal fun rememberSleepWarning(sleep: SleepTimer, held: () -> Boolean = { false }): SleepWarning {
    val warning = remember(sleep) { SleepWarning(sleep, held) }
    val stopAt = sleep.stopAtMs
    LaunchedEffect(warning, stopAt) {
        while (true) {
            val now = Clock.System.now().toEpochMilliseconds()
            warning.tick(now)
            if (stopAt == null || stopAt <= now) break
            delay(MS_PER_SECOND)
        }
    }
    return warning
}

/** The [SleepCard] while [warning] is up. */
@Composable
internal fun BoxScope.SleepWarningCard(warning: SleepWarning, pointerDriven: Boolean) {
    val left = warning.secondsLeft
    if (left != null && warning.showing) {
        SleepCard(left, pointerDriven, onExtend = warning::extend, onDismiss = warning::dismiss)
    }
}

/**
 * The sleep warning in the corner, with a bar that drains in whole seconds. It takes no focus: OK and Back come
 * through the screen's keys; a pointer gets two buttons. A screen reader hears the card once, from its title; the
 * seconds are left out of its semantics.
 */
@Composable
internal fun BoxScope.SleepCard(
    secondsLeft: Int,
    pointerDriven: Boolean,
    onExtend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spoken = stringResource(Res.string.player_sleep_soon)
    CornerCard {
        Text(
            stringResource(Res.string.player_sleep_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        )
        LinearProgressIndicator(
            progress = { secondsLeft.toFloat() / SLEEP_WARNING_SECONDS },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        )
        Text(
            stringResource(Res.string.player_sleep_card, secondsLeft),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clearAndSetSemantics {},
        )
        if (pointerDriven) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SofaButton(main = true, onClick = onExtend) { Text(stringResource(Res.string.player_sleep_extend)) }
                SofaButton(onClick = onDismiss) { Text(stringResource(Res.string.player_sleep_dismiss)) }
            }
        } else {
            Text(
                stringResource(Res.string.player_sleep_keep),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
