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
import dev.jamesrich.sofaking.resources.player_code_label
import dev.jamesrich.sofaking.resources.player_retry_in
import dev.jamesrich.sofaking.resources.player_retry_now
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import org.jetbrains.compose.resources.stringResource

/** The [FailureCard] code for [e]: the server's error code, or `SESSION_UNAVAILABLE` when the server gave none. */
internal fun failureCode(e: Throwable): String = (e as? SofaKingException)?.code ?: SESSION_UNAVAILABLE

/**
 * [retryInSeconds] counts down to the next automatic reconnect; 0 while it runs, null while none is scheduled. A
 * lambda, so the countdown recomposes only this card.
 */
@Composable
internal fun BoxScope.FailureCard(code: String, live: Boolean = true, retryInSeconds: () -> Int? = { null }) {
    val retryIn = retryInSeconds()
    val copy = failureCopy(code, live)
    Column(
        Modifier
            .align(Alignment.Center)
            .background(SofaOverlayScrim, MaterialTheme.shapes.small)
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(copy.reason),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(copy.hint),
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
            stringResource(Res.string.player_code_label, code),
            modifier = Modifier.semantics { hideFromAccessibility() },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
