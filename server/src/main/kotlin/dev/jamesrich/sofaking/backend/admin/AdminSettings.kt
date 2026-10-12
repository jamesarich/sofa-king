package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.SecretOrigin
import dev.jamesrich.sofaking.backend.SecretsFile
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyApplyResult
import dev.jamesrich.sofaking.backend.api.PolicyChangeEntry
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.GuideConfig
import kotlinx.html.*

/** One effective setting: the variable that sets it, its value as the server uses it, and whether that is the default. */
internal class Setting(val name: String, val value: String, val isDefault: Boolean, val note: String? = null)

internal class SettingGroup(val title: String, val settings: List<Setting>)

/** A provider or guide from the source file, described without its URL's path, query, headers' values or options' values. */
internal class SourceSummary(val id: String, val kind: String, val where: String, val refresh: String, val notes: List<String>)

internal class PolicySummary(val path: String, val revision: String, val policy: LineupPolicy, val lastRun: PolicyApplyResult?)

internal class SettingsView(
    val groups: List<SettingGroup>, val providers: List<SourceSummary>, val guides: List<SourceSummary>, val policy: PolicySummary?,
    val changes: List<PolicyChangeEntry>, val changeLimit: Int,
    val apiTokenSet: Boolean = false,
    /** The API token, only on the page answering the reveal action. */
    val revealedApiToken: String? = null,
)

private const val SET = "set"
private const val GENERATED = "generated"
private const val UNSET = "not set"

/** The settings as the server runs with them. Tokens are reported only as set, generated or not set. */
internal fun effectiveSettings(config: ServerConfig): List<SettingGroup> {
    fun s(name: String, value: Any?, default: Any?, note: String? = null) =
        Setting(name, value?.toString()?.takeIf { it.isNotEmpty() } ?: UNSET, value == default || (value is Collection<*> && value.isEmpty() && default == null), note)
    fun secret(name: String, value: String?, note: String): Setting {
        val origin = config.secretOrigins[name]
        val from = when (origin) {
            SecretOrigin.GENERATED -> "Generated and stored in ${config.secretsFile ?: SecretsFile.NAME}. "
            SecretOrigin.FILE -> "Read from the file ${name}_FILE names. "
            SecretOrigin.ENVIRONMENT -> "Set in the environment. "
            null -> ""
        }
        return Setting(name, if (value == null) UNSET else if (origin == SecretOrigin.GENERATED) GENERATED else SET, value == null, from + note)
    }
    fun found(name: String) = if (name in config.foundInDataDirectory) "Found in SOFA_DATA_DIR; $name is unset" else null
    val hdhr = config.hdhr
    val plex = config.plex
    return listOf(
        SettingGroup("Server", listOf(
            s("SOFA_HOST", config.host, "127.0.0.1"),
            s("SOFA_PORT", config.port, 8080),
            s("SOFA_DATA_DIR", config.dataDirectory, java.nio.file.Path.of("data")),
            s("SOFA_SOURCES", config.sourcesFile, null, found("SOFA_SOURCES")),
            s("SOFA_POLICY", config.policyFile, null, found("SOFA_POLICY")),
            s("SOFA_MAX_SESSIONS", config.maxSessions, 4),
            s("SOFA_FFMPEG", config.ffmpeg, "ffmpeg"),
            s("SOFA_PROBE_PARALLELISM", config.probeParallelism, 6),
            s("SOFA_RESTREAM_ORIGINS", config.restreamOrigins.sorted().joinToString(), ""),
            s("SOFA_OBSERVATION_RETENTION_DAYS", config.observationRetentionDays, 7),
            s("SOFA_ALLOW_LOOPBACK_UPSTREAMS", config.allowLoopbackUpstreams, false),
        )),
        SettingGroup("Tokens", listOf(
            secret("SOFA_API_TOKEN", config.apiToken, "Opens /api/v1; the Sofa King app needs it"),
            secret("SOFA_ADMIN_TOKEN", config.adminToken, if (config.adminToken == null) "The API token signs in here and opens the admin API" else "Signs in here and opens the admin API"),
            secret("SOFA_OUTPUT_TOKEN", config.outputs.token, "Players and Plex fetch /output with it; reveal it on the Plex page"),
            secret("SOFA_PLEX_TOKEN", plex?.token, "Sent to Plex only"),
        )),
        SettingGroup("Player outputs", listOf(s("SOFA_XMLTV_DAYS", config.outputs.guideDays, 3))),
        SettingGroup("HDHomeRun", listOf(
            s("SOFA_HDHR_ENABLED", hdhr.enabled, true),
            s("SOFA_HDHR_TUNERS", hdhr.tuners, 2),
            s("SOFA_HDHR_DEVICE_ID", hdhr.deviceId ?: "derived from the server identity", "derived from the server identity"),
            s("SOFA_HDHR_NAME", hdhr.friendlyName, "Sofa King"),
            s("SOFA_HDHR_ALLOW", hdhr.allow.joinToString(), ""),
            s("SOFA_HDHR_EXTRA_PORTS", hdhr.extraPorts.joinToString(), "", "${plural(hdhr.devices, "device")}"),
            s("SOFA_HDHR_DEVICE_SIZE", hdhr.deviceSize, 250),
            s("SOFA_HDHR_PUBLIC_PORTS", hdhr.publicPorts.joinToString(), ""),
        )),
        SettingGroup("Plex sync", listOf(
            s("SOFA_PLEX_URL", plex?.url, null),
            s("SOFA_PLEX_SYNC_MINUTES", plex?.syncMinutes ?: 60, 60),
            s("SOFA_PLEX_TUNER_BASE", plex?.tunerBase, null),
        )),
    )
}

