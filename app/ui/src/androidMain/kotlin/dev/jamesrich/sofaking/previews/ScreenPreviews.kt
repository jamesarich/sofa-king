package dev.jamesrich.sofaking.previews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.ItemDetailState
import dev.jamesrich.sofaking.LibraryHomeState
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.Appearance
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.ClockFormat
import dev.jamesrich.sofaking.data.GuideStatus
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LyricLine
import dev.jamesrich.sofaking.data.Lyrics
import dev.jamesrich.sofaking.data.MediaHome
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.MediaLibrary
import dev.jamesrich.sofaking.data.MediaShelf
import dev.jamesrich.sofaking.data.MusicQueue
import dev.jamesrich.sofaking.data.PlayProgress
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LastInput
import dev.jamesrich.sofaking.ui.LayoutProfile
import dev.jamesrich.sofaking.ui.LocalClock
import dev.jamesrich.sofaking.ui.LocalClockFormat
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.LocalTimeZone
import dev.jamesrich.sofaking.ui.guide.GuideActions
import dev.jamesrich.sofaking.ui.guide.GuideArrival
import dev.jamesrich.sofaking.ui.guide.GuidePreview
import dev.jamesrich.sofaking.ui.guide.GuideScreen
import dev.jamesrich.sofaking.ui.guide.ProgramDetails
import dev.jamesrich.sofaking.ui.guide.ProgramSheet
import dev.jamesrich.sofaking.ui.library.ItemDetailsActions
import dev.jamesrich.sofaking.ui.library.ItemDetailsScreen
import dev.jamesrich.sofaking.ui.library.LibraryHomeActions
import dev.jamesrich.sofaking.ui.library.LibraryHomeScreen
import dev.jamesrich.sofaking.ui.player.CaptionSync
import dev.jamesrich.sofaking.ui.player.InfoBar
import dev.jamesrich.sofaking.ui.player.InfoOverlay
import dev.jamesrich.sofaking.ui.player.MusicPlayback
import dev.jamesrich.sofaking.ui.player.NowPlayingScreen
import dev.jamesrich.sofaking.ui.player.PlayerButtonItem
import dev.jamesrich.sofaking.ui.player.PlayerButtonRow
import dev.jamesrich.sofaking.ui.player.PlayerFocus
import dev.jamesrich.sofaking.ui.player.SleepTimer
import dev.jamesrich.sofaking.ui.player.SongSessions
import dev.jamesrich.sofaking.ui.player.UnavailableLivePlayer
import dev.jamesrich.sofaking.ui.player.VideoZoom
import dev.jamesrich.sofaking.ui.player.VodButton
import dev.jamesrich.sofaking.ui.player.VodControls
import dev.jamesrich.sofaking.ui.player.VodEnding
import dev.jamesrich.sofaking.ui.player.VodInfoBar
import dev.jamesrich.sofaking.ui.player.VodParts
import dev.jamesrich.sofaking.ui.player.VodPlayerState
import dev.jamesrich.sofaking.ui.player.VodSession
import dev.jamesrich.sofaking.ui.player.VodTitle
import dev.jamesrich.sofaking.ui.player.VodToasts
import dev.jamesrich.sofaking.ui.player.VodTrackPicks
import dev.jamesrich.sofaking.ui.player.captionsButtonItem
import dev.jamesrich.sofaking.ui.player.playPauseButtonItem
import dev.jamesrich.sofaking.ui.player.sleepButtonItem
import dev.jamesrich.sofaking.ui.player.streamInfoButtonItem
import dev.jamesrich.sofaking.ui.player.vodButtonItem
import dev.jamesrich.sofaking.ui.player.zoomButtonItem
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.settings.SettingsScreen
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/*
 * The main screens filled with sample data, for androidApp's screenshot tests, which render them as Android Studio
 * renders a preview. Each takes [tv] for the TV layout; otherwise the window's size class picks the phone, tablet or
 * desktop one. The clock stands still at one evening in UTC with a 12-hour clock, so the pictures do not change
 * with the day, the machine's zone or its settings.
 */

