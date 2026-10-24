package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.banner_logo
import dev.jamesrich.sofaking.resources.loading
import dev.jamesrich.sofaking.resources.retry
import dev.jamesrich.sofaking.resources.tagline
import dev.jamesrich.sofaking.ui.theme.SofaButton
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The crown-on-sofa mark, decorative wherever the app name is printed beside it. */
@Composable
internal fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.banner_logo),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}

/**
 * A full-screen state with the brand on it: loading, a failed load, an empty lineup. [detail] is the
 * technical reason in a quieter color; [hint] tells the viewer what to do about it; [loading] adds the loader.
 */
@Composable
fun MessageScreen(
    message: String,
    detail: String? = null,
    hint: String? = null,
    onRetry: (() -> Unit)? = null,
    loading: Boolean = false,
) {
    Box(Modifier.fillMaxSize().screenPadding(), contentAlignment = Alignment.Center) {
        // A phone in landscape is too short for the logo, the reason and Retry together, so the message scrolls.
        Column(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LogoMark(96.dp)
            Text(
                stringResource(Res.string.tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (loading) SofaLoader(size = 56.dp)
            Text(message, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            if (onRetry != null) {
                // A failed load has no other focus target, so Retry takes focus when the screen shows.
                val retryFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { requestFocusWhenAttached(retryFocus) }
                Spacer(Modifier.height(4.dp))
                SofaButton(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) {
                    Text(stringResource(Res.string.retry))
                }
            }
        }
    }
}

/** "<number>  <name>", as every list and overlay shows a channel. */
internal fun Channel.label(): String = "${number.formatNumber()}  $name"
