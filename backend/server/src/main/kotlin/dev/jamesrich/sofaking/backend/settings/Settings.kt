package dev.jamesrich.sofaking.backend.settings

import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.admin.plural
import dev.jamesrich.sofaking.backend.iptvorg.IptvOrgSettings
import dev.jamesrich.sofaking.backend.library.HardwareAccel
import dev.jamesrich.sofaking.backend.library.VideoEncoder
import dev.jamesrich.sofaking.backend.logging.DEFAULT_LOG_DAYS
import dev.jamesrich.sofaking.backend.multiview.MultiviewMode
import dev.jamesrich.sofaking.backend.playback.DEFAULT_HISTORY_DAYS

/** What a setting holds, which decides how its text is checked. */
internal sealed interface SettingType {
    /** A whole number within [range]. */
    data class Number(val range: IntRange) : SettingType

    /** One of [values], matched without case. */
    data class Choice(val values: List<String>) : SettingType

    /** `true` or `false`. */
    data object Flag : SettingType

    /** A file or folder path. */
    data object Path : SettingType

    /** A token or key, shown only as set or not set. */
    data object Secret : SettingType

    /** Free text, or a list or map the setting's own reader splits. */
    data object Text : SettingType
}

/** Where the settings page lists a setting. */
internal enum class SettingArea(val title: String) {
    SERVER("Server"),
    LIBRARY("Library and playback"),
    TOKENS("Tokens"),
    HDHR("HDHomeRun"),
    PLEX("Plex sync"),
}

/**
 * One environment variable: how it is checked, its default, what the settings page says about it and how to read
 * its value back from a [ServerConfig]. [runtime] settings can also be saved in the portal and apply without a
 * restart. A [custom] setting has a dedicated reader for its shape instead of the generic one for its type; it is
 * registered so the page and the docs cannot leave it out.
 */
internal class SettingDef(
    val name: String,
    val type: SettingType,
    /** The value when the variable is unset; null when unset means off. */
    val default: String?,
    val label: String,
    val help: String,
    val area: SettingArea,
    val runtime: Boolean = false,
    val custom: Boolean = false,
    private val rule: String? = null,
    val show: (ServerConfig) -> Any?,
    /** The default as [show] would give it, when it depends on other settings. */
    private val usualValue: ((ServerConfig) -> Any?)? = null,
    val note: ((ServerConfig) -> String?)? = null,
    /** The form a [SettingType.Text] value is kept in, or null when it does not fit. */
    private val clean: ((String) -> String?)? = null,
) {
    /** The range of a [SettingType.Number] setting. */
    val range: IntRange get() = (type as SettingType.Number).range

    fun usual(config: ServerConfig): Any? = usualValue?.invoke(config) ?: default

    /** [raw] in the form the setting is kept in, or null when it does not fit. */
    fun normalize(raw: String): String? = when (type) {
        is SettingType.Number -> raw.trim().toIntOrNull()?.takeIf { it in type.range }?.toString()
        is SettingType.Choice -> raw.trim().lowercase().takeIf { it in type.values }
        else -> clean?.invoke(raw) ?: raw.takeIf { clean == null }
    }

    /** What to tell the operator when a value does not fit. */
    val failure: String
        get() = rule ?: when (type) {
            is SettingType.Number -> "$name must be between ${type.range.first} and ${type.range.last}"
            is SettingType.Choice -> "$name must be one of ${type.values.joinToString()}"
            SettingType.Flag -> "$name must be true or false"
            SettingType.Path -> "$name must not be blank"
            SettingType.Secret -> "$name must contain at least ${ServerConfig.TOKEN_MIN_LENGTH} characters"
            SettingType.Text -> "$name is not valid"
        }
}

/**
 * Every environment variable the server reads, once. The startup reader, the runtime settings, the portal's runtime
 * form and its list of startup settings all take their names, ranges, defaults and help from here. Adding a runtime
 * setting is an entry here, the code that uses its value and a row in the server settings table of `live-tv.md`.
 */
internal object Settings {
    private fun number(
        name: String,
        default: Int,
        range: IntRange,
        label: String,
        help: String,
        area: SettingArea = SettingArea.SERVER,
        runtime: Boolean = false,
        custom: Boolean = false,
        rule: String? = null,
        show: (ServerConfig) -> Any?,
    ) = SettingDef(
        name, SettingType.Number(range), default.toString(), label, help, area, runtime, custom, rule, show,
    )