/** The guide, with its preview band where the layout has room for it. */
@Composable
fun GuidePreview(tv: Boolean) = Frame(tv) {
    GuideScreen(
        lineup = Samples.lineup,
        favorites = setOf("c1"),
        recentChannels = listOf("c2"),
        arrival = GuideArrival(initialChannelId = "c1"),
        actions = GuideActions(onPlay = { _, _, _ ->
        }, onToggleFavorite = {}, onFilterChange = {}, onLoadGuideWindow = {}),
        preview = GuidePreview(
            player = remember { UnavailableLivePlayer(CaptionSync(), "PREVIEW") },
            sessions = remember { PlaybackSessions() },
            captionsEnabled = false,
            captionStyle = CaptionStyle.SYSTEM,
            autoOpenDelay = AutoOpenDelay.OFF,
            sound = false,
        ),
    )
}

/** A program's details: a side panel on a TV, a sheet elsewhere. */
@Composable
fun ProgramDetailsPreview(tv: Boolean) = Frame(tv) {
    ProgramSheet(
        details = ProgramDetails(Samples.channels[0], Samples.lineup.guide.getValue("c0").first()),
        now = Samples.now,
        isFavorite = true,
        guideLoading = false,
        onWatch = {},
        onToggleFavorite = {},
        onDismiss = {},
    )
}

/** The full-screen player's info bar over the picture. */
@Composable
fun PlayerInfoPreview(tv: Boolean, modifier: Modifier = Modifier) = Frame(tv) {
    Box(modifier.fillMaxSize()) {
        InfoOverlay(
            lineup = Samples.lineup,
            channel = Samples.channels[0],
            now = Samples.now,
            captionsOn = true,
            captionAdvanceMs = 1_500,
            scope = "News 1/4",
            showKeyLegend = true,
            modifier = Modifier.align(Alignment.BottomStart).screenPadding(),
        )
    }
}

/** The live player's info bar: the program on now above the button row. */
@Composable
fun LiveBarPreview(tv: Boolean, modifier: Modifier = Modifier) = Frame(tv) {
    val entry = remember { FocusRequester() }
    val sleep = remember { SleepTimer() }
    val items = listOf(
        PlayerButtonItem(Icons.AutoMirrored.Filled.List, "Channels", {}),
        playPauseButtonItem(paused = false, failed = false, onPlayPause = {}, onRetry = {}),
        captionsButtonItem(captionsOn = true, onClick = {}),
        streamInfoButtonItem(hasAudioChoice = true, onClick = {}),
        zoomButtonItem(VideoZoom.FIT, {}),
        sleepButtonItem(sleep, {}),
    )
    Box(modifier.fillMaxSize()) {
        InfoBar(visible = true) {
            InfoOverlay(
                lineup = Samples.lineup,
                channel = Samples.channels[0],
                now = Samples.now,
                captionsOn = true,
                captionAdvanceMs = 1_500,
                scope = "News 1/4",
            )
            PlayerButtonRow(items, entry, onFocus = {}, showLabel = tv)
        }
    }
}

/** The library and recordings player's info bar: title, seek bar, times and the button row. */
@Composable
fun VodBarPreview(tv: Boolean, modifier: Modifier = Modifier) = Frame(tv) {
    val scope = rememberCoroutineScope()
    val parts = remember {
        val vod = VodSession(
            VodPlayerState(),
            UnavailableLivePlayer(CaptionSync(), "PREVIEW"),
            PlaybackSessions(),
            "m0",
            scope,
        )
        vod.state.positionMs = 1_800_000
        vod.state.durationMs = 6_480_000
        val toasts = VodToasts()
        VodParts(vod, VodTrackPicks(null, null, vod, toasts), toasts, VodEnding(), PlayerFocus())
    }
    val sleep = remember { SleepTimer() }
    val buttons = listOf(
        VodButton.CAPTIONS,
        VodButton.ZOOM,
        VodButton.SLEEP,
        VodButton.SPEED,
        VodButton.START_OVER,
        VodButton.NEXT,
    ).map {
        vodButtonItem(it, captionsOn = true, VideoZoom.FIT, null, false, sleep, speed = 1f, onClick = {})
    }
    val controls = remember {
        object : VodControls {
            override fun seek(targetMs: Long) = Unit
            override fun playPause() = Unit
            override fun retry() = Unit
            override fun toggleCaptions() = Unit
            override fun zoom() = Unit
            override fun focusButtons() = Unit
            override fun focusPicture() = Unit
            override fun playNext() = Unit
            override fun exit() = Unit
        }
    }
    val input = remember { LastInput(if (tv) InputKind.KEYS else InputKind.POINTER) }
    CompositionLocalProvider(LocalLastInput provides input) {
        Box(modifier.fillMaxSize()) {
            VodInfoBar(parts, VodTitle("The Long Night", "Harbor Patrol"), Wire.VodMode.DIRECT, true, buttons, controls)
        }
    }
}