/** The host of [url] only; a path or query can carry a token. */
private fun hostOf(url: String?): String? = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }

internal fun providerSummary(config: FeedConfig): SourceSummary {
    val notes = buildList {
        if (config.headers.isNotEmpty()) add("headers: ${config.headers.keys.sorted().joinToString()}")
        if (config.options.isNotEmpty()) add("options: ${config.options.keys.sorted().joinToString()}")
        if (!config.probe) add("not probed")
        if (config.guideUrl != null) add("guide from ${hostOf(config.guideUrl) ?: "a URL"}")
    }
    val kind = if (config.native) "native: ${config.service}" else "M3U"
    val where = if (config.native) "built-in client" else hostOf(config.playlistUrl) ?: "a URL"
    return SourceSummary(config.id, kind, where, every(config.refreshInterval), notes)
}

internal fun guideSummary(config: GuideConfig): SourceSummary {
    val notes = if (config.headers.isNotEmpty()) listOf("headers: ${config.headers.keys.sorted().joinToString()}") else emptyList()
    val where = config.provider?.let { "provider $it" } ?: hostOf(config.url) ?: "a URL"
    return SourceSummary(config.id, if (config.provider != null) "provider guide" else "XMLTV", where, every(config.refreshSeconds), notes)
}

private fun every(seconds: Long): String = when {
    seconds % 3600 == 0L -> "every ${seconds / 3600} h"
    seconds % 60 == 0L -> "every ${seconds / 60} min"
    else -> "every $seconds s"
}

