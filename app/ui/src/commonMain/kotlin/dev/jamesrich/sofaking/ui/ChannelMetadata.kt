package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.GuideStatus
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.guide_out_of_date
import dev.jamesrich.sofaking.resources.listing_loading
import dev.jamesrich.sofaking.resources.no_guide
import dev.jamesrich.sofaking.resources.no_listing
import dev.jamesrich.sofaking.resources.preview_live
import dev.jamesrich.sofaking.resources.program_new
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The episode label and title, for example "S2 E5  The Pilot", or null when the guide has neither. */
internal fun Program.episodeLine(): String? = listOfNotNull(episode, subTitle).joinToString("  ").ifEmpty { null }

/** Why a stretch of a channel shows no program, with the text it reads as. */
internal enum class NoListing(val label: StringResource) {
    NO_GUIDE(Res.string.no_guide),
    OUT_OF_DATE(Res.string.guide_out_of_date),
    LOADING(Res.string.listing_loading),
    NONE(Res.string.no_listing),
}

/**
 * What to show in place of a program: why the channel has none, as far as the server says, or that its programs
 * are still on the way while [guideLoading].
 */
internal fun Channel.noListing(guideLoading: Boolean): NoListing = when (guideStatus) {
    GuideStatus.UNASSIGNED -> NoListing.NO_GUIDE
    GuideStatus.STALE -> NoListing.OUT_OF_DATE
    GuideStatus.EMPTY, GuideStatus.AVAILABLE -> if (guideLoading) NoListing.LOADING else NoListing.NONE
}

/** The text of [noListing]. */
@Composable
internal fun Channel.noListingText(guideLoading: Boolean): String = stringResource(noListing(guideLoading).label)

/** Between the parts of a program's detail line, such as its time range and episode. */
internal const val DETAIL_SEPARATOR = "  ·  "

/**
 * The letters a channel without a logo shows on its tile: the first letter of each of the first two words of its
 * [name] in capitals, else its [number] when the name has no letters or digits.
 */
internal fun logoMonogram(name: String, number: String): String {
    val initials = name.split(' ', '-', '_').mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
    return if (initials.isEmpty()) number else initials.take(2).joinToString("").uppercase()
}

/** The share of a logo tile's height its letters take, so a two-letter monogram reads as the guide's body text does. */
private const val LOGO_TEXT_SHARE = 0.5f

/** The widest a bold glyph runs, as a share of its size, and the smallest a long fallback shrinks to. */
private const val GLYPH_WIDTH_SHARE = 0.6f
private val MIN_LOGO_TEXT = 10.dp

/**
 * The size of [text] on a [width] by [height] tile: a share of the height, shrunk until a long channel number fits the
 * width, never below [MIN_LOGO_TEXT].
 */
internal fun logoTextSize(text: String, width: Dp, height: Dp): Dp {
    val fitting = width * 0.9f / (GLYPH_WIDTH_SHARE * text.length.coerceAtLeast(1))
    return minOf(height * LOGO_TEXT_SHARE, fitting).coerceAtLeast(MIN_LOGO_TEXT)
}

/**
 * The channel's logo in a [width] by [height] slot, so names line up; a channel without one shows [logoMonogram] on a
 * tile of the same size. Decorative: the name is beside it.
 */
@Composable
internal fun ChannelLogo(channel: Channel, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val logo = channel.logo
    val slot = modifier.size(width = width, height = height)
    if (logo == null) {
        Box(
            slot.clearAndSetSemantics {}
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.extraSmall),
            contentAlignment = Alignment.Center,
        ) {
            val monogram = logoMonogram(channel.name, channel.number.formatNumber())
            // Anchored to dp, so the system font scale does not push the letters out of a tile that does not scale.
            val size = with(LocalDensity.current) { logoTextSize(monogram, width, height).toSp() }
            Text(
                monogram,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontSize = size,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        AsyncImage(
            rememberFadingRequest(logo),
            contentDescription = null,
            modifier = slot,
            contentScale = ContentScale.Fit,
        )
    }
}

/** Live, New and the rating, in that order; [fill] and [onFill] color the filled badges, [outline] the rating. */
@Composable
internal fun ProgramBadges(
    program: Program,
    live: Boolean,
    fill: Color,
    onFill: Color,
    outline: Color,
    modifier: Modifier = Modifier,
) {
    if (!live && !program.isNew && program.rating == null) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (live) FilledBadge(stringResource(Res.string.preview_live), fill, onFill)
        if (program.isNew) FilledBadge(stringResource(Res.string.program_new), fill, onFill)
        program.rating?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = outline,
                maxLines = 1,
                modifier = Modifier.border(1.dp, outline, MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun FilledBadge(text: String, fill: Color, onFill: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = onFill,
        maxLines = 1,
        modifier = Modifier.background(
            fill,
            MaterialTheme.shapes.extraSmall,
        ).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
