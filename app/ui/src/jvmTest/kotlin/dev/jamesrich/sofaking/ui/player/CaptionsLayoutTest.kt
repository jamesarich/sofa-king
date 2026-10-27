package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.PlaybackStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** Captions on a 16:9 screen land on the picture the player shows, not on the bars beside it. */
@OptIn(ExperimentalTestApi::class)
class CaptionsLayoutTest {
    /** A player showing one cue at the picture's left edge, with a picture of [size]. */
    private class CuePlayer(size: IntSize?) : LivePlayer {
        override val cues: StateFlow<List<CaptionCue>> = MutableStateFlow(
            listOf(
                CaptionCue(
                    AnnotatedString("Left column"),
                    position = 0f,
                    positionAnchor = CaptionCue.ANCHOR_TYPE_START,
                ),
            ),
        )
        override val videoSize: StateFlow<IntSize?> = MutableStateFlow(size)
        override val status: StateFlow<PlayerStatus> = MutableStateFlow(PlayerStatus())
        override val errors: SharedFlow<PlayerError> = MutableSharedFlow()
        override val captionSync = CaptionSync()
        override val currentStreamUrl: String? = null
        override val currentChannelId: String? = null
        override val hasItem = false
        override val bufferedMs = 0L
        override val supportsCaptionLead = false
        override val hasTextTrack = true
        override val audioTracks: StateFlow<List<AudioTrack>> = MutableStateFlow(emptyList())
        override var volume = 1f
        override var playWhenReady = true

        override fun selectAudioTrack(id: String) = Unit
        override fun streamInfo() = StreamInfo()
        override fun setCaptionsEnabled(enabled: Boolean) = Unit
        override fun setStream(stream: PlaybackStream, nowPlaying: NowPlaying?) = Unit
        override fun replaceStream(stream: PlaybackStream) = Unit
        override fun prepare() = Unit
        override fun stop() = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle = object : MediaSessionHandle {
            override fun publish(nowPlaying: NowPlaying) = Unit
            override fun close() = Unit
        }

        @Composable
        override fun Video(modifier: Modifier, zoom: VideoZoom, shutter: @Composable () -> Unit) = Box(modifier)

        override fun release() = Unit
    }

    /** Where the cue's text starts on a 1920 by 1080 screen. */
    private fun cueLeft(size: IntSize?, zoom: VideoZoom): Dp {
        var left = Dp.Unspecified
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(1f)) {
            setContent {
                Captions(CuePlayer(size), CaptionStyle.SYSTEM, zoom, 0f, Modifier.fillMaxSize())
            }
            left = onNodeWithText("Left column", substring = true).getBoundsInRoot().left
        }
        return left
    }

    @Test
    fun `a 4 by 3 channel's left column starts at the picture's edge, inside the pillar`() {
        assertEquals(240.dp, cueLeft(IntSize(640, 480), VideoZoom.FIT))
    }

    @Test
    fun `a 16 by 9 picture or a filled one puts the left column at the screen's edge`() {
        assertEquals(0.dp, cueLeft(IntSize(1920, 1080), VideoZoom.FIT))
        assertEquals(0.dp, cueLeft(IntSize(640, 480), VideoZoom.FILL))
    }
}
