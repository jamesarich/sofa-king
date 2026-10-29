package dev.jamesrich.sofaking.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The fixtures under `wire/` are real server responses, frozen per API version; the server's tests check that it
 * still sends every field in them. These tests check that the types still read them with a client's settings.
 */
class WireFixturesTest {
    private val client = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/wire/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `every v1 fixture decodes with a client's settings`() {
        assertEquals(true, client.decodeFromString<Bootstrap>(fixture("v1/bootstrap.json")).capabilities.playback)
        assertEquals(
            "available",
            client.decodeFromString<Catalog>(fixture("v1/catalog.json")).channels.single().guideStatus,
        )
        assertEquals(
            "Morning",
            client.decodeFromString<GuideWindow>(
                fixture("v1/guide-window.json"),
            ).channels.single().programs.single().title,
        )
        val session = client.decodeFromString<PlaybackSession>(fixture("v1/playback-session.json"))
        assertEquals("hls", session.descriptor.protocol)
        assertEquals(4200, session.health?.bitrateKbps)
        assertEquals(Wire.ErrorCode.AUTH_REQUIRED, client.decodeFromString<ApiError>(fixture("v1/api-error.json")).code)
        assertEquals(64, client.decodeFromString<PairingResult>(fixture("v1/pairing-result.json")).apiToken.length)
        assertEquals(
            "Living room TV",
            client.decodeFromString<ViewerPreferences>(fixture("v1/viewer-preferences.json")).name,
        )
        assertEquals(
            Wire.ItemKind.SHOW,
            client.decodeFromString<LibraryPage>(fixture("v1/library-page.json")).items.single().kind,
        )
        assertEquals(
            listOf(1, 2),
            client.decodeFromString<LibraryItemDetail>(fixture("v1/library-item-detail.json")).children.map {
                it.episode
            },
        )
        val episode = client.decodeFromString<LibraryItemDetail>(fixture("v1/library-episode-detail.json"))
        assertEquals(listOf(Wire.SegmentKind.INTRO, Wire.SegmentKind.CREDITS), episode.segments.map { it.kind })
        assertEquals(1, episode.scrub?.sheets?.size)
        val movie = client.decodeFromString<LibraryItemDetail>(fixture("v1/library-movie-detail.json"))
        assertEquals(Wire.ExtraKind.TRAILER, movie.extras.single().extra)
        assertEquals("Prometheus", movie.related.single().title)
        val home = client.decodeFromString<LibraryHome>(fixture("v1/library-home.json"))
        assertEquals(listOf(Wire.ShelfId.CONTINUE, Wire.ShelfId.RECENT), home.shelves.map { it.id })
        assertEquals(600000, home.shelves.first().items.single().playState?.positionMs)
        val vod = client.decodeFromString<VodSession>(fixture("v1/vod-session.json"))
        assertEquals(Wire.VodMode.REMUX, vod.mode)
        assertEquals(2000, vod.startMs)
        val recordings = client.decodeFromString<RecordingList>(fixture("v1/recording-list.json"))
        assertEquals(listOf(RecordingBreak(1000, 2500)), recordings.recordings.single().breaks)
        assertEquals(Wire.RecordingState.COMPLETED, recordings.recordings.single().state)
        assertEquals(
            5,
            client.decodeFromString<RecordingRuleList>(fixture("v1/recording-rule-list.json")).rules.single().keep,
        )
        assertEquals(
            Wire.LibraryKind.SHOWS,
            client.decodeFromString<List<Library>>(fixture("v1/libraries.json")).single().kind,
        )
        val item = client.decodeFromString<LibraryItem>(fixture("v1/library-item.json"))
        assertEquals(2, item.episodeEnd)
        assertEquals(600000, item.playState?.positionMs)
    }

    @Test
    fun `the program facts fixture decodes with a client's settings`() {
        val facts = client.decodeFromString<ProgramFacts>(fixture("v1/program-facts.json"))
        assertEquals(Wire.ItemKind.SHOW, facts.kind)
        assertEquals(406, facts.episodes)
    }

    @Test
    fun `the household preferences fixture decodes with a client's settings`() {
        val household = client.decodeFromString<HouseholdPreferences>(fixture("v1/household-preferences.json"))
        assertEquals(2, household.favorites.size)
        assertEquals("2026-10-16T00:30:00Z", household.updatedAt)
    }

