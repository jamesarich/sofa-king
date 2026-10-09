package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.jamesrich.sofaking.R
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import java.time.Instant

@Composable
fun ChannelListScreen(lineup: Lineup, onPlay: (Channel) -> Unit, modifier: Modifier = Modifier) {
    val now = Instant.now()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(lineup.channels, key = { it.guideId + it.number }) { channel ->
            val (current, next) = lineup.nowAndNext(channel, now)
            ListItem(
                selected = false,
                onClick = { onPlay(channel) },
                leadingContent = {
                    Box(Modifier.width(120.dp), contentAlignment = Alignment.CenterStart) {
                        if (channel.logo != null) {
                            AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(width = 96.dp, height = 40.dp))
                        } else {
                            Text(channel.number.formatNumber(), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                },
                headlineContent = {
                    Text("${channel.number.formatNumber()}  ${channel.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Column {
                        Text(current?.title ?: stringResource(R.string.no_listing), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        next?.let { Text(stringResource(R.string.up_next, it.title), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                },
                trailingContent = { Text(channel.group, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

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