    private fun flag(
        name: String,
        default: Boolean,
        label: String,
        help: String,
        area: SettingArea = SettingArea.SERVER,
        usual: ((ServerConfig) -> Any?)? = null,
        show: (ServerConfig) -> Any?,
    ) = SettingDef(
        name,
        SettingType.Flag,
        default.toString(),
        label,
        help,
        area,
        usualValue = usual,
        show = show,
    )

    private fun text(
        name: String,
        default: String?,
        label: String,
        help: String,
        area: SettingArea = SettingArea.SERVER,
        custom: Boolean = false,
        usual: ((ServerConfig) -> Any?)? = null,
        note: ((ServerConfig) -> String?)? = null,
        runtime: Boolean = false,
        rule: String? = null,
        clean: ((String) -> String?)? = null,
        show: (ServerConfig) -> Any?,
    ) = SettingDef(
        name, SettingType.Text, default, label, help, area,
        runtime = runtime, custom = custom, rule = rule, usualValue = usual, note = note, clean = clean, show = show,
    )

    private fun path(
        name: String,
        label: String,
        help: String,
        usual: ((ServerConfig) -> Any?)? = null,
        show: (ServerConfig) -> Any?,
    ) = SettingDef(
        name,
        SettingType.Path,
        null,
        label,
        help,
        SettingArea.SERVER,
        usualValue = usual,
        show = show,
    )

    private fun choice(
        name: String,
        default: String,
        values: List<String>,
        label: String,
        help: String,
        area: SettingArea = SettingArea.SERVER,
        runtime: Boolean = false,
        custom: Boolean = false,
        show: (ServerConfig) -> Any?,
    ) = SettingDef(
        name, SettingType.Choice(values), default, label, help, area, runtime, custom, show = show,
    )

    private fun secret(
        name: String,
        label: String,
        help: String,
        area: SettingArea = SettingArea.TOKENS,
        note: ((ServerConfig) -> String?)? = null,
        show: (ServerConfig) -> Any?,
    ) = SettingDef(name, SettingType.Secret, null, label, help, area, custom = true, note = note, show = show)

    val LOG_LEVEL = choice(
        "SOFA_LOG_LEVEL",
        "info",
        listOf("trace", "debug", "info", "warn", "error", "off"),
        "Log level",
        "Log level on stderr. The Events page keeps INFO and above whatever it is.",
        runtime = true,
    ) { it.logging.level }

    val XMLTV_DAYS = number(
        "SOFA_XMLTV_DAYS",
        ServerConfig.DEFAULT_GUIDE_DAYS,
        1..14,
        "XMLTV days ahead",
        "Days ahead the XMLTV output carries.",
        runtime = true,
    ) { it.outputs.guideDays }

    val PLEX_SYNC_MINUTES = number(
        "SOFA_PLEX_SYNC_MINUTES",
        ServerConfig.DEFAULT_PLEX_SYNC_MINUTES,
        5..1440,
        "Plex sync every (minutes)",
        "Minutes between Plex syncs when the lineup does not change. It applies once Plex sync is set up.",
        SettingArea.PLEX,
        runtime = true,
    ) { it.plexSyncMinutes }

    val HDHR_TUNERS = number(
        "SOFA_HDHR_TUNERS",
        ServerConfig.DEFAULT_TUNERS,
        1..MAX_SESSIONS_LIMIT,
        "Streams per HDHomeRun device",
        "Streams each HDHomeRun device may open at once, at most the sessions at once. Plex reads the count when it " +
            "adds the device, so a change shows in Plex after the device is re-added; the server enforces it at once.",
        SettingArea.HDHR,
        runtime = true,
        custom = true,
    ) { it.hdhr.tuners }

    val MAX_SESSIONS = number(
        "SOFA_MAX_SESSIONS",
        ServerConfig.DEFAULT_MAX_SESSIONS,
        1..MAX_SESSIONS_LIMIT,
        "Sessions at once",
        "Playback sessions the server holds at once.",
        runtime = true,
    ) { it.maxSessions }

