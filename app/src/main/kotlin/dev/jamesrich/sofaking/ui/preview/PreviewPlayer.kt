package dev.jamesrich.sofaking.ui.preview

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import dev.jamesrich.sofaking.ui.player.buildLivePlayer

/** The full-screen player's TS setup without captions, muted, with no MediaSession. */
internal fun buildPreviewPlayer(context: Context): ExoPlayer =
    buildLivePlayer(context, captions = null).apply { volume = 0f }
