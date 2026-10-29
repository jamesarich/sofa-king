package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.cant_play
import dev.jamesrich.sofaking.resources.cant_play_retry
import dev.jamesrich.sofaking.resources.cant_reach_stream
import dev.jamesrich.sofaking.resources.no_working_source
import dev.jamesrich.sofaking.resources.no_working_source_hint
import dev.jamesrich.sofaking.resources.player_retry_in
import dev.jamesrich.sofaking.resources.player_retry_now
import dev.jamesrich.sofaking.resources.stream_refused
import dev.jamesrich.sofaking.resources.stream_unsupported
import dev.jamesrich.sofaking.resources.vod_cant_play
import dev.jamesrich.sofaking.resources.vod_cant_play_retry
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The server's answer when every source of a channel is down or quarantined. */
internal const val SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE"

/**
 * Plain-English headline for a playback error; [code] is the ExoPlayer error name or a session code, and [live] tells
 * a channel from a library item or recording.
 */
internal fun failureReasonRes(code: String, live: Boolean = true): StringResource = when {
    SOURCE_UNAVAILABLE in code -> Res.string.no_working_source
    "NETWORK_CONNECTION" in code -> Res.string.cant_reach_stream
    "BAD_HTTP_STATUS" in code -> Res.string.stream_refused
    "PARSING" in code || "UNSUPPORTED" in code -> Res.string.stream_unsupported
    live -> Res.string.cant_play
    else -> Res.string.vod_cant_play
}

/** The [FailureCard] code for [e]: the server's error code, or `SESSION_UNAVAILABLE` when the server gave none. */
internal fun failureCode(e: Throwable): String = (e as? SofaKingException)?.code ?: SESSION_UNAVAILABLE

private const val SESSION_UNAVAILABLE = "SESSION_UNAVAILABLE"

/**
 * What the card says under the headline: a channel with no working source gets its own
 * line, and an item has no reconnect.
 */
internal fun failureHintRes(code: String, live: Boolean = true): StringResource = when {
    !live -> Res.string.vod_cant_play_retry
    SOURCE_UNAVAILABLE in code -> Res.string.no_working_source_hint
    else -> Res.string.cant_play_retry
}

/**
 * [retryInSeconds] counts down to the next automatic reconnect; 0 while it runs, null while none is scheduled. A
 * lambda, so the countdown recomposes only this card.
 */
@Composable
internal fun BoxScope.FailureCard(code: String, live: Boolean = true, retryInSeconds: () -> Int? = { null }) {
    val retryIn = retryInSeconds()
    Column(
        Modifier
            .align(Alignment.Center)
            .background(SofaOverlayScrim, MaterialTheme.shapes.small)
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(failureReasonRes(code, live)),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(failureHintRes(code, live)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (retryIn != null) {
            Text(
                if (retryIn > 0) {
                    stringResource(Res.string.player_retry_in, retryIn)
                } else {
                    stringResource(Res.string.player_retry_now)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            code,
            modifier = Modifier.semantics { hideFromAccessibility() },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
