package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The settings store over the DataStore file every platform with a file system uses, on every such target. */
class DataStoreSettingsStoreTest {
    private val dir = testDirectory()
    private val file = Path(dir, "settings.preferences_pb")

    /** One DataStore per test: a second instance over the same file would not see the first one's writes. */
    private val dataStore: DataStore<Preferences> by lazy {
        SystemFileSystem.createDirectories(dir)
        preferencesDataStore(file.toString())
    }

    @AfterTest
    fun tearDown() {
        if (SystemFileSystem.exists(dir)) SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(dir, mustExist = false)
    }

    private fun newStore(): DataStoreSettingsStore = DataStoreSettingsStore(dataStore)

    @Test
    fun `an empty store has no server and no token`() = runTest {
        val settings = newStore().settings.first()
        assertEquals(AppSettings(), settings)
        assertEquals("", settings.serverUrl)
        assertEquals("", settings.apiToken)
    }

    @Test
    fun `setServer trims whitespace and strips a trailing slash`() = runTest {
        val store = newStore()
        store.setServer("  http://sofa.lan:9193/  ", " abc123 ")

        val settings = store.settings.first()
        assertEquals("http://sofa.lan:9193", settings.serverUrl)
        assertEquals("abc123", settings.apiToken)
    }

    @Test
    fun `the token is kept out of the settings' string form`() {
        val printed = AppSettings(serverUrl = "http://sofa.lan:9193", apiToken = "s3cret-token").toString()
        assertFalse(printed.contains("s3cret"), printed)
        assertTrue(printed.contains("http://sofa.lan:9193"), printed)
    }

    @Test
    fun `toggleFavorite adds then removes a guide id`() = runTest {
        val store = newStore()

        store.toggleFavorite("5")
        assertEquals(setOf("5"), store.settings.first().favorites)

        store.toggleFavorite("5")
        assertEquals(emptySet<String>(), store.settings.first().favorites)
    }

    @Test
    fun `setLastChannel is persisted`() = runTest {
        val store = newStore()
        assertEquals(null, store.settings.first().lastChannel)

        store.setLastChannel("12")
        assertEquals("12", store.settings.first().lastChannel)
    }

    @Test
    fun `setLastChannel keeps the ten most recent channels newest first without repeats`() = runTest {
        val store = newStore()
        assertEquals(emptyList(), store.settings.first().recentChannels)

        store.setLastChannel("1")
        store.setLastChannel("2")
        store.setLastChannel("1")
        assertEquals(listOf("1", "2"), store.settings.first().recentChannels)

        for (id in 3..12) store.setLastChannel(id.toString())
        val recent = store.settings.first().recentChannels
        assertEquals(AppSettings.RECENT_CHANNELS, recent.size)
        assertEquals("12", recent.first())
        assertEquals("12", store.settings.first().lastChannel)
        assertFalse(recent.contains("1") || recent.contains("2"), "the oldest channels drop off: $recent")
    }

    @Test
    fun `startup keeps a chosen channel only for the channel target`() = runTest {
        val store = newStore()
        assertEquals(StartupTarget.RESUME, store.settings.first().startup)
        store.setStartup(StartupTarget.CHANNEL, "7")
        assertEquals(StartupTarget.CHANNEL to "7", store.settings.first().let { it.startup to it.startupChannel })
        store.setStartup(StartupTarget.GUIDE, "7")
        assertEquals(StartupTarget.GUIDE to null, store.settings.first().let { it.startup to it.startupChannel })
    }

    @Test
    fun `a stored last channel startup reads as where the viewer left off`() = runTest {
        dataStore.edit { it[stringPreferencesKey("startup")] = "LAST_CHANNEL" }
        assertEquals(StartupTarget.RESUME, newStore().settings.first().startup)
    }

    @Test
    fun `the last tab is kept and starts on Live`() = runTest {
        val store = newStore()
        assertEquals(TopLevelTab.LIVE, store.settings.first().lastTab)
        store.setLastTab(TopLevelTab.LIBRARY)
        assertEquals(TopLevelTab.LIBRARY, store.settings.first().lastTab)
    }

