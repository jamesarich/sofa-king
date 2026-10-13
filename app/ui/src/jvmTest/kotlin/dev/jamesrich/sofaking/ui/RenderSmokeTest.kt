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

/** A window to lay the screens out in, in dp at [density]. */
enum class ScreenSize(val label: String, val widthDp: Int, val heightDp: Int, val density: Float) {
    TV("tv", 960, 540, 2f),
    PHONE_LANDSCAPE("phone-landscape", 800, 360, 3f),
    PHONE_PORTRAIT("phone-portrait", 360, 800, 3f),
    TABLET_PORTRAIT("tablet-portrait", 800, 1280, 2f),
    DESKTOP_NARROW("desktop-narrow", 560, 720, 1f),
}

/**
 * Renders the main screens headless on the JVM through Skia at each [ScreenSize], from a 1080p TV (960x540 dp at 2x)
 * to a phone held either way, and writes each to build/screenshots/<size> for review. Fails when a screen throws or draws nothing but the background.
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
    private fun render(name: String, size: ScreenSize = ScreenSize.TV, content: @Composable () -> Unit): Image = runBlocking {
        val image = ImageComposeScene(
            width = (size.widthDp * size.density).toInt(),
            height = (size.heightDp * size.density).toInt(),
            density = Density(size.density),
            coroutineContext = coroutineContext.minusKey(Job),
        ) {
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
        val out = File("build/screenshots/${size.label}/$name.png")
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
        for (size in ScreenSize.entries) {
        val image = render("guide", size) {
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
        assertTrue(image.hasContent(), "$size")
        }
    }

    @Test
    fun `settings renders its fields and switches`() {
        for (size in ScreenSize.entries) {
        val image = render("settings", size) {
            SettingsScreen(
                settings = AppSettings(serverUrl = "http://sofa.lan:9193", apiToken = "s3cret-token", captionsEnabled = true),
                onSave = { _, _ -> },
                onBack = {},
                channelCount = channels.size,
            )
        }
        assertTrue(image.hasContent(), "$size")
        }
    }

    @Test
    fun `the player's info bar renders over the picture`() {
        for (size in ScreenSize.entries) {
        val image = render("player-info", size) {
            Box(Modifier.fillMaxSize()) {
                InfoOverlay(lineup = lineup, channel = channels[0], now = now, captionsOn = true, captionAdvanceMs = 1_500, scope = "News 1/4")
            }
        }
        assertTrue(image.hasContent(), "$size")
        }
    }
}
