package dev.jamesrich.sofaking.ui.preview

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import dev.jamesrich.sofaking.ui.player.buildLivePlayer

/** The full-screen player's TS setup without captions, decoding video only, with no MediaSession. */
internal fun buildPreviewPlayer(context: Context): ExoPlayer =
    buildLivePlayer(context, captions = null).apply {
        volume = 0f
        // Audio would be decoded and mixed at zero volume; text would be drawn over the preview.
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }
