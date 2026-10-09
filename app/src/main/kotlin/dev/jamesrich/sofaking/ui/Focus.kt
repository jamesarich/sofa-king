package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/** Requests focus once the node is attached; a requester throws until its modifier has been placed. */
internal suspend fun requestFocusWhenAttached(request: FocusRequester) {
    repeat(10) {
        withFrameNanos { }
        if (runCatching { request.requestFocus() }.isSuccess) return
    }
}
