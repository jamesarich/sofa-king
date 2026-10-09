package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

@Composable
fun MessageScreen(message: String, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(message, style = MaterialTheme.typography.titleLarge)
            if (onRetry != null) Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
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
