package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.kids_mode_badge
import org.jetbrains.compose.resources.stringResource

/** Whether kids mode is on (see `KidsMode`); App provides it from the settings. */
internal val LocalKidsMode = compositionLocalOf { false }

/** The header's Kids mark while kids mode is on. */
@Composable
internal fun KidsBadge(modifier: Modifier = Modifier) {
    Text(
        stringResource(Res.string.kids_mode_badge),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        maxLines = 1,
        modifier = modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