/**
 * The Library tab: Continue watching, recent additions and the libraries, or while it [loading], the spotlight's frame
 * and placeholder rows at card size.
 */
@Composable
fun LibraryHomePreview(tv: Boolean, light: Boolean = false, loading: Boolean = false) = Frame(tv, light) {
    val state = if (loading) LibraryHomeState.Loading else LibraryHomeState.Ready(Samples.home)
    LibraryHomeScreen(state, LibraryHomeActions({}, {}, {}, {}))
}

/** A show's page on the season with the next episode. */
@Composable
fun ShowPagePreview(tv: Boolean) = Frame(tv) {
    ItemDetailsScreen(
        ItemDetailState.Ready(Samples.showDetail),
        ItemDetailState.Ready(Samples.seasonDetail),
        selectedSeasonId = "s2",
        actions = ItemDetailsActions({ _, _ -> }, { _, _ -> }, {}, {}),
    )
}

/** Settings for a paired server that offers libraries and recordings. */
@Composable
fun SettingsPreview(tv: Boolean, light: Boolean = false) = Frame(tv, light) {
    SettingsScreen(
        settings = AppSettings(
            serverUrl = "http://sofa.example:9193",
            apiToken = "token",
            captionsEnabled = true,
            appearance = if (light) Appearance.LIGHT else Appearance.SYSTEM,
        ),
        onSave = { _, _ -> },
        onBack = {},
        channelCount = Samples.channels.size,
        libraryOffered = true,
        recordingsOffered = true,
    )
}

private const val ICONS_PER_ROW = 3

