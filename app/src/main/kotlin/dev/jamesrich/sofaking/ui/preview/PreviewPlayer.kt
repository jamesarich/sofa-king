package dev.jamesrich.sofaking.ui.preview

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/** Same TS flags as the full-screen player, muted, with no MediaSession. Dispatcharr streams can start mid-GOP. */
@OptIn(UnstableApi::class)
internal fun buildPreviewPlayer(context: Context): ExoPlayer {
    val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
    )
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
        .build()
        .apply {
            volume = 0f
            playWhenReady = true
        }
}
