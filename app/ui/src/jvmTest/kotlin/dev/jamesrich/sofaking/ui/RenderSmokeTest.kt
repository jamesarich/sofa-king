package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.GuideStatus
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.ui.guide.GuideScreen
import dev.jamesrich.sofaking.ui.player.CaptionSync
import dev.jamesrich.sofaking.ui.player.InfoOverlay
import dev.jamesrich.sofaking.ui.player.UnavailableLivePlayer
import dev.jamesrich.sofaking.ui.settings.SettingsScreen
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Renders the main screens headless on the JVM through Skia, as a 1080p TV lays them out (960x540 dp at 2x), and
 * writes each to build/screenshots for review. Fails when a screen throws or draws nothing but the background.
 */
class RenderSmokeTest {
    private val now = Clock.System.now()
    private val start = now - 20.minutes
    // A loopback server stands in for the server's /logos/ route; channels without a logo keep an empty slot.
    private val logo = run {
        val png = java.io.File("../androidApp/src/main/res/drawable-xhdpi/banner.png").readBytes()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/logos/banner") { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        server.start()
        "http://127.0.0.1:${server.address.port}/logos/banner"
    }
    private val channels = List(8) { i ->
        Channel(
            guideId = "c$i",
            number = (i + 2).toDouble(),
            name = listOf("News Now", "Sofa Sports", "Movies", "Kids", "Local 4", "Weather", "Music", "Classics")[i],
            logo = logo.takeIf { i % 3 != 2 },
            groups = listOf(if (i % 2 == 0) "News" else "Sports"),
            guideStatus = when (i) {
                2 -> GuideStatus.UNASSIGNED
                3 -> GuideStatus.STALE
                else -> GuideStatus.AVAILABLE
            },
        )
    }
    private val lineup = Lineup(
        channels = channels,
        guide = channels.filter { it.guideStatus == GuideStatus.AVAILABLE }.associate { channel ->
            channel.guideId to List(6) { slot ->
                Programme(
                    channelId = channel.guideId,
                    start = start + (slot * 45).minutes,
                    stop = start + ((slot + 1) * 45).minutes,
                    title = "${channel.name} show ${slot + 1}",
                    description = "Episode notes for slot ${slot + 1}.",
                    subTitle = "Part ${slot + 1}".takeIf { slot % 2 == 0 },
                    episode = "S1 E${slot + 1}".takeIf { slot % 2 == 0 },
                    isNew = slot == 0,
                    rating = "TV-PG".takeIf { slot % 2 == 0 },
                    icon = logo.takeIf { slot == 0 },
                )
            }
        },
    )

    /** Runs the scene on this thread's event loop, so effects resume where the frames draw; each frame yields to them. */
    private fun render(name: String, content: @Composable () -> Unit): Image = runBlocking {
        val image = ImageComposeScene(width = 1920, height = 1080, density = Density(2f), coroutineContext = coroutineContext.minusKey(Job)) {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }.use { scene ->
            // Resources load and effects settle over a few frames.
            var frame = 0L
            repeat(40) {
                scene.render(frame)
                frame += 16_000_000L
                delay(16)
            }
            scene.render(frame)
        }
        val out = File("build/screenshots/$name.png")
        out.parentFile.mkdirs()
        out.writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
        image
    }

    /** True when the image has pixels other than the top-left one's color, i.e. something was drawn on the background. */
    private fun Image.hasContent(): Boolean {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(this)
        val background = bitmap.getColor(0, 0)
        for (y in 0 until height step 8) for (x in 0 until width step 8) if (bitmap.getColor(x, y) != background) return true
        return false
    }

    @Test
    fun `the guide renders its grid and preview band`() {
        val image = render("guide") {
            GuideScreen(
                lineup = lineup,
                favorites = setOf("c1"),
                initialChannelId = "c2",
                startAtNow = false,
                startSearch = false,
                returnedFrom = null,
                onPlay = { _, _ -> },
                onToggleFavorite = {},
                previewPlayer = UnavailableLivePlayer(CaptionSync(), "TEST"),
                sessions = PlaybackSessions(),
                captionsEnabled = false,
                captionStyle = CaptionStyle.SYSTEM,
                autoOpenDelay = AutoOpenDelay.OFF,
                previewSound = false,
            )
        }
        assertTrue(image.hasContent())
    }

    @Test
    fun `settings renders its fields and switches`() {
        val image = render("settings") {
            SettingsScreen(
                settings = AppSettings(serverUrl = "http://sofa.lan:9193", apiToken = "s3cret-token", captionsEnabled = true),
                onSave = { _, _ -> },
                onBack = {},
                channelCount = channels.size,
            )
        }
        assertTrue(image.hasContent())
    }

    @Test
    fun `the player's info bar renders over the picture`() {
        val image = render("player-info") {
            Box(Modifier.fillMaxSize()) {
                InfoOverlay(lineup = lineup, channel = channels[0], now = now, captionsOn = true, captionAdvanceMs = 1_500, scope = "News 1/4")
            }
        }
        assertTrue(image.hasContent())
    }

    @Test
    fun `a long channel name keeps the info bar at the bottom`() {
        val longChannel = channels[0].copy(number = 1414.0, name = "The Curse of Oak Island")
        val image = render("player-info-long") {
            Box(Modifier.fillMaxSize()) {
                InfoOverlay(
                    lineup = lineup.copy(channels = listOf(longChannel) + channels.drop(1)),
                    channel = longChannel,
                    now = now,
                    captionsOn = true,
                    captionAdvanceMs = 1_500,
                    scope = "Favorites 3/10",
                )
            }
        }
        // A long name once squeezed the captions badge until it wrapped a letter a line and stretched the bar up the screen.
        assertTrue(image.firstContentRow() > image.height / 3)
    }

    private fun Image.firstContentRow(): Int {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(this)
        val background = bitmap.getColor(0, 0)
        for (y in 0 until height) for (x in 0 until width step 4) if (bitmap.getColor(x, y) != background) return y
        return height
    }
}
