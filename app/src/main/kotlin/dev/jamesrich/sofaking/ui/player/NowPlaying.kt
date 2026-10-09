package dev.jamesrich.sofaking.ui.player

import android.net.Uri
import androidx.media3.common.MediaMetadata
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.formatNumber

/** Session metadata for a channel: the programme on top, the channel number and name under it. */
internal fun nowPlayingMetadata(channel: Channel, programmeTitle: String?): MediaMetadata =
    MediaMetadata.Builder()
        .setTitle(programmeTitle ?: channel.name)
        .setSubtitle("${channel.number.formatNumber()}  ${channel.name}")
        .setArtworkUri(channel.logo?.let(Uri::parse))
        .setIsPlayable(true)
        .build()
