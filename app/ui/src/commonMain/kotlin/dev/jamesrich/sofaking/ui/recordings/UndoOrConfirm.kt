package dev.jamesrich.sofaking.ui.recordings

import dev.jamesrich.sofaking.data.RecordingState

/**
 * Whether the end button acts at once and offers Undo, instead of asking first. Only canceling a scheduled recording
 * can be put back; stopping a running one and deleting a file cannot, and a TV has no way to reach a snackbar's
 * button without taking focus from the list, so it keeps the question.
 */
internal fun undoesInsteadOfConfirming(tv: Boolean, state: RecordingState): Boolean =
    !tv && state == RecordingState.SCHEDULED
