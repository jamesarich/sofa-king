package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.layout.ContentScale

/** How the picture meets the screen when their shapes differ, as a TV's zoom key sets it. */
enum class VideoZoom {
    /** The whole picture, with bars where the shapes differ. */
    FIT,

    /** The screen filled, cropping what overhangs it, as a 4:3 channel loses its pillars. */
    FILL,

    /** The screen filled by stretching the picture to it. */
    STRETCH;

    /** The zoom one press of the Zoom key or button moves to, back round to [FIT] after [STRETCH]. */
    fun next(): VideoZoom = entries[(ordinal + 1) % entries.size]

    val contentScale: ContentScale
        get() = when (this) {
            FIT -> ContentScale.Fit
            FILL -> ContentScale.Crop
            STRETCH -> ContentScale.FillBounds
        }
}
