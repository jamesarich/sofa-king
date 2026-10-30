package dev.jamesrich.sofaking.backend.settings

import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.logging.LogSettings
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SettingsRegistryTest {
    /** The settings each [ServerConfig] field is read from; a field set from none is listed with an empty list. */
    private val fieldSettings = mapOf(
        "host" to listOf("SOFA_HOST"),
        "port" to listOf("SOFA_PORT"),
        "dataDirectory" to listOf("SOFA_DATA_DIR"),
        "sourcesFile" to listOf("SOFA_SOURCES"),
        "apiToken" to listOf("SOFA_API_TOKEN"),
        "maxSessions" to listOf("SOFA_MAX_SESSIONS"),
        "ffmpeg" to listOf("SOFA_FFMPEG"),
        "probeParallelism" to listOf("SOFA_PROBE_PARALLELISM"),
        "restreamOrigins" to listOf("SOFA_RESTREAM_ORIGINS"),
        "observationRetentionDays" to listOf("SOFA_OBSERVATION_RETENTION_DAYS"),
        "allowLoopbackUpstreams" to listOf("SOFA_ALLOW_LOOPBACK_UPSTREAMS"),
        "adminToken" to listOf("SOFA_ADMIN_TOKEN"),
        "outputs" to listOf("SOFA_OUTPUT_TOKEN", "SOFA_XMLTV_DAYS"),
        "hdhr" to listOf(
            "SOFA_HDHR_ENABLED",
            "SOFA_HDHR_TUNERS",
            "SOFA_HDHR_DEVICE_ID",
            "SOFA_HDHR_NAME",
            "SOFA_HDHR_ALLOW",
            "SOFA_HDHR_EXTRA_PORTS",
            "SOFA_HDHR_DEVICE_SIZE",
            "SOFA_HDHR_PUBLIC_PORTS",
        ),
        "policyFile" to listOf("SOFA_POLICY"),
        "plex" to listOf("SOFA_PLEX_URL", "SOFA_PLEX_TOKEN", "SOFA_PLEX_TUNER_BASE"),
        "plexSyncMinutes" to listOf("SOFA_PLEX_SYNC_MINUTES"),
        "secretOrigins" to emptyList(),
        "secretsFile" to emptyList(),
        "foundInDataDirectory" to emptyList(),
        "logging" to listOf("SOFA_LOG_LEVEL", "SOFA_LOG_FORMAT", "SOFA_LOG_DAYS"),
        "revision" to listOf("SOFA_REVISION"),
        "version" to listOf("SOFA_VERSION"),
        "updateImage" to listOf("SOFA_UPDATE_IMAGE"),
        "corsOrigins" to listOf("SOFA_CORS_ORIGINS"),
        "recordingsDirectory" to listOf("SOFA_RECORDINGS_DIR"),
        "mediaDirectory" to listOf("SOFA_MEDIA_DIR"),
        "webDirectory" to listOf("SOFA_WEB_DIR"),
        "tmdb" to listOf("SOFA_TMDB_TOKEN", "SOFA_TMDB_LANGUAGE"),
        "maxTranscodes" to listOf("SOFA_MAX_TRANSCODES"),
        "videoEncoder" to listOf("SOFA_HWACCEL", "SOFA_HWACCEL_DEVICE"),
        "multiview" to listOf("SOFA_MULTIVIEW"),
        "recordingsFreeGib" to listOf("SOFA_RECORDINGS_FREE_GIB"),
        "recordingsKeepDays" to listOf("SOFA_RECORDINGS_KEEP_DAYS"),
        "learnedBreaks" to listOf("SOFA_LEARNED_BREAKS"),
        "adListeners" to listOf("SOFA_AD_LISTENERS"),
        "iptvOrg" to listOf("SOFA_IPTV_ORG", "SOFA_IPTV_ORG_STREAMS", "SOFA_IPTV_ORG_REGION"),
        "comskip" to listOf("SOFA_COMSKIP"),
        "mdns" to listOf("SOFA_MDNS"),
        "libraryPathMap" to listOf("SOFA_LIBRARY_PATH_MAP"),
        "historyDays" to listOf("SOFA_HISTORY_DAYS"),
        "qualityFloors" to listOf("SOFA_QUALITY_FLOORS"),
        "musicBrainz" to listOf("SOFA_MUSICBRAINZ"),
        "fanartKey" to listOf("SOFA_FANART_KEY"),
        "listenBrainzToken" to listOf("SOFA_LISTENBRAINZ_TOKEN"),
    )

    @Test fun `every server config field is read from registered settings`() {
        val fields = ServerConfig::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(fields, fieldSettings.keys, "a new field needs its settings listed here")
        val unknown = fieldSettings.values.flatten().filter { it !in Settings.byName }
        assertTrue(unknown.isEmpty(), "not in the registry: $unknown")
        val unread = Settings.all.map { it.name } - fieldSettings.values.flatten().toSet()
        assertTrue(unread.isEmpty(), "in the registry but read into no field: $unread")
    }

    @Test fun `each registry default is what the server starts with`() {
        val config = ServerConfig.fromEnvironment(emptyMap())
        for (def in Settings.all) {
            assertEquals(def.usual(config)?.toString().orEmpty(), def.show(config)?.toString().orEmpty(), def.name)
        }
        assertEquals(Settings.all.size, Settings.all.map { it.name }.toSet().size, "a name is registered once")
    }

    @Test fun `the server settings table in the docs lists exactly the registered settings`() {
        val table = Files.readAllLines(Path.of("..", "docs", "live-tv.md"))
            .filter { it.startsWith("| `SOFA_") }
            .flatMap { row -> Regex("`(SOFA_[A-Z_]+)`").findAll(row.split('|')[1]).map { it.groupValues[1] }.toList() }
            .filter { !it.endsWith("_FILE") }
        assertEquals(Settings.all.map { it.name }.sorted(), table.sorted())
    }

    @Test fun `an invalid value stops the start with one line naming the variable`() {
        val checked = Settings.all.filter {
            !it.custom &&
                (it.type is SettingType.Number || it.type is SettingType.Choice || it.type == SettingType.Flag)
        }
        assertTrue(checked.size > 20, "the generic readers cover most settings")
        for (def in checked) {
            val invalid = buildList {
                add("bogus")
                if (def.type is SettingType.Number) add((def.range.last + 1).toString())
            }
            for (value in invalid) {
                val failure = assertFailsWith<IllegalArgumentException>("${def.name}=$value") {
                    ServerConfig.fromEnvironment(mapOf(def.name to value))
                }
                assertEquals(def.failure, failure.message)
                assertTrue(failure.message!!.startsWith(def.name) && '\n' !in failure.message!!, failure.message)
            }
        }
    }

    @Test fun `a rule lives in the registry once for the environment and for the portal`() {
        assertEquals(
            Settings.LOG_LEVEL.failure,
            assertFailsWith<IllegalArgumentException> { LogSettings.from(mapOf("SOFA_LOG_LEVEL" to "loud")) }.message,
        )
        assertEquals(
            Settings.LEARNED_BREAKS.failure,
            assertFailsWith<IllegalArgumentException> {
                ServerConfig.fromEnvironment(mapOf("SOFA_LEARNED_BREAKS" to "maybe"))
            }.message,
        )
        for (def in listOf(Settings.LOG_LEVEL, Settings.LEARNED_BREAKS, Settings.HDHR_TUNERS)) {
            val refused = assertFailsWith<ApiFailure> {
                RuntimeValues.from(RuntimeValues.DEFAULT, mapOf(def.name to "17 or loud"))
            }
            assertEquals(def.failure, refused.message)
        }
    }

    @Test fun `the plex sync interval from the environment is kept when plex is not set up`() {
        val config = ServerConfig.fromEnvironment(mapOf("SOFA_PLEX_SYNC_MINUTES" to "15"))
        assertEquals(null, config.plex)
        assertEquals(15, config.plexSyncMinutes)
        assertEquals(15, RuntimeValues.of(config).plexSyncMinutes)
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(mapOf("SOFA_PLEX_SYNC_MINUTES" to "4"))
        }
        val withPlex = ServerConfig.fromEnvironment(
            mapOf(
                "SOFA_PLEX_SYNC_MINUTES" to "15",
                "SOFA_PLEX_URL" to "http://plex.lan:32400",
                "SOFA_PLEX_TOKEN" to "plexTOKEN-123",
            ),
        )
        assertEquals(15, withPlex.plex!!.syncMinutes)
        assertEquals(15, withPlex.plexSyncMinutes)
    }

    @Test fun `every runtime setting accepts its default and starts in the runtime values`() {
        for (def in Settings.runtime) {
            val ok = def.normalize(def.default!!)
            assertEquals(def.default, ok, def.name)
        }
        assertEquals(Settings.runtime.map { it.name }.toSet(), RuntimeValues.DEFAULT.byName().keys)
    }
}
