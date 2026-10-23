package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.openStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleExtractionTest {
    private fun Path.file(relative: String, text: String = "x"): Path = resolve(relative).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    @Test fun `a slow extraction holds up no other item's, and one given up on leaves no partial file`() = runBlocking {
        val dir = Files.createTempDirectory("sofa-subtitles-")
        try {
            val clock = MutableClock()
            val store = openStore(dir.resolve("data"), clock)
            val root = Files.createDirectories(dir.resolve("media"))
            val movies = Files.createDirectories(root.resolve("movies"))
            movies.file("Slow (2001)/Slow (2001).mkv")
            movies.file("Slow (2001)/Slow (2001).en.srt", "1\n00:00:00,000 --> 00:00:01,000\nHi\n")
            movies.file("Fast (2002)/Fast (2002).mkv")
            movies.file("Fast (2002)/Fast (2002).en.srt", "1\n00:00:00,000 --> 00:00:01,000\nHi\n")
            val media =
                MediaInfo(
                    "matroska,webm",
                    durationMs = 60_000,
                    video = VideoStream("h264", width = 1920, height = 1080),
                )
            val service =
                LibraryService(
                    store,
                    clock,
                    listOf(MediaRoot("Media", root)),
                    LibraryScanner({
                        media
                    }, missingGrace = Duration.ofDays(7)),
                    Jobs(clock),
                )
            val library = service.create("Movies", LibraryKind.MOVIES, movies.toString())
            service.scan(library.id)
            val ids = store.libraryItems(library.id).associate { it.title to it.id }
            // An FFmpeg that writes part of the slow file's track and then hangs, and converts the fast one at once.
            val ffmpeg = dir.file(
                "ffmpeg",
                "#!/bin/sh\nfor out; do :; done\ncase \"$*\" in\n  *Slow*) echo partial > \"\$out\"; exec sleep 60 ;;\n  *) printf 'WEBVTT\\n\\n00:00.000 --> 00:01.000\\nHi\\n' > \"\$out\" ;;\nesac\n",
            ).also { it.toFile().setExecutable(true) }
            val transcode = dir.resolve("transcode")
            val vod = VodSessions(service.catalog, clock, ffmpeg.toString(), transcode)
            val subtitles = transcode.resolve("subtitles")
            fun parts() = if (Files.isDirectory(subtitles)) subtitles.listDirectoryEntries("*.part") else emptyList()

            val slow = async(Dispatchers.Default) { vod.subtitles(ids.getValue("Slow"), SIDECAR_BASE) }
            withTimeout(10_000) { while (parts().isEmpty()) delay(20) }
            val fast = withTimeout(10_000) { vod.subtitles(ids.getValue("Fast"), SIDECAR_BASE) }
            assertTrue(Files.readString(fast).startsWith("WEBVTT"))

            slow.cancelAndJoin()
            assertEquals(emptyList(), parts())
            store.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
