package dev.jamesrich.sofaking.backend.library

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryArtworkFrameTest {
    @Test fun `an MP3's embedded cover is read as its first frame`() = runBlocking {
        val dir = Files.createTempDirectory("sofa-frame-")
        val song = dir.resolve("song.mp3")
        val cover = dir.resolve("cover.jpg")
        ffmpeg("-f", "lavfi", "-i", "color=c=red:s=200x200:d=1", "-frames:v", "1", cover.toString())
        ffmpeg(
            "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-i", cover.toString(), "-map", "0:a", "-map", "1:v",
            "-c:a", "libmp3lame", "-c:v", "copy", "-id3v2_version", "3", song.toString(),
        )
        val artwork = LibraryArtwork(
            HttpClient(MockEngine { respond("") }),
            dir.resolve("art"),
            ffmpeg = "ffmpeg",
        )
        val frame = artwork.image(ArtSource.Frame(dir, "song.mp3", 0, "1"))
        assertNotNull(frame)
        assertEquals("image/jpeg", frame.contentType)
        assertTrue(Files.size(frame.file) > 0)
    }

    private fun ffmpeg(vararg args: String) {
        val process = ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y") + args)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }
}