    val RECORDINGS_FREE_GIB = number(
        "SOFA_RECORDINGS_FREE_GIB",
        ServerConfig.DEFAULT_RECORDINGS_FREE_GIB,
        0..100_000,
        "Recordings: keep free (GiB)",
        "Space the DVR keeps free on the recordings disk by deleting the oldest recordings.",
        SettingArea.LIBRARY,
        runtime = true,
    ) { it.recordingsFreeGib }

    val RECORDINGS_KEEP_DAYS = number(
        "SOFA_RECORDINGS_KEEP_DAYS",
        0,
        0..3650,
        "Recordings: keep for (days, 0 for no limit)",
        "Days the DVR keeps a recording; 0 keeps it until space runs short.",
        SettingArea.LIBRARY,
        runtime = true,
    ) { it.recordingsKeepDays }

    val LEARNED_BREAKS = choice(
        "SOFA_LEARNED_BREAKS",
        LearnedBreaks.SHADOW.setting,
        LearnedBreaks.entries.map { it.setting },
        "Learned ad breaks",
        "Breaks the server finds in a stream with no ad markers, such as a long silent slate: shadow only logs them " +
            "(ADDETECT), on also shows the break screen over them.",
        SettingArea.LIBRARY,
        runtime = true,
    ) { it.learnedBreaks.setting }

    val AD_LISTENERS = number(
        "SOFA_AD_LISTENERS",
        ServerConfig.DEFAULT_AD_LISTENERS,
        0..24,
        "Ad listener: channels at once (0 for off)",
        "Channels the ad listener plays at once, headless, while nobody watches them, so the server learns their " +
            "ads. At most 2 play while anyone watches.",
        SettingArea.LIBRARY,
        runtime = true,
    ) { it.adListeners }

    val IPTV_ORG = choice(
        "SOFA_IPTV_ORG",
        IptvOrgSettings.OFF,
        listOf(IptvOrgSettings.OFF, IptvOrgSettings.ON),
        "iptv-org metadata",
        "on downloads the iptv-org channel database weekly (names, logos, categories, blocklist; never streams) to " +
            "match channels, fill missing logos and categories and hold blocklisted channels out.",
        runtime = true,
    ) { if (it.iptvOrg.metadata) IptvOrgSettings.ON else IptvOrgSettings.OFF }

    val IPTV_ORG_STREAMS = choice(
        "SOFA_IPTV_ORG_STREAMS",
        IptvOrgSettings.OFF,
        listOf(IptvOrgSettings.OFF, IptvOrgSettings.ON),
        "iptv-org streams",
        "on adds the iptv-org community streams of the region below as a provider. They are often dead or " +
            "geo-blocked; the source probes drop the dead ones.",
        runtime = true,
    ) { if (it.iptvOrg.streams) IptvOrgSettings.ON else IptvOrgSettings.OFF }

    val IPTV_ORG_REGION = text(
        "SOFA_IPTV_ORG_REGION",
        "",
        "iptv-org region",
        "Two-letter country codes, comma separated, whose streams are imported. Empty uses the server's time " +
            "zone, else US.",
        runtime = true,
        rule = "SOFA_IPTV_ORG_REGION must be up to 20 two-letter country codes separated by commas",
        clean = IptvOrgSettings::cleanRegion,
    ) { it.iptvOrg.region }

    val HOST = text("SOFA_HOST", "127.0.0.1", "Host", "Listen address.", custom = true) { it.host }

    val PORT = number("SOFA_PORT", ServerConfig.DEFAULT_PORT, 1..65_535, "Port", "Listen port.") { it.port }

    val MDNS = flag(
        "SOFA_MDNS",
        false,
        "mDNS",
        "Advertise the server on the LAN over mDNS, so apps find it.",
        usual = { !ServerConfig.isLoopback(it.host) },
    ) { it.mdns }

    val DATA_DIR = text(
        "SOFA_DATA_DIR",
        "data",
        "Data directory",
        "Directory for the database, the logo cache and secrets.json.",
        custom = true,
    ) { it.dataDirectory }

    val RECORDINGS_DIR = path(
        "SOFA_RECORDINGS_DIR",
        "Recordings folder",
        "Folder the recordings are kept in.",
        usual = { it.dataDirectory.resolve("recordings") },
    ) { it.recordingsDirectory }

