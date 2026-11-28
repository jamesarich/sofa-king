package dev.jamesrich.sofaking.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.Appearance
import dev.jamesrich.sofaking.ui.LayoutProfile
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.ProgressBar
import dev.jamesrich.sofaking.ui.SofaLoader
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.settings.ToggleRow
import dev.jamesrich.sofaking.ui.theme.OverVideo
import dev.jamesrich.sofaking.ui.theme.SofaActionChip
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaChip
import dev.jamesrich.sofaking.ui.theme.SofaIconAction
import dev.jamesrich.sofaking.ui.theme.SofaIconToggle
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import dev.jamesrich.sofaking.ui.theme.SofaMenuChip
import dev.jamesrich.sofaking.ui.theme.SofaOnPicture
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import dev.jamesrich.sofaking.ui.theme.overlaySurface

/**
 * The design system on one page, for androidApp's screenshot tests: the scheme's color roles, the type styles and
 * shapes the screens use, and the shared components at rest, in [appearance]. A change to a token or a component shows
 * as a change to this one picture. docs/design-system.md says what each is for.
 */
@Composable
fun DesignSystemPreview(tv: Boolean, modifier: Modifier = Modifier, appearance: Appearance = Appearance.DARK) {
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    SofaKingTheme(appearance) {
        CompositionLocalProvider(LocalLayoutProfile provides LayoutProfile.of(tv, sizeClass)) {
            Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (tv) {
                    Row(Modifier.screenPadding(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(COLORS_SHARE), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Colors()
                            Shapes()
                        }
                        Column(Modifier.weight(1f)) { Type() }
                        Column(Modifier.weight(COMPONENTS_SHARE)) { Components() }
                    }
                } else {
                    Column(Modifier.screenPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Colors()
                        Shapes()
                        Type()
                        Components()
                    }
                }
            }
        }
    }
}

private const val COLORS_SHARE = 1.3f
private const val COMPONENTS_SHARE = 1.2f

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** A role's name in its own text color on its container. */
private class Role(val name: String, val fill: Color, val ink: Color)

private fun roles(s: ColorScheme) = listOf(
    Role("primary", s.primary, s.onPrimary),
    Role("primaryContainer", s.primaryContainer, s.onPrimaryContainer),
    Role("secondary", s.secondary, s.onSecondary),
    Role("secondaryContainer", s.secondaryContainer, s.onSecondaryContainer),
    Role("tertiaryContainer", s.tertiaryContainer, s.onTertiaryContainer),
    Role("error", s.error, s.onError),
    Role("errorContainer", s.errorContainer, s.onErrorContainer),
    Role("inverseSurface", s.inverseSurface, s.inverseOnSurface),
    Role("surface", s.surface, s.onSurface),
    Role("surfaceVariant", s.surfaceVariant, s.onSurfaceVariant),
    Role("containerLow", s.surfaceContainerLow, s.onSurface),
    Role("container", s.surfaceContainer, s.onSurface),
    Role("containerHighest", s.surfaceContainerHighest, s.onSurface),
    Role("primaryFixed", s.primaryFixed, s.onPrimaryFixed),
    Role("secondaryFixed", s.secondaryFixed, s.onSecondaryFixed),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Colors() = Section("Color roles") {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2,
    ) {
        roles(MaterialTheme.colorScheme).forEach { Swatch(it.name, it.fill, it.ink, Modifier.weight(1f)) }
        // The over-picture panel, with the dark scheme's text on it in any scheme.
        OverVideo {
            Swatch("overlay scrim", SofaOverlayScrim, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Swatch(name: String, fill: Color, ink: Color, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        modifier
            .height(32.dp)
            .background(fill, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(name, color = ink, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun Shapes() = Section("Shapes") {
    val shapes = MaterialTheme.shapes
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShapeTile("xs", shapes.extraSmall)
        ShapeTile("small", shapes.small)
        ShapeTile("medium", shapes.medium)
        ShapeTile("large", shapes.large)
        ShapeTile("xl", shapes.extraLarge)
    }
}

@Composable
private fun ShapeTile(name: String, shape: Shape) {
    Box(
        Modifier.size(56.dp, 48.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Type() = Section("Type") {
    val type = MaterialTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TypeLine("headlineSmall", type.headlineSmall)
        TypeLine("titleLarge", type.titleLarge)
        TypeLine("titleMedium", type.titleMedium)
        TypeLine("titleMediumEmphasized", type.titleMediumEmphasized)
        TypeLine("titleSmall", type.titleSmall)
        TypeLine("bodyLarge", type.bodyLarge)
        TypeLine("bodyMedium", type.bodyMedium)
        TypeLine("bodySmall", type.bodySmall)
        TypeLine("labelLarge", type.labelLarge)
        TypeLine("labelMedium", type.labelMedium)
    }
}

@Composable
private fun TypeLine(name: String, style: TextStyle) {
    Text(name, style = style, maxLines = 1)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Components() = Section("Components") {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SofaButton(onClick = {}, main = true) { Text("Play") }
            SofaButton(onClick = {}) { Text("Record") }
            SofaButton(onClick = {}, enabled = false) { Text("Delete") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SofaChip(selected = true, onClick = {}, label = "Sports")
            SofaChip(selected = false, onClick = {}, label = "News")
            SofaActionChip(onClick = {}, label = "Now")
            SofaMenuChip("Genre", listOf("Genre"), "Genre", { it }, {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SofaIconToggle(checked = true, onCheckedChange = {}, icon = Icons.Filled.Favorite, label = "Watchlist")
            SofaIconToggle(checked = false, onCheckedChange = {}, icon = Icons.Filled.Favorite, label = "Watchlist")
            SofaIconAction(onClick = {}, icon = Icons.Filled.Refresh, label = "Refresh")
            SofaLoader(size = 32.dp)
        }
        ToggleRow("Closed captions", checked = true, onCheckedChange = {
        }, help = "Shows captions when a channel has them")
        ToggleRow("Start in the guide", checked = false, onCheckedChange = {})
        OverPicture()
    }
}

private const val PROGRESS = 0.4f

/** A stand-in picture with the overlay panel, the text that goes on it and a progress bar along its bottom edge. */
@Composable
private fun OverPicture() {
    val scheme = MaterialTheme.colorScheme
    val picture = Brush.horizontalGradient(listOf(scheme.tertiary, SofaOnPicture, SofaScrim))
    Box(Modifier.fillMaxWidth().height(80.dp).clip(MaterialTheme.shapes.small).background(picture)) {
        OverVideo {
            Column(
                Modifier.align(Alignment.Center).overlaySurface().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Over the picture", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Paused",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ProgressBar(PROGRESS, Modifier.align(Alignment.BottomCenter))
    }
}