internal fun settingsPage(chrome: Chrome, view: SettingsView): String = layout("Settings", chrome, "/admin/settings") {
    val now = chrome.now
    pageHead("Settings", "How this server is configured right now: environment variables, the source file and the lineup policy. Change a variable in the server's environment (.env) and restart; edit groups and providers on their pages.")
    panel("Lineup policy", id = "policy") {
        val policy = view.policy
        if (policy == null) { p("muted") { +"No lineup policy. Add policy.json to the data directory, or set SOFA_POLICY to a policy file in a writable directory, and restart; the server then builds the lineup from it." }; return@panel }
        dl("kv") {
            dt { +"File" }; dd { code { +policy.path } }
            dt { +"Revision" }; dd { span("mono") { attributes["title"] = policy.revision; +policy.revision.take(12) } }
            val p = policy.policy
            dt { +"Holds" }
            dd {
                +listOf(
                    plural(p.providers.size, "provider entry", "provider entries"), plural(p.numbering.groups.size, "number block"),
                    plural(p.groups.map.size, "category mapping"), plural(p.groups.weak.size, "weak category", "weak categories"),
                    plural(p.groups.patterns.size, "pattern"), plural(p.stations.size, "station override"),
                    plural(p.names.aliases.size, "alias", "aliases"), plural(p.names.learned.size, "learned alias", "learned aliases"),
                ).joinToString(", ")
            }
            dt { +"Last run" }
            dd {
                val run = policy.lastRun
                if (run == null) +"None since the server started"
                else {
                    ago(run.appliedAt, now)
                    +(": ${thousands(run.enabledStations)} on of ${thousands(run.stations)}, " + if (run.changed) plural(run.changes, "field") + " changed" else "no change")
                }
            }
        }
        div("actions") {
            attributes["style"] = "margin-top:14px"
            postForm("/admin/policy/apply", chrome.session) { button(type = ButtonType.submit, classes = "primary") { +"Apply now" } }
            span("muted small") { +"Runs the policy over the current feeds and writes the lineup. Runs also follow every import and every 15 minutes." }
        }
    }
    div("cols") {
        for (group in view.groups) panel(group.title) {
            div("settings") {
                for (setting in group.settings) div("setting") {
                    div("name mono") { +setting.name }
                    div("val") {
                        when (setting.value) {
                            SET -> chip(SET, Tone.OK)
                            GENERATED -> chip(GENERATED, Tone.OK)
                            UNSET -> span("muted") { +UNSET }
                            else -> +setting.value
                        }
                        if (setting.isDefault && setting.value !in setOf(UNSET, SET, GENERATED)) span("muted small") { +" · default" }
                        if (setting.note != null) div("muted small") { +setting.note }
                    }
                }
                if (group.title == "Tokens" && view.apiTokenSet) div {
                    attributes["id"] = "api-token"
                    attributes["style"] = "margin-top:12px"
                    val token = view.revealedApiToken
                    if (token == null) postForm("/admin/settings/reveal-api-token", chrome.session) { button(type = ButtonType.submit) { +"Reveal the API token" } }
                    else {
                        div("muted small") { +"API token, for the Sofa King app and API clients:" }
                        code { +token }
                    }
                }
            }
        }
    }
    panel("Source file", id = "sources", aside = { span("muted") { +"URL paths, queries and header values are not shown" } }) {
        if (view.providers.isEmpty() && view.guides.isEmpty()) { p("muted") { +"No providers or guides. Add sources.json to the data directory, or set SOFA_SOURCES to a source file, and restart." }; return@panel }
        div("scroll") {
            table {
                thead { tr { th { +"ID" }; th { +"Kind" }; th { +"From" }; th { +"Refresh" }; th { +"Notes" } } }
                tbody {
                    for (source in view.providers + view.guides) tr {
                        th { attributes["scope"] = "row"; +source.id }
                        td { +source.kind }
                        td { +source.where }
                        td("nowrap") { +source.refresh }
                        td("muted small") { +source.notes.joinToString("; ") }
                    }
                }
            }
        }
    }
    if (view.policy != null) panel("Change log", id = "changes", aside = {
        if (view.changeLimit < 1000) a("/admin/settings?changes=${minOf(1000, view.changeLimit * 4)}#changes") { +"Show more" }
    }) {
        if (view.changes.isEmpty()) { p("muted") { +"No policy run has changed the lineup yet." }; return@panel }
        div("scroll") {
            table {
                thead { tr { th { +"When" }; th { +"Station" }; th { +"Field" }; th { +"Before" }; th { +"After" }; th { +"Cause" } } }
                tbody {
                    for (entry in view.changes) tr {
                        val change = entry.change
                        td("nowrap") { ago(entry.appliedAt, now) }
                        td {
                            if (change.entryId != null) a("/admin/lineup/${change.entryId}", classes = "rowlink") { +change.entryName } else +change.entryName
                            change.key?.let { br(); span("muted small mono") { +it } }
                        }
                        td { +(change.field ?: "station") }
                        td("muted") { +(change.before ?: "–") }
                        td { +(change.after ?: "–") }
                        td("muted small") { +change.reason }
                    }
                }
            }
        }
    }
}