    val SOURCES = path(
        "SOFA_SOURCES",
        "Source file",
        "Path to the source configuration file.",
    ) { it.sourcesFile }

    val POLICY = path("SOFA_POLICY", "Lineup policy file", "Path to the lineup policy file.") { it.policyFile }

    val API_TOKEN = secret(
        "SOFA_API_TOKEN",
        "API token",
        "Opens /api/v1; the Sofa King app needs it",
    ) { it.apiToken }

    val ADMIN_TOKEN = secret(
        "SOFA_ADMIN_TOKEN",
        "Admin token",
        "Signs in here and opens the admin API",
        note = { if (it.adminToken == null) "The API token signs in here and opens the admin API" else null },
    ) { it.adminToken }

    val OUTPUT_TOKEN = secret(
        "SOFA_OUTPUT_TOKEN",
        "Output token",
        "Players and Plex fetch /output with it; reveal it on the Plex page",
    ) { it.outputs.token }

    val PLEX_TOKEN = secret(
        "SOFA_PLEX_TOKEN",
        "Plex token",
        "Sent to Plex only",
    ) { it.plex?.token }

    val TMDB_TOKEN = secret(
        "SOFA_TMDB_TOKEN",
        "TMDB token",
        "Sent to TMDB only, for library details and artwork",
    ) { it.tmdb?.token }

    val FANART_KEY = secret(
        "SOFA_FANART_KEY",
        "fanart.tv key",
        "Sent to fanart.tv only, for music artwork",
    ) { it.fanartKey }

    val LISTENBRAINZ_TOKEN = secret(
        "SOFA_LISTENBRAINZ_TOKEN",
        "ListenBrainz token",
        "Sent to ListenBrainz only, for artists' most played songs",
    ) { it.listenBrainzToken }

    val FFMPEG = text("SOFA_FFMPEG", "ffmpeg", "FFmpeg", "FFmpeg executable.") { it.ffmpeg }

    val COMSKIP = text(
        "SOFA_COMSKIP",
        "comskip",
        "Comskip",
        "Comskip executable; none turns the commercial scan off.",
        custom = true,
    ) { it.comskip ?: "none" }

    val QUALITY_FLOORS = flag(
        "SOFA_QUALITY_FLOORS",
        false,
        "Quality floors",
        "Also move a remuxed stream to the next source on a black picture or too low a rate.",
    ) { it.qualityFloors }

    val MUSICBRAINZ = flag(
        "SOFA_MUSICBRAINZ",
        true,
        "MusicBrainz",
        "Look Music libraries up on MusicBrainz after scans.",
    ) { it.musicBrainz }

    val PROBE_PARALLELISM = number(
        "SOFA_PROBE_PARALLELISM",
        ServerConfig.DEFAULT_PROBE_PARALLELISM,
        1..32,
        "Probe parallelism",
        "Concurrent source probes.",
    ) { it.probeParallelism }

    val RESTREAM_ORIGINS = text(
        "SOFA_RESTREAM_ORIGINS",
        "",
        "Restream origins",
        "LAN restream proxies whose 502 counts as source evidence.",
        custom = true,
    ) { it.restreamOrigins.sorted().joinToString() }

    val OBSERVATION_RETENTION_DAYS = number(
        "SOFA_OBSERVATION_RETENTION_DAYS",
        ServerConfig.DEFAULT_RETENTION_DAYS,
        1..3650,
        "Observation retention",
        "Days of health observation history to keep.",
    ) { it.observationRetentionDays }

    val HISTORY_DAYS = number(
        "SOFA_HISTORY_DAYS",
        DEFAULT_HISTORY_DAYS,
        1..3650,
        "History days",
        "Days of playback history to keep.",
    ) { it.historyDays }

    val ALLOW_LOOPBACK_UPSTREAMS = flag(
        "SOFA_ALLOW_LOOPBACK_UPSTREAMS",
        false,
        "Loopback upstreams",
        "Allow playlist, guide and stream URLs on loopback; for local fixtures only.",
    ) { it.allowLoopbackUpstreams }

