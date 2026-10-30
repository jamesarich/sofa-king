package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.ui.Haptics
import dev.jamesrich.sofaking.ui.NoListing
import dev.jamesrich.sofaking.ui.TimeFormatter
import dev.jamesrich.sofaking.ui.noListing

/**
 * Everything a row's cells need that is the same for every cell on screen; one instance per guide, so cells skip.
 * The `cell*` strings are resource templates, filled only when the semantics tree is read.
 */
@Immutable
internal class GuideRowEnv(
    val cellAiring: String,
    val cellLater: String,
    val cellNone: String,
    val timeFormat: TimeFormatter,
    val noListing: String,
    val listingLoading: String,
    /** [Lineup.guideLoading]: a stretch with no program may yet get one. */
    val guideLoading: Boolean,
    val noGuide: String,
    val guideOutOfDate: String,
    val favoriteLabel: String,
    val toggleFavoriteLabel: String,
    val minutePx: Float,
    val minStickyTextPx: Float,
    val scrollState: ScrollState,
    val onCellFocus: (Channel, GuideCell) -> Unit,
    val onPlay: (Channel) -> Unit,
    val onToggleFavorite: (Channel) -> Unit,
    /** A held OK, or the Info key, opens the program's details. */
    val onOpenDetails: (Channel, GuideCell) -> Unit,
    val detailsLabel: String,
    val watchLabel: String,
    val laterLabel: String,
    /** A Later line's program was chosen: the grid jumps to its cell. */
    val onJumpTo: (Channel, Program) -> Unit,
    val haptics: Haptics,
) {
    /** The text of a stretch with no program, as [noListing] decides. */
    fun noListingFor(channel: Channel): String = when (channel.noListing(guideLoading)) {
        NoListing.NO_GUIDE -> noGuide
        NoListing.OUT_OF_DATE -> guideOutOfDate
        NoListing.LOADING -> listingLoading
        NoListing.NONE -> noListing
    }
}
