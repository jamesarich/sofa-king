package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.LyricLine
import dev.jamesrich.sofaking.data.Lyrics
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MusicQueue
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LastInput
import dev.jamesrich.sofaking.ui.LayoutProfile
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test

/**
 * Lyrics on Now playing on a TV (#454): the button sits among the song's buttons only where the server has lyrics for
 * the song, and OK on it shows the lines in the cover's place and OK again gives the cover back.
 */
@OptIn(ExperimentalTestApi::class)
class NowPlayingLyricsTest {
    private val song = MediaItem(
        "s1",
        "music",
        ItemKind.TRACK,
        "Porch Light",
        parentId = "a1",
        artist = "The Hollow Pines",
        album = "Slow Burn",
    )
    private val lyrics = Lyrics(listOf(LyricLine(null, "Porch light on"), LyricLine(null, "Waiting for the sun")))

    private fun ComposeUiTest.show(lyrics: Lyrics?): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val music = MusicPlayback(UnavailableLivePlayer(CaptionSync(), "TEST"), HoldingSongSessions, scope)
        music.play(MusicQueue.of(listOf(song))!!)
        setContent {
            CompositionLocalProvider(
                LocalLayoutProfile provides LayoutProfile.Tv,
                LocalLastInput provides LastInput(InputKind.KEYS),
            ) {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        NowPlayingScreen(music, onClose = {}, loadLyrics = { lyrics })
                    }
                }
            }
        }
        waitForIdle()
        return scope
    }

    @Test
    fun `the Lyrics button swaps the cover for the lines and back`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val scope = show(lyrics)
            onAllNodesWithText("Porch light on").assertCountEquals(0)
            onNodeWithContentDescription("Lyrics").assertIsOff().performClick()
            onNodeWithContentDescription("Lyrics").assertIsOn()
            onAllNodesWithText("Porch light on").assertCountEquals(1)
            onAllNodesWithText("Waiting for the sun").assertCountEquals(1)
            onNodeWithContentDescription("Lyrics").performClick()
            onNodeWithContentDescription("Lyrics").assertIsOff()
            onAllNodesWithText("Porch light on").assertCountEquals(0)
            scope.cancel()
        }

    @Test
    fun `a song with no lyrics has no Lyrics button`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        val scope = show(null)
        onAllNodesWithContentDescription("Favorite").assertCountEquals(1)
        onAllNodesWithContentDescription("Lyrics").assertCountEquals(0)
        scope.cancel()
    }
}

/** Sessions that never answer, so a song stays open on its first moment. */
private object HoldingSongSessions : SongSessions {
    override val current = MutableStateFlow<PlaybackStream?>(null)
    override val replaced = emptyFlow<PlaybackStream>()

    override suspend fun play(itemId: String, profile: PlaybackProfile, startMs: Long): PlaybackStream =
        awaitCancellation()

    override fun release(itemId: String) = Unit
}