    val LOG_FORMAT = choice(
        "SOFA_LOG_FORMAT",
        "text",
        listOf("text", "json"),
        "Log format",
        "Text lines or one JSON object per line.",
    ) { it.logging.format }

    val LOG_DAYS = number(
        "SOFA_LOG_DAYS",
        DEFAULT_LOG_DAYS,
        0..365,
        "Log days",
        "Days of log files kept; 0 writes no file.",
        rule = "SOFA_LOG_DAYS must be a whole number from 0 to 365",
    ) { it.logging.days }

    val CORS_ORIGINS = text(
        "SOFA_CORS_ORIGINS",
        "",
        "CORS origins",
        "Browser origins that may call the API from another origin.",
        custom = true,
    ) { it.corsOrigins.joinToString() }

    val MEDIA_DIR = path(
        "SOFA_MEDIA_DIR",
        "Media folder",
        "The folder media libraries are added from, read only.",
        usual = { it.dataDirectory.resolve("media") },
    ) { it.mediaDirectory }

    val WEB_DIR = path("SOFA_WEB_DIR", "Web app folder", "The web app's build, served at /app/.") { it.webDirectory }

    val LIBRARY_PATH_MAP = text(
        "SOFA_LIBRARY_PATH_MAP",
        "",
        "Library path map",
        "How Sonarr, Radarr and the like name the media folders, as from=to pairs.",
        custom = true,
    ) { config -> config.libraryPathMap.joinToString { "${it.from}=${it.to}" } }

    val TMDB_LANGUAGE = text(
        "SOFA_TMDB_LANGUAGE",
        "en-US",
        "TMDB language",
        "The language TMDB answers in.",
        custom = true,
    ) { it.tmdb?.language ?: "en-US" }

    val MAX_TRANSCODES = number(
        "SOFA_MAX_TRANSCODES",
        ServerConfig.DEFAULT_MAX_TRANSCODES,
        1..8,
        "Transcodes at once",
        "Library files FFmpeg converts or remuxes at once; direct play is not counted.",
        SettingArea.LIBRARY,
    ) { it.maxTranscodes }

    val HWACCEL = choice(
        "SOFA_HWACCEL",
        HardwareAccel.NONE.setting,
        HardwareAccel.entries.map { it.setting },
        "GPU encoder",
        "The GPU encoder library files are converted with; none is software.",
        SettingArea.LIBRARY,
        custom = true,
    ) { it.videoEncoder.accel.setting }

    val HWACCEL_DEVICE = text(
        "SOFA_HWACCEL_DEVICE",
        VideoEncoder.DEFAULT_DEVICE,
        "GPU render node",
        "The render node vaapi and qsv open.",
        SettingArea.LIBRARY,
        custom = true,
    ) { it.videoEncoder.device }

    val MULTIVIEW = choice(
        "SOFA_MULTIVIEW",
        MultiviewMode.AUTO.setting,
        MultiviewMode.entries.map { it.setting },
        "Multiview",
        "Whether the server draws multiview: auto only with a working GPU encoder, on in software too, off never.",
        SettingArea.LIBRARY,
        custom = true,
    ) { it.multiview.setting }

    val HDHR_ENABLED = flag(
        "SOFA_HDHR_ENABLED",
        true,
        "HDHomeRun emulation",
        "Serve the HDHomeRun emulation at the root.",
        SettingArea.HDHR,
    ) { it.hdhr.enabled }

    val HDHR_DEVICE_ID = text(
        "SOFA_HDHR_DEVICE_ID",
        DERIVED_ID,
        "Device ID",
        "Eight hex digits Plex identifies the tuner by.",
        SettingArea.HDHR,
        custom = true,
    ) { it.hdhr.deviceId ?: DERIVED_ID }

    val HDHR_NAME = text(
        "SOFA_HDHR_NAME",
        "Sofa King",
        "Device name",
        "Friendly name Plex shows.",
        SettingArea.HDHR,
        custom = true,
    ) { it.hdhr.friendlyName }

    val HDHR_ALLOW = text(
        "SOFA_HDHR_ALLOW",
        "",
        "Allowed clients",
        "Client IPs or CIDR blocks admitted to the HDHomeRun routes.",
        SettingArea.HDHR,
        custom = true,
    ) { it.hdhr.allow.joinToString() }

