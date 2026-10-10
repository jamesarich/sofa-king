package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.R
import dev.jamesrich.sofaking.data.Channel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The crown-on-sofa mark, decorative wherever the app name is printed beside it. */
@Composable
internal fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.banner_logo),
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
    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(640.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LogoMark(96.dp)
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (loading) SofaLoader(size = 56.dp)
            Text(message, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            if (onRetry != null) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

internal fun Double.formatNumber(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

/** "<number>  <name>", as every list and overlay shows a channel. */
internal fun Channel.label(): String = "${number.formatNumber()}  $name"

/** Wall-clock time in the device zone, for example "1:30 PM". */
@Composable
internal fun rememberTimeFormatter(): DateTimeFormatter = remember {
    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault())
}

internal fun DateTimeFormatter.range(start: Instant, stop: Instant): String = "${format(start)} - ${format(stop)}"
