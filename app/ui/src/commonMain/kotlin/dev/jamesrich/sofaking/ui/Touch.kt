package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * Gives a gesture-only surface (a picture that shows its controls on a tap) a click a screen reader can perform:
 * a button named [label], plus [extra] actions for gestures with no tap of their own (a swipe).
 */
internal fun Modifier.pictureButton(
    label: String,
    onPress: () -> Unit,
    extra: List<CustomAccessibilityAction> = emptyList(),
): Modifier = semantics {
    role = Role.Button
    onClick(label = label) {
        onPress()
        true
    }
    if (extra.isNotEmpty()) customActions = extra
}

/** Marks a section title as a heading, so a screen reader can jump between sections. */
internal fun Modifier.sectionHeading(): Modifier = semantics { heading() }

/**
 * Touch feedback: a tick for a long press, a release or a double-tap seek. A television has no touch, so [enabled]
 * is false there and every call is a no-op.
 */
internal class Haptics(private val feedback: HapticFeedback, private val enabled: Boolean) {
    /** A long press opened a menu or a details sheet. */
    fun longPress() = perform(HapticFeedbackType.LongPress)

    /** A drag ended, as a seek bar's release. */
    fun release() = perform(HapticFeedbackType.GestureEnd)

    /** A discrete jump landed, as a double-tap seek. */
    fun step() = perform(HapticFeedbackType.SegmentTick)

    private fun perform(type: HapticFeedbackType) {
        if (enabled) feedback.performHapticFeedback(type)
    }
}

@Composable
internal fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val tv = LocalLayoutProfile.current.tv
    return remember(feedback, tv) { Haptics(feedback, enabled = !tv) }
}

/** Moves a list row to its new place when the list reorders. A television cuts, so focus never chases a moving row. */
@Composable
internal fun LazyItemScope.reorderMotion(): Modifier =
    if (LocalLayoutProfile.current.tv) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)

/** [reorderMotion] for a grid's cell. */
@Composable
internal fun LazyGridItemScope.reorderMotion(): Modifier =
    if (LocalLayoutProfile.current.tv) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