/** The player's `SofaIcons` at 64dp with their names, to compare the hand-copied paths with Material's. */
@Composable
fun PlayerIconsPreview(modifier: Modifier = Modifier) = Frame(tv = false) {
    val icons = listOf(
        "Undo" to SofaIcons.Undo,
        "Redo" to SofaIcons.Redo,
        "Lyrics" to SofaIcons.Lyrics,
        "Cancel" to SofaIcons.Cancel,
        "FastForward" to SofaIcons.FastForward,
        "Replay" to SofaIcons.Replay,
        "Subtitles" to SofaIcons.Subtitles,
        "Replay10" to SofaIcons.Replay10,
        "Forward30" to SofaIcons.Forward30,
    )
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        icons.chunked(ICONS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (name, icon) ->
                    Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, contentDescription = name, modifier = Modifier.size(64.dp))
                        Text(name, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** The Now playing page for a song with lyrics, held open on its first moment. */
@Composable
fun NowPlayingPreview(tv: Boolean) = Frame(tv) {
    val scope = rememberCoroutineScope()
    val music = remember {
        MusicPlayback(UnavailableLivePlayer(CaptionSync(), "PREVIEW"), HoldingSongSessions, scope).also {
            it.play(Samples.album)
        }
    }
    val input = remember { LastInput(if (tv) InputKind.KEYS else InputKind.POINTER) }
    CompositionLocalProvider(LocalLastInput provides input) {
        NowPlayingScreen(music, onClose = {}, loadLyrics = { Lyrics(listOf(LyricLine(null, "Porch light on"))) })
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

/** The app's theme (the light one when [light]), layout profile and background around [content], on the fixed clock. */
@Composable
private fun Frame(tv: Boolean, light: Boolean = false, content: @Composable () -> Unit) {
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    SofaKingTheme(if (light) Appearance.LIGHT else Appearance.DARK) {
        CompositionLocalProvider(
            LocalLayoutProfile provides LayoutProfile.of(tv, sizeClass),
            LocalClock provides Samples.clock,
            LocalTimeZone provides TimeZone.UTC,
            LocalClockFormat provides ClockFormat.TWELVE_HOUR,
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }
}

private object Samples {
    val now: Instant = Instant.parse("2026-10-23T20:10:00Z")
    val clock = object : Clock {
        override fun now() = this@Samples.now
    }
    private val start = now - 25.minutes

    val channels = List(8) { i ->
        Channel(
            guideId = "c$i",
            number = (i + 2).toDouble(),
            name = listOf("News Now", "Sofa Sports", "Movies", "Kids", "Local 4", "Weather", "Music", "Classics")[i],
            logo = null,
            groups = listOf(if (i % 2 == 0) "News" else "Sports"),
            guideStatus = if (i == 3) GuideStatus.STALE else GuideStatus.AVAILABLE,
        )
    }

    val lineup = Lineup(
        channels = channels,
        guide = channels.filter { it.guideStatus == GuideStatus.AVAILABLE }.associate { channel ->
            channel.guideId to List(8) { slot ->
                Program(
                    channelId = channel.guideId,
                    start = start + (slot * 45).minutes,
                    stop = start + ((slot + 1) * 45).minutes,
                    title = "${channel.name} show ${slot + 1}",
                    description = "A night shift at the harbor turns into a search for a missing ship before the " +
                        "storm comes in.",
                    subTitle = "Part ${slot + 1}".takeIf { slot % 2 == 0 },
                    episode = "S1 E${slot + 1}".takeIf { slot % 2 == 0 },
                    isNew = slot == 0,
                    rating = "TV-PG".takeIf { slot % 2 == 0 },
                )
            }
        },
    )

    private val movies = MediaLibrary("movies", "Movies", LibraryKind.MOVIES, itemCount = 42)
    private val shows = MediaLibrary("shows", "TV Shows", LibraryKind.SHOWS, itemCount = 12)

    private fun movie(i: Int) = MediaItem(
        id = "m$i",
        libraryId = "movies",
        kind = ItemKind.MOVIE,
        title = listOf(
            "The Long Night",
            "Harbor Lights",
            "Paper Moon",
            "Quiet Town",
            "Northbound",
            "Second Act",
            "Glass Garden",
            "Dust",
        )[
            i %
                8,
        ],
        year = 1990 + i,
        durationMs = 6_480_000,
        genres = listOf("Drama", "Mystery"),
        progress = when (i) {
            0 -> PlayProgress(positionMs = 1_800_000, durationMs = 6_480_000, watched = false)
            2 -> PlayProgress(positionMs = 0, durationMs = 6_480_000, watched = true)
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
        aired = "2024-0$season-1$i",
        durationMs = 2_700_000,
        overview = "The crew follows a lead across the bay while a storm closes in on the harbor.",
        progress = progress,
    )

    val album = checkNotNull(
        MusicQueue.of(
            List(3) { i ->
                MediaItem(
                    id = "t$i",
                    libraryId = "music",
                    kind = ItemKind.TRACK,
                    title = "Porch Light ${i + 1}",
                    parentId = "album",
                    artist = "The Slow Tides",
                    album = "Harbor Hours",
                    durationMs = 213_000,
                )
            },
        ),
    )

    private val show = MediaItem(
        id = "show",
        libraryId = "shows",
        kind = ItemKind.SHOW,
        title = "Harbor Patrol",
        year = 2023,
        overview = "Coast guard officers keep a small harbor town safe through storms, smugglers and summer crowds.",
        genres = listOf("Drama"),
        contentRating = "TV-14",
        rating = 8.1,
        children = 2,
        unwatched = 9,
    )

    val home = MediaHome(
        shelves = listOf(
            MediaShelf(
                "continue",
                "Continue watching",
                listOf(movie(0), episode(1, 2, PlayProgress(900_000, 2_700_000, false))),
            ),
            MediaShelf("recent", "Recently added in Movies", List(8) { movie(it) }, libraryId = "movies"),
            MediaShelf("recent", "Recently added in TV Shows", listOf(show), libraryId = "shows"),
        ),
        libraries = listOf(movies, shows),
    )

    private val seasons =
        List(2) {
            MediaItem(
                "s${it + 1}",
                "shows",
                ItemKind.SEASON,
                "Season ${it + 1}",
                parentId = "show",
                season =
                it + 1,
                unwatched = 4,
            )
        }
    private val next = episode(2, 1, PlayProgress(600_000, 2_700_000, false))
    val showDetail = MediaItemDetail(item = show, children = seasons, next = next)
    val seasonDetail = MediaItemDetail(
        item = seasons[1],
        children = listOf(episode(2, 0, PlayProgress(0, 2_700_000, true)), next) + List(3) { episode(2, it + 2) },
    )
}