    @Test
    fun `guide density round trips and falls back to its default`() = runTest {
        val store = newStore()
        assertEquals(GuideDensity.COMFORTABLE, store.settings.first().guideDensity)
        store.setGuideDensity(GuideDensity.COMPACT)
        assertEquals(GuideDensity.COMPACT, store.settings.first().guideDensity)
    }

    @Test
    fun `appearance follows the system by default and round trips every choice`() = runTest {
        val store = newStore()
        assertEquals(Appearance.SYSTEM, store.settings.first().appearance)

        for (appearance in Appearance.entries) {
            store.setAppearance(appearance)
            assertEquals(appearance, store.settings.first { it.appearance == appearance }.appearance)
        }
    }

    @Test
    fun `an unrecognized stored appearance falls back to the system`() = runTest {
        dataStore.edit { it[stringPreferencesKey("appearance")] = "NO_SUCH_LOOK" }

        assertEquals(Appearance.SYSTEM, newStore().settings.first().appearance)
    }

    @Test
    fun `hidden channels are added and removed and the zap switch persists`() = runTest {
        val store = newStore()
        store.setChannelHidden("a", true)
        store.setChannelHidden("b", true)
        store.setChannelHidden("a", false)
        assertEquals(setOf("b"), store.settings.first().hiddenChannels)
        assertFalse(store.settings.first().zapSkipsHidden)
        store.setZapSkipsHidden(true)
        assertTrue(store.settings.first().zapSkipsHidden)
    }

    @Test
    fun `intros credits and commercial breaks wait for a press until set otherwise`() = runTest {
        val store = newStore()
        assertEquals(Skips(), store.settings.first().skips)
        store.setSkip(SkipKind.CREDITS, SkipMode.AUTO)
        store.setSkip(SkipKind.COMMERCIAL_BREAKS, SkipMode.OFF)
        assertEquals(
            Skips(credits = SkipMode.AUTO, commercialBreaks = SkipMode.OFF),
            store.settings.first().skips,
        )
    }

    @Test
    fun `the switches an older build kept stand in until a mode is set and stay written for it`() = runTest {
        dataStore.edit {
            it[booleanPreferencesKey("auto_skip_intros")] = true
            it[booleanPreferencesKey("skip_commercials")] = true
        }
        val store = newStore()
        assertEquals(Skips(intros = SkipMode.AUTO, commercialBreaks = SkipMode.AUTO), store.settings.first().skips)

        store.setSkip(SkipKind.INTROS, SkipMode.OFF)
        store.setSkip(SkipKind.COMMERCIAL_BREAKS, SkipMode.BUTTON)
        assertEquals(Skips(intros = SkipMode.OFF), store.settings.first().skips)
        val prefs = dataStore.data.first()
        assertEquals(false, prefs[booleanPreferencesKey("auto_skip_intros")])
        assertEquals(false, prefs[booleanPreferencesKey("skip_commercials")])
    }

    @Test
    fun `a press steps 10 seconds back and 30 forward until set otherwise`() = runTest {
        val store = newStore()
        assertEquals(SeekSteps(10, 30), store.settings.first().seekSteps)
        assertEquals(10_000, store.settings.first().seekSteps.backMs)
        store.setSeekSteps(SeekSteps(backSeconds = 5, forwardSeconds = 90))
        assertEquals(SeekSteps(5, 90), store.settings.first().seekSteps)
        assertEquals(90_000, store.settings.first().seekSteps.forwardMs)
    }

    @Test
    fun `episodes show their overviews until Hide spoilers is turned on`() = runTest {
        val store = newStore()
        assertFalse(store.settings.first().hideSpoilers)
        store.setHideSpoilers(true)
        assertTrue(store.settings.first().hideSpoilers)
    }

    @Test
    fun `library subtitles start medium at the bottom on a box and keep what is chosen`() = runTest {
        val store = newStore()
        assertEquals(
            SubtitleLook(SubtitleSize.MEDIUM, SubtitleRaise.NONE, box = true),
            store.settings.first().subtitleLook,
        )
        val chosen = SubtitleLook(SubtitleSize.LARGE, SubtitleRaise.HIGH, box = false)
        store.setSubtitleLook(chosen)
        assertEquals(chosen, store.settings.first().subtitleLook)
    }