    @Test
    fun `the watchlist fixture decodes with a client's settings`() {
        val watchlist = client.decodeFromString<Watchlist>(fixture("v1/watchlist.json")).entries.single()
        assertEquals(Wire.WatchlistKind.TITLE, watchlist.kind)
        assertEquals("Movie Night", watchlist.next?.title)
    }

    @Test
    fun `a music item keeps its artist album track and listening fields`() {
        val strict = Json { explicitNulls = false }
        val track = strict.decodeFromString<LibraryItem>(fixture("v1/library-item-music.json"))
        assertEquals(Wire.ItemKind.TRACK, track.kind)
        assertEquals("e91b6a30-5d2f-3f0e-8c47-2a7d9b1c4f05", track.artistId)
        assertEquals("The Tidewater", track.artist)
        assertEquals("The Tidewater feat. Mara Lin", track.trackArtist)
        assertEquals("Harbor Lights", track.album)
        assertEquals(4, track.track)
        assertEquals(2, track.disc)
        assertEquals(true, track.favorite)
        assertEquals(12, track.plays)
        assertEquals("2026-10-13T18:05:00Z", track.lastPlayedAt)
        assertEquals(true, track.playState?.dismissed)
    }

    @Test
    fun `a library page keeps its letters and the people a search found`() {
        val strict = Json { explicitNulls = false }
        val page = strict.decodeFromString<LibraryPage>(fixture("v1/library-page-letters.json"))
        assertEquals(listOf(LetterCount("#", 4), LetterCount("A", 31), LetterCount("H", 85)), page.letters)
        assertEquals(listOf("Mara Lin", "Joe Okafor"), page.people)
        assertEquals(Wire.ItemKind.ALBUM, page.items.single().kind)
        assertEquals(120, page.total)
    }

    @Test
    fun `a rule keeps what it matches`() {
        val strict = Json { explicitNulls = false }
        val rule = strict.decodeFromString<RecordingRuleList>(fixture("v1/recording-rule-match.json")).rules.single()
        assertEquals(
            RuleMatch(Wire.RuleMatchKind.TEAM, listOf("Rangers", "Bruins"), listOf("Sports", "Hockey")),
            rule.match,
        )
    }

    @Test
    fun `the ad break and multiview and on later fixtures decode`() {
        val strict = Json { explicitNulls = false }
        assertEquals(
            AdBreakState(true, 42000),
            strict.decodeFromString<AdBreakState>(fixture("v1/ad-break-state.json")),
        )
        val multiview = strict.decodeFromString<MultiviewSession>(fixture("v1/multiview-session.json"))
        assertEquals(2, multiview.channelIds.size)
        assertEquals(1, multiview.audio)
        assertEquals(2, multiview.descriptor.epoch)
        val program = strict.decodeFromString<UpcomingPrograms>(fixture("v1/upcoming-programs.json")).programs.single()
        assertEquals("Evening News", program.title)
        assertEquals(listOf("News"), program.categories)
    }

    /**
     * The full fixtures hold a value in every field, so the server's check types each one rather than only finding it
     * present; they decode with no unknown field. A field added later is absent from them, which the rules allow.
     */
    @Test
    fun `the full recording, rule, library item and program facts fixtures hold no nulls and no unknown fields`() {
        val strict = Json { explicitNulls = false }
        fun nulls(element: JsonElement): Set<String> = (element as JsonObject).filterValues { it is JsonNull }.keys

        val recordings = Json.parseToJsonElement(fixture("v1/recording-list.json")).jsonObject
        strict.decodeFromJsonElement<RecordingList>(recordings)
        val recording = recordings["recordings"]!!.jsonArray.single()
        assertEquals(emptySet(), nulls(recording))
        assertEquals(emptySet(), nulls(recording.jsonObject["breaks"]!!.jsonArray.single()))
        assertEquals(emptySet(), nulls(recordings["storage"]!!))

        val rules = Json.parseToJsonElement(fixture("v1/recording-rule-list.json"))
        strict.decodeFromJsonElement<RecordingRuleList>(rules)
        assertEquals(emptySet(), nulls(rules.jsonObject["rules"]!!.jsonArray.single()))

        val item = Json.parseToJsonElement(fixture("v1/library-item.json"))
        strict.decodeFromJsonElement<LibraryItem>(item)
        assertEquals(emptySet(), nulls(item))
        assertEquals(emptySet(), nulls(item.jsonObject["playState"]!!))
        strict.decodeFromJsonElement<List<Library>>(Json.parseToJsonElement(fixture("v1/libraries.json")))
        val facts = Json.parseToJsonElement(fixture("v1/program-facts.json"))
        strict.decodeFromJsonElement<ProgramFacts>(facts)
        assertEquals(emptySet(), nulls(facts))
    }

