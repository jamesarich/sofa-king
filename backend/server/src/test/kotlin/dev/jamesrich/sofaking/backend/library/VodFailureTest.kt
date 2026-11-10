package dev.jamesrich.sofaking.backend.library

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.pollUntil
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.ClientProfile
import dev.jamesrich.sofaking.contract.OpenVodPlayback
import dev.jamesrich.sofaking.contract.UpdateVodPlayback
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VodFailureTest {
    private val readable = MediaInfo("matroska,webm", 8_000, video = VideoStream("h264", width = 320, height = 180))

    @Test
    fun `a conversion that dies before its first segment logs once and fails the session for renewals and media`() =
        runBlocking {
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            val events = ListAppender<ILoggingEvent>().apply { start() }
            val logger = context.getLogger(VodSessions::class.java)
            logger.addAppender(events)
            val dir = Files.createTempDirectory("sofa-vod-dies-")
            val store = openStore(dir)
            val broken = dir.resolve("recordings/Small Prophets.mkv")
            Files.createDirectories(broken.parent)
            Files.write(broken, ByteArray(1_048_576))
            val vod = VodSessions(
                LibraryCatalog(store, MutableClock()) { 0L },
                MutableClock(),
                "ffmpeg",
                dir.resolve("transcode"),
                recordings = { PlayableFile(broken, 8_000, growing = false) },
                probe = { readable },
            )
            try {
                val session = vod.open(OpenVodPlayback(profile = ClientProfile(), recordingId = "rec-1"))
                assertEquals(Wire.VodMode.TRANSCODE, session.mode)
                pollUntil(20_000) {
                    events.list.any { it.level.toString() == "WARN" && "first segment" in it.formattedMessage }
                }
                val line = events.list.single { "first segment" in it.formattedMessage }.formattedMessage
                assertTrue("session=${session.id}" in line, line)
                assertTrue("item=rec-1" in line, line)
                assertTrue("exit=" in line, line)
                assertFalse(broken.toString() in line, "the file's path stays out of the log: $line")
                val update = assertFailsWith<ApiFailure> { vod.update(session.id, UpdateVodPlayback()) }
                assertEquals(HttpStatusCode.UnprocessableEntity, update.status)
                assertEquals(Wire.ErrorCode.MEDIA_UNREADABLE, update.code)
                val parts = session.descriptor.url.removePrefix("/media/vod/").split('/')
                val media = assertFailsWith<ApiFailure> {
                    vod.files.media(parts[0], parts[1].toInt(), parts[2], "index.m3u8")
                }
                assertEquals(Wire.ErrorCode.MEDIA_UNREADABLE, media.code)
                assertEquals(
                    1,
                    events.list.count { "first segment" in it.formattedMessage },
                    "one warning per session, however often it is asked",
                )
            } finally {
                logger.detachAppender(events)
                vod.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
}
