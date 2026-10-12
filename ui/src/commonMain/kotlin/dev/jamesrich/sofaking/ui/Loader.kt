package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.ui.theme.SofaAmber

/** The brand's busy indicator: Material 3 Expressive's morphing loader in amber. Decorative; pair it with a text label. */
@Composable
fun SofaLoader(modifier: Modifier = Modifier, size: Dp = 48.dp, color: Color = SofaAmber) {
    LoadingIndicator(modifier = modifier.size(size), color = color)
}