    @Test
    fun `a response without the fields no client reads still decodes`() {
        val bootstrap = client.decodeFromString<Bootstrap>("""{"serverId":"s","serverTime":"t"}""")
        assertEquals(1, bootstrap.apiVersion)
        assertEquals(Capabilities(), bootstrap.capabilities)
        val catalog = client.decodeFromString<Catalog>("""{"channels":[{"id":"c","displayNumber":"2","name":"PBS"}]}""")
        assertEquals(Wire.GuideStatus.AVAILABLE, catalog.channels.single().guideStatus)
        val window = client.decodeFromString<GuideWindow>(
            """{"revision":"r","channels":[{"channelId":"c","programs":[""" +
                """{"startsAt":"a","endsAt":"b","title":"News"}]}]}""",
        )
        assertEquals("News", window.channels.single().programs.single().title)
        val session = client.decodeFromString<PlaybackSession>(
            """{"id":"s","channelId":"c","intent":"watch","descriptor":{"protocol":"hls","mimeType":"m","url":"/""" +
                """u","epoch":1}}""",
        )
        assertEquals("", session.expiresAt)
        assertEquals(false, client.decodeFromString<ApiError>("""{"code":"X","message":"m"}""").retryable)
        assertEquals(
            false,
            client.decodeFromString<Bootstrap>(
                """{"serverId":"s","serverTime":"t","capabilities":{"catalog":true}}""",
            ).capabilities.viewers,
        )
        assertEquals("", client.decodeFromString<PairingResult>("""{"apiToken":"t"}""").serverId)
        assertEquals(ViewerPreferences(), client.decodeFromString<ViewerPreferences>("{}"))
        assertEquals(HouseholdPreferences(), client.decodeFromString<HouseholdPreferences>("{}"))
        assertEquals(PlayState(), client.decodeFromString<PlayState>("{}"))
        val item = client.decodeFromString<LibraryItem>("""{"id":"i","libraryId":"l","kind":"movie","title":"Alien"}""")
        assertEquals(0, item.unwatched)
        assertNull(item.playState)
        assertEquals(emptyList(), client.decodeFromString<LibraryHome>("""{"shelves":[]}""").libraries)
        val entry = client.decodeFromString<WatchlistEntry>("""{"id":"w","kind":"item","title":"Alien"}""")
        assertNull(entry.item)
        assertNull(entry.next)
    }

    @Test
    fun `a client opens a VOD session with its profile and where to start`() {
        val body = Json.encodeToString(
            OpenVodPlayback.serializer(),
            OpenVodPlayback("i", ClientProfile(listOf("mp4"), listOf("h264"), listOf("aac"), 1080), 5000),
        )
        assertEquals(
            """{"itemId":"i","profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":""" +
                """["aac"],"maxHeight":1080},"startMs":5000}""",
            body,
        )
        assertEquals(
            VodSession("s", "i", "direct", PlaybackDescriptor("file", "video/mp4", "/u", 1)),
            client.decodeFromString<VodSession>(
                """{"id":"s","itemId":"i","mode":"direct","descriptor":{"protocol":"file","mimeType":"video/""" +
                    """mp4","url":"/u","epoch":1}}""",
            ),
        )
    }

    @Test
    fun `a client sends only the play state it changes`() {
        assertEquals(
            """{"positionMs":1000}""",
            Json.encodeToString(UpdatePlayState.serializer(), UpdatePlayState(positionMs = 1000)),
        )
        assertEquals(
            """{"watched":true}""",
            Json.encodeToString(UpdatePlayState.serializer(), UpdatePlayState(watched = true)),
        )
    }

    @Test
    fun `a pairing claim carries the code and who claims it`() {
        val body = Json.encodeToString(
            PairingClaim.serializer(),
            PairingClaim("123456", "client-1", "Den", "android-tv"),
        )
        assertEquals("""{"code":"123456","clientId":"client-1","name":"Den","platform":"android-tv"}""", body)
    }

    @Test
    fun `a client always sends the intent it opens a session with`() {
        val body = Json.encodeToString(OpenPlayback.serializer(), OpenPlayback("c", Wire.Intent.WATCH, "k"))
        assertEquals("""{"channelId":"c","intent":"watch","idempotencyKey":"k"}""", body)
    }
}
