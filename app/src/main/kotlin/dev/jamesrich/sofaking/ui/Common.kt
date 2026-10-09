package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.R

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