    @Test
    fun `live ad breaks play until the break screen is chosen`() = runTest {
        val store = newStore()
        assertEquals(LiveBreaks.PLAY, store.settings.first().liveBreaks)
        store.setLiveBreaks(LiveBreaks.SCREEN_MUTED)
        assertEquals(LiveBreaks.SCREEN_MUTED, store.settings.first().liveBreaks)
    }

    @Test
    fun `ads dodged add up through the day and start again the next`() = runTest {
        val store = newStore()
        assertEquals(0L, store.settings.first().adsDodged.on("2026-10-22"))
        store.addAdsDodged("2026-10-22", 90_000)
        store.addAdsDodged("2026-10-22", 30_000)
        assertEquals(120_000L, store.settings.first().adsDodged.on("2026-10-22"))
        assertEquals(0L, store.settings.first().adsDodged.on("2026-10-23"))
        store.addAdsDodged("2026-10-23", 15_000)
        assertEquals(AdsDodged("2026-10-23", 15_000), store.settings.first().adsDodged)
    }

    @Test
    fun `the tracks picked in a show are kept per show and the newest pick replaces the last`() = runTest {
        val store = newStore()
        assertTrue(store.settings.first().trackChoices.isEmpty())
        store.setTrackChoice("show-a", TrackChoice("jpn", subtitles = true, subtitleLanguage = "eng"))
        store.setTrackChoice("show-b", TrackChoice(subtitles = false))
        store.setTrackChoice("show-a", TrackChoice("eng"))
        val kept = store.settings.first().trackChoices
        assertEquals(TrackChoice("eng"), kept["show-a"])
        assertEquals(TrackChoice(), kept["show-b"])
        assertEquals(listOf("show-a", "show-b"), kept.keys.toList(), "most recent first")
    }

    @Test
    fun `the favorites order is kept and an unstarred channel leaves it`() = runTest {
        val store = newStore()
        store.toggleFavorite("a")
        store.toggleFavorite("b")
        store.setFavoritesOrder(listOf("b", "a", "b"))
        assertEquals(listOf("b", "a"), store.settings.first().favoritesOrder)
        store.toggleFavorite("b")
        assertEquals(listOf("a"), store.settings.first().favoritesOrder)
        assertEquals(setOf("a"), store.settings.first().favorites)
    }

    @Test
    fun `playback preferences default to the engine's own and round trip`() = runTest {
        val store = newStore()
        assertEquals(PlaybackPreferences(), store.settings.first().playback)
        store.setMatchFrameRate(true)
        store.setDecoder(DecoderPreference.SOFTWARE)
        store.setBuffer(BufferPreference.SMOOTH)
        store.setChannelDecoder("x", DecoderPreference.AUTO)
        val playback = store.settings.first().playback
        assertEquals(
            PlaybackPreferences(
                matchFrameRate = true,
                decoder = DecoderPreference.SOFTWARE,
                decoderByChannel = mapOf("x" to DecoderPreference.AUTO),
                buffer = BufferPreference.SMOOTH,
            ),
            playback,
        )
        assertEquals(DecoderPreference.AUTO, playback.decoderFor("x"))
        assertEquals(DecoderPreference.SOFTWARE, playback.decoderFor("y"))
        store.setChannelDecoder("x", null)
        assertEquals(emptyMap(), store.settings.first().playback.decoderByChannel)
    }

    @Test
    fun `the guide filter is kept and cleared`() = runTest {
        val store = newStore()
        assertEquals(null, store.settings.first().guideFilter)
        store.setGuideFilter("group:News")
        assertEquals("group:News", store.settings.first().guideFilter)
        store.setGuideFilter(null)
        assertEquals(null, store.settings.first().guideFilter)
    }

    @Test
    fun `captions are off until the user turns them on`() = runTest {
        assertFalse(newStore().settings.first().captionsEnabled)
    }

    @Test
    fun `setCaptionsEnabled is persisted in both directions`() = runTest {
        val store = newStore()

        store.setCaptionsEnabled(true)
        assertTrue(store.settings.first().captionsEnabled)

        store.setCaptionsEnabled(false)
        assertFalse(store.settings.first().captionsEnabled)
    }