    val HDHR_EXTRA_PORTS = text(
        "SOFA_HDHR_EXTRA_PORTS",
        "",
        "Extra ports",
        "Further ports, each one more HDHomeRun device.",
        SettingArea.HDHR,
        custom = true,
        note = { plural(it.hdhr.devices, "device") },
    ) { it.hdhr.extraPorts.joinToString() }

    val HDHR_DEVICE_SIZE = number(
        "SOFA_HDHR_DEVICE_SIZE",
        250,
        1..5000,
        "Channels per device",
        "With a lineup policy, channels per HDHomeRun device.",
        SettingArea.HDHR,
    ) { it.hdhr.deviceSize }

    val HDHR_PUBLIC_PORTS = text(
        "SOFA_HDHR_PUBLIC_PORTS",
        "",
        "Public ports",
        "The host ports Plex reaches each device on.",
        SettingArea.HDHR,
        custom = true,
    ) { it.hdhr.publicPorts.joinToString() }

    val PLEX_URL = text(
        "SOFA_PLEX_URL",
        null,
        "Plex URL",
        "The Plex server to keep in step with the lineup.",
        SettingArea.PLEX,
        custom = true,
    ) { it.plex?.url }

    val PLEX_TUNER_BASE = text(
        "SOFA_PLEX_TUNER_BASE",
        null,
        "Plex tuner base",
        "Scheme and host Plex reaches this server's tuners on.",
        SettingArea.PLEX,
        custom = true,
    ) { it.plex?.tunerBase }

    val REVISION = text(
        "SOFA_REVISION",
        UNKNOWN,
        "Revision",
        "The commit the server was built from.",
    ) { it.revision }

    val VERSION = text(
        "SOFA_VERSION",
        UNKNOWN,
        "Version",
        "The product version the server was built from.",
    ) { it.version }

    val UPDATE_IMAGE = text(
        "SOFA_UPDATE_IMAGE",
        ServerConfig.DEFAULT_UPDATE_IMAGE,
        "Update image",
        "The published image the update check reads; empty turns the check off.",
        custom = true,
    ) { it.updateImage }

    val all: List<SettingDef> = listOf(
        LOG_LEVEL, XMLTV_DAYS, PLEX_SYNC_MINUTES, HDHR_TUNERS, MAX_SESSIONS, RECORDINGS_FREE_GIB,
        RECORDINGS_KEEP_DAYS, LEARNED_BREAKS, AD_LISTENERS, IPTV_ORG, IPTV_ORG_STREAMS, IPTV_ORG_REGION, HOST, PORT,
        MDNS, DATA_DIR, RECORDINGS_DIR, SOURCES,
        POLICY, API_TOKEN, ADMIN_TOKEN, OUTPUT_TOKEN, PLEX_TOKEN, TMDB_TOKEN, FANART_KEY, LISTENBRAINZ_TOKEN, FFMPEG,
        COMSKIP, QUALITY_FLOORS, MUSICBRAINZ, PROBE_PARALLELISM, RESTREAM_ORIGINS, OBSERVATION_RETENTION_DAYS,
        HISTORY_DAYS, ALLOW_LOOPBACK_UPSTREAMS, LOG_FORMAT, LOG_DAYS, CORS_ORIGINS, MEDIA_DIR, WEB_DIR,
        LIBRARY_PATH_MAP, TMDB_LANGUAGE, MAX_TRANSCODES, HWACCEL, HWACCEL_DEVICE, MULTIVIEW, HDHR_ENABLED,
        HDHR_DEVICE_ID, HDHR_NAME, HDHR_ALLOW, HDHR_EXTRA_PORTS, HDHR_DEVICE_SIZE, HDHR_PUBLIC_PORTS, PLEX_URL,
        PLEX_TUNER_BASE, REVISION, VERSION, UPDATE_IMAGE,
    )

    /** The settings that can be saved in the portal, in the order the form lists them. */
    val runtime: List<SettingDef> = all.filter { it.runtime }

    val byName: Map<String, SettingDef> = all.associateBy { it.name }

    private const val MAX_SESSIONS_LIMIT = 16
    private const val DERIVED_ID = "derived from the server identity"
    private const val UNKNOWN = "unknown"
}
