package dev.jamesrich.sofaking.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.jamesrich.sofaking.ItemDetailState
import dev.jamesrich.sofaking.LibraryGrid
import dev.jamesrich.sofaking.LibraryHomeState
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaHome
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.MediaLibrary
import dev.jamesrich.sofaking.data.MediaShelf
import dev.jamesrich.sofaking.data.PlayProgress
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.GuideStatus
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.ui.guide.GuideScreen
import dev.jamesrich.sofaking.ui.guide.ProgrammeDetails
import dev.jamesrich.sofaking.ui.guide.ProgrammeSheet
import dev.jamesrich.sofaking.ui.player.CaptionSync
import dev.jamesrich.sofaking.ui.player.InfoBar
import dev.jamesrich.sofaking.ui.player.InfoOverlay
import dev.jamesrich.sofaking.ui.player.MiniGuide
import dev.jamesrich.sofaking.ui.player.MiniGuideActions
import dev.jamesrich.sofaking.ui.player.UnavailableLivePlayer
import dev.jamesrich.sofaking.ui.library.ItemDetailsActions
import dev.jamesrich.sofaking.ui.library.ItemDetailsScreen
import dev.jamesrich.sofaking.ui.library.LibraryGridActions
import dev.jamesrich.sofaking.ui.library.LibraryGridScreen
import dev.jamesrich.sofaking.ui.library.LibraryHomeActions
import dev.jamesrich.sofaking.ui.library.LibraryHomeScreen
import dev.jamesrich.sofaking.ui.settings.SettingsLockScreen
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
                recentChannels = listOf("c2"),
                initialChannelId = "c2",
                initialFilterKey = null,
                startAtNow = false,
                startSearch = false,
                returnedFrom = null,
                onPlay = { _, _ -> },
                onToggleFavorite = {},
                onFilterChanged = {},
                onLoadGuideWindow = {},
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
    fun `the program details panel fits its buttons and description`() {
        val programme = lineup.guide.getValue("c0").first().copy(
            title = "Sailor Moon",
            description = List(12) { "Usagi and the girls are excited to attend the school festival." }.joinToString(" "),
        )
        val image = render("guide-details") {
            // The guide sits under the app's header, so the panel gets the height below it.
            Box(Modifier.fillMaxSize().padding(top = 75.dp)) {
                ProgrammeSheet(
                    details = ProgrammeDetails(channels[0], programme),
                    now = now,
                    isFavorite = true,
                    guideLoading = false,
                    onWatch = {},
                    onToggleFavorite = {},
                    onDismiss = {},
                )
            }
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
    fun `settings with no server renders the open server fields`() {
        val image = render("settings-unconfigured") {
            SettingsScreen(settings = AppSettings(), onSave = { _, _ -> }, onBack = {}, channelCount = 0)
        }
        assertTrue(image.hasContent())
    }

    @Test
    fun `the settings lock page renders its field and buttons`() {
        val image = render("settings-lock") {
            SettingsLockScreen(pinHash = "salt:hash", apiToken = "s3cret-token", onUnlocked = {}, onPinRemoved = {}, onBack = {})
        }
        assertTrue(image.hasContent())
    }

    @Test
    fun `the player's info bar renders over the picture`() {
        val image = render("player-info") {
            Box(Modifier.fillMaxSize()) {
                InfoOverlay(
                    lineup = lineup,
                    channel = channels[0],
                    now = now,
                    captionsOn = true,
                    captionAdvanceMs = 1_500,
                    scope = "News 1/4",
                    showKeyLegend = true,
                    modifier = Modifier.align(Alignment.BottomStart).screenPadding(),
                )
            }
        }
        assertTrue(image.hasContent())
    }

    @Test
    fun `the channel list renders its chips, action buttons and rows`() {
        val image = render("player-channels") {
            Box(Modifier.fillMaxSize()) {
                MiniGuide(
                    lineup = lineup,
                    scope = null,
                    favorites = setOf("c1", "c3"),
                    recent = listOf("c4", "c1"),
                    currentGuideId = "c1",
                    previousGuideId = "c4",
                    now = now,
                    onTune = {},
                    actions = MiniGuideActions(
                        captionsOn = true,
                        paused = false,
                        failed = false,
                        hasAudioChoice = true,
                        favorite = true,
                        onToggleFavorite = {},
                        onCaptions = {},
                        onPlayPause = {},
                        onRetry = {},
                        onStreamInfo = {},
                    ),
                )
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
                    modifier = Modifier.align(Alignment.BottomStart).screenPadding(),
                )
            }
        }
        // A long name once squeezed the captions badge until it wrapped a letter a line and stretched the bar up the screen.
        assertTrue(image.firstContentRow() > image.height / 3)
    }

    @Test
    fun `the player's info bar sits at the bottom of the screen`() {
        val image = render("player-info-bar") {
            Box(Modifier.fillMaxSize()) {
                InfoBar(visible = true) {
                    InfoOverlay(lineup = lineup, channel = channels[0], now = now, captionsOn = false, captionAdvanceMs = null, scope = null)
                }
            }
        }
        // An align() inside AnimatedVisibility's content once left the bar at the top of the screen.
        assertTrue(image.firstContentRow() > image.height / 3)
    }

    private val movies = MediaLibrary("movies", "Movies", LibraryKind.MOVIES, itemCount = 42)
    private val shows = MediaLibrary("shows", "TV Shows", LibraryKind.SHOWS, itemCount = 12)
    private fun movie(i: Int) = MediaItem(
        id = "m$i",
        libraryId = "movies",
        kind = ItemKind.MOVIE,
        title = listOf("The Long Night", "Harbor Lights", "Paper Moon", "Quiet Town", "Northbound", "Second Act", "Glass Garden", "Dust")[i % 8],
        year = 1990 + i,
        durationMs = 6_480_000,
        poster = logo.takeIf { i % 3 != 1 },
        backdrop = logo,
        overview = "A night shift at a lighthouse turns into a search for a missing ship. ".repeat(3),
        genres = listOf("Drama", "Mystery"),
        contentRating = "PG-13",
        rating = 7.4,
        videoHeight = 2160,
        hdr = i == 0,
        progress = when (i) {
            0 -> PlayProgress(1_800_000, 6_480_000, watched = false)
            2 -> PlayProgress(0, 6_480_000, watched = true)
            else -> null
        },
    )
    private fun episode(season: Int, i: Int, progress: PlayProgress? = null) = MediaItem(
        id = "e$season-$i",
        libraryId = "shows",
        kind = ItemKind.EPISODE,
        title = "Chapter ${i + 1}",
        parentId = "s$season",
        showId = "show",
        showTitle = "Harbor Patrol",
        season = season,
        episode = i + 1,
        aired = "2024-0${season}-1${i}",
        durationMs = 2_700_000,
        backdrop = logo.takeIf { i % 2 == 0 },
        overview = "The crew follows a lead across the bay while a storm closes in on the harbor.",
        progress = progress,
    )
    private val show = MediaItem(
        id = "show",
        libraryId = "shows",
        kind = ItemKind.SHOW,
        title = "Harbor Patrol",
        year = 2023,
        poster = logo,
        backdrop = logo,
        overview = "Coast guard officers keep a small harbor town safe through storms, smugglers and summer crowds.",
        genres = listOf("Drama"),
        contentRating = "TV-14",
        rating = 8.1,
        children = 2,
        unwatched = 9,
    )

    @Test
    fun `the library home renders its rows and libraries`() {
        val home = MediaHome(
            shelves = listOf(
                MediaShelf("continue", "Continue watching", listOf(movie(0), episode(1, 2, PlayProgress(900_000, 2_700_000, false)))),
                MediaShelf("recent-movies", "Recently added in Movies", List(8) { movie(it) }, libraryId = "movies"),
                MediaShelf("recent-shows", "Recently added in TV Shows", listOf(show), libraryId = "shows"),
            ),
            libraries = listOf(movies, shows),
        )
        val image = render("library-home") {
            LibraryHomeScreen(LibraryHomeState.Ready(home), LibraryHomeActions({}, {}, {}, {}))
        }
        assertTrue(image.hasContent())
    }

    @Test
    fun `a movie's page renders its hero, details and buttons`() {
        val detail = MediaItemDetail(
            item = movie(0),
            tagline = "Some lights never go out.",
            cast = listOf("Ana Reyes", "Tom Hale", "June Park"),
            directors = listOf("Lee Moran"),
        )
        val image = render("library-movie") {
            ItemDetailsScreen(ItemDetailState.Ready(detail), season = null, selectedSeasonId = null, actions = ItemDetailsActions({ _, _ -> }, { _, _ -> }, {}, {}))
        }
        assertTrue(image.hasContent())
    }

    @Test
    fun `a show's page renders its seasons and episodes`() {
        val seasons = List(2) { MediaItem("s${it + 1}", "shows", ItemKind.SEASON, "Season ${it + 1}", parentId = "show", season = it + 1, unwatched = 4) }
        val next = episode(2, 1, PlayProgress(600_000, 2_700_000, false))
        val detail = MediaItemDetail(item = show, children = seasons, next = next)
        val season = MediaItemDetail(
            item = seasons[1],
            children = listOf(episode(2, 0, PlayProgress(0, 2_700_000, true)), next) + List(3) { episode(2, it + 2) },
        )
        val image = render("library-show") {
            ItemDetailsScreen(ItemDetailState.Ready(detail), ItemDetailState.Ready(season), selectedSeasonId = "s2", actions = ItemDetailsActions({ _, _ -> }, { _, _ -> }, {}, {}))
        }
        assertTrue(image.hasContent())
    }

    @Test
    fun `a library's grid renders its chips and posters`() {
        val grid = LibraryGrid("movies", LibrarySort.ADDED, unwatched = false, items = List(14) { movie(it) }, genres = listOf("Action", "Comedy", "Drama"), total = 42, next = "c2", loading = false)
        val image = render("library-grid") {
            LibraryGridScreen(movies, grid, LibraryGridActions({}, {}, {}, {}, {}, {}))
        }
        assertTrue(image.hasContent())
    }

    private fun Image.firstContentRow(): Int {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(this)
        val background = bitmap.getColor(0, 0)
        for (y in 0 until height) for (x in 0 until width step 4) if (bitmap.getColor(x, y) != background) return y
        return height
    }
}