    @Test
    fun `caption advance defaults to zero and is clamped to eight seconds either way`() = runTest {
        val store = newStore()
        assertEquals(0, store.settings.first().captionAdvanceMs)

        store.setCaptionAdvanceMs(5_500)
        assertEquals(5_500, store.settings.first().captionAdvanceMs)

        store.setCaptionAdvanceMs(42_000)
        assertEquals(8_000, store.settings.first().captionAdvanceMs)

        store.setCaptionAdvanceMs(-3_000)
        assertEquals(-3_000, store.settings.first().captionAdvanceMs)

        store.setCaptionAdvanceMs(-42_000)
        assertEquals(-8_000, store.settings.first().captionAdvanceMs)

        store.setChannelCaptionAdvance("5", -1_500)
        assertEquals(-1_500, store.settings.first().captionAdvanceFor("5"))
    }

    @Test
    fun `per-channel caption advance overrides the default only for that channel`() = runTest {
        val store = newStore()
        store.setCaptionAdvanceMs(5_000)
        store.setChannelCaptionAdvance("5", 1_000)
        store.setChannelCaptionAdvance("8", 6_500)
        store.setChannelCaptionAdvance("5", 2_500)

        val settings = store.settings.first()
        assertEquals(2_500, settings.captionAdvanceFor("5"))
        assertEquals(6_500, settings.captionAdvanceFor("8"))
        assertEquals(5_000, settings.captionAdvanceFor("108"), "unadjusted channels use the default")
    }

    @Test
    fun `a set default replaces the zero default`() = runTest {
        val store = newStore()
        store.setCaptionAdvanceMs(1_500)

        assertEquals(1_500, store.settings.first().captionAdvanceMs)
    }

    @Test
    fun `smooth captions are on by default and can be turned off`() = runTest {
        val store = newStore()
        assertTrue(store.settings.first().smoothCaptions)

        store.setSmoothCaptions(false)
        assertFalse(store.settings.first().smoothCaptions)
    }

    @Test
    fun `caption style is system by default and persists the large style`() = runTest {
        val store = newStore()
        assertEquals(CaptionStyle.SYSTEM, store.settings.first().captionStyle)

        store.setCaptionStyle(CaptionStyle.LARGE_HIGH_CONTRAST)
        assertEquals(CaptionStyle.LARGE_HIGH_CONTRAST, store.settings.first().captionStyle)
    }

    @Test
    fun `an unrecognized stored caption style falls back to system`() = runTest {
        dataStore.edit { it[stringPreferencesKey("caption_style")] = "NO_SUCH_STYLE" }

        assertEquals(CaptionStyle.SYSTEM, newStore().settings.first().captionStyle)
    }

    @Test
    fun `auto open defaults to ten seconds and round trips every option`() = runTest {
        val store = newStore()
        assertEquals(AutoOpenDelay.AFTER_10_SECONDS, store.settings.first().autoOpenDelay)

        for (delay in AutoOpenDelay.entries) {
            store.setAutoOpenDelay(delay)
            assertEquals(delay, store.settings.first { it.autoOpenDelay == delay }.autoOpenDelay)
        }
    }

    @Test
    fun `an unrecognized stored auto open delay falls back to ten seconds`() = runTest {
        dataStore.edit { it[stringPreferencesKey("auto_open_delay")] = "NO_SUCH_DELAY" }

        assertEquals(AutoOpenDelay.AFTER_10_SECONDS, newStore().settings.first().autoOpenDelay)
    }

    @Test
    fun `preview sound is on by default and can be turned off`() = runTest {
        val store = newStore()
        assertTrue(store.settings.first().previewSound)

        store.setPreviewSound(false)
        assertFalse(store.settings.first().previewSound)
    }

    @Test
    fun `volume is full and unmuted by default and keeps a level clamped to the range`() = runTest {
        val store = newStore()
        assertEquals(AppSettings.MAX_VOLUME, store.settings.first().volume)
        assertFalse(store.settings.first().volumeMuted)

        store.setVolume(35, muted = true)
        assertEquals(35, store.settings.first().volume)
        assertTrue(store.settings.first().volumeMuted)

        store.setVolume(250, muted = false)
        assertEquals(AppSettings.MAX_VOLUME, store.settings.first().volume)
        store.setVolume(-5, muted = false)
        assertEquals(0, store.settings.first().volume)
    }

