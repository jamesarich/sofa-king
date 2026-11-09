package dev.jamesrich.sofaking.backend.virtual

import dev.jamesrich.sofaking.backend.library.VideoEncoder
import dev.jamesrich.sofaking.backend.upstream.FFMPEG_LOCAL_PROTOCOL_WHITELIST
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class VirtualCommandsTest {
    private fun command(input: PieceInput) =
        pieceCommand("ffmpeg", input, Path.of("/tmp/out"), VideoEncoder(), timestampMs = 0, burstSeconds = 10)

    /** The whitelist arguments, `-i` and the file after it. */
    private fun List<String>.inputWithWhitelist(): List<String> {
        val i = indexOf("-i")
        return subList(i - FFMPEG_LOCAL_PROTOCOL_WHITELIST.size, i + 2)
    }

    @Test fun `a library file is opened with only the file and pipe protocols`() {
        val input = PieceInput(Path.of("/media/movie.mkv"), media = null, offsetMs = 5_000, durationMs = 60_000)
        assertEquals(
            FFMPEG_LOCAL_PROTOCOL_WHITELIST + listOf("-i", "/media/movie.mkv"),
            command(input).inputWithWhitelist(),
        )
    }

    @Test fun `a slide image is opened with only the file and pipe protocols`() {
        val input = PieceInput(Path.of("/slides/a.png"), media = null, offsetMs = 0, durationMs = 8_000, still = true)
        assertEquals(
            FFMPEG_LOCAL_PROTOCOL_WHITELIST + listOf("-i", "/slides/a.png"),
            command(input).inputWithWhitelist(),
        )
    }
}