    @Test
    fun `a file that is not a preferences store reads as defaults and is replaced`() = runTest {
        SystemFileSystem.createDirectories(dir)
        SystemFileSystem.sink(file).buffered().use { it.write(byteArrayOf(0x7f, 0x00, 0x7f, 0x7f, 0x7f)) }

        assertEquals(AppSettings(), newStore().settings.first())
        val store = newStore()
        store.setLastChannel("3")
        assertEquals("3", store.settings.first().lastChannel)
    }

    @Test
    fun `settings flow emits each update`() = runTest {
        val store = newStore()

        store.setServer("http://a:9193", "one")
        assertEquals("one", store.settings.first { it.apiToken == "one" }.apiToken)

        store.setServer("http://b:9193", "two")
        val updated = store.settings.first { it.apiToken == "two" }
        assertEquals("http://b:9193", updated.serverUrl)
    }

    @Test
    fun `the client id is made once and the server's preferences replace the synced fields`() = runTest {
        val store = newStore()
        val id = store.ensureClientId()
        assertTrue(id.length >= 32, id)
        assertEquals(id, store.ensureClientId())
        assertEquals(id, store.settings.first().clientId)

        store.toggleFavorite("mine")
        store.setChannelHidden("h0", true)
        store.setLastChannel("watched")
        store.applyServerPreferences(setOf("h1"), listOf("b", "a"), listOf("r1", "r2"), "2026-10-16T00:00:00Z")
        val settings = store.settings.first()
        assertEquals(setOf("h1"), settings.hiddenChannels)
        assertEquals(setOf("a", "b"), settings.favorites)
        assertEquals(listOf("b", "a"), settings.favoritesOrder)
        assertEquals(listOf("r1", "r2"), settings.recentChannels)
        assertEquals("watched", settings.lastChannel, "a last channel this device has is kept")
        assertEquals("2026-10-16T00:00:00Z", settings.preferencesSyncedAt)

        store.setPreferencesSyncedAt("2026-10-16T00:01:00Z")
        assertEquals("2026-10-16T00:01:00Z", store.settings.first().preferencesSyncedAt)
    }

    @Test
    fun `a group of channels hides and shows in one write`() = runTest {
        val store = newStore()
        store.setChannelHidden("solo", true)
        store.setChannelsHidden(listOf("a", "b"), true)
        assertEquals(setOf("solo", "a", "b"), store.settings.first().hiddenChannels)
        store.setChannelsHidden(listOf("b", "solo", "missing"), false)
        assertEquals(setOf("a"), store.settings.first().hiddenChannels)
    }

    @Test
    fun `the settings PIN is stored hashed and removed with null`() = runTest {
        val store = newStore()
        assertFalse(store.settings.first().settingsLocked)
        // A fixed salt: with a random one the hex digest itself contains "2580" about once in 700 runs.
        store.setSettingsPin(SettingsPin.hash("2580", salt = "00".repeat(16)))
        val locked = store.settings.first()
        assertTrue(locked.settingsLocked)
        assertTrue(SettingsPin.matches("2580", locked.settingsPin))
        assertFalse(locked.settingsPin.contains("2580"))
        store.setSettingsPin(null)
        assertFalse(store.settings.first().settingsLocked)
    }

    @Test
    fun `kids mode takes a PIN and goes with it`() = runTest {
        val store = newStore()
        store.setKidsMode(true)
        assertFalse(store.settings.first().kidsMode)
        store.setSettingsPin(SettingsPin.hash("2580"))
        store.setKidsMode(true)
        assertTrue(store.settings.first().kidsMode)
        store.setKidsMode(false)
        assertFalse(store.settings.first().kidsMode)
        store.setKidsMode(true)
        store.setSettingsPin(null)
        assertFalse(store.settings.first().kidsMode)
        store.setSettingsPin(SettingsPin.hash("2580"))
        assertFalse(store.settings.first().kidsMode)
    }
}
