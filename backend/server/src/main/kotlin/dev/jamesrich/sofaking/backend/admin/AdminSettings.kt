package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.SecretOrigin
import dev.jamesrich.sofaking.backend.SecretsFile
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyApplyResult
import dev.jamesrich.sofaking.backend.api.PolicyChangeEntry
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.GuideConfig
import dev.jamesrich.sofaking.backend.logging.LogSettings
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import dev.jamesrich.sofaking.backend.settings.RuntimeValues
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.header
import kotlinx.html.hiddenInput
import kotlinx.html.html
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.map
import kotlinx.html.numberInput
import kotlinx.html.option
import kotlinx.html.output
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.small
import kotlinx.html.source
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.time
import kotlinx.html.title
import kotlinx.html.tr

/**
 * One effective setting: the variable that sets it, its value as the server uses it, and whether that is the default.
 */
internal class Setting(val name: String, val value: String, val isDefault: Boolean, val note: String? = null)

internal class SettingGroup(val title: String, val settings: List<Setting>)

/**
 * A provider or guide from the source file, described without its URL's path, query,
 * headers' values or options' values.
 */
internal class SourceSummary(
    val id: String,
    val kind: String,
    val where: String,
    val refresh: String,
    val notes: List<String>,
)

internal class PolicySummary(
    val path: String,
    val revision: String,
    val policy: LineupPolicy,
    val lastRun: PolicyApplyResult?,
)

/**
 * The runtime settings as the page edits them: [current] is what the server uses, [environment] what it started with,
 * [saved] the names whose value comes from the database. [plex] says whether Plex sync is configured, so the interval
 * reads as applying to it or as waiting for it. [errors] are a refused save's messages, and [proposed] its values.
 */
internal class RuntimeSettingsView(
    val current: RuntimeValues,
    val environment: RuntimeValues,
    val saved: Set<String>,
    val plex: Boolean,
    val errors: List<String> = emptyList(),
    val proposed: Map<String, String>? = null,
)

internal class SettingsView(
    val groups: List<SettingGroup>,
    val policy: PolicySummary?,
    val changes: List<PolicyChangeEntry>,
    val changeLimit: Int,
    val apiTokenSet: Boolean = false,
    /** The API token, only on the page answering the reveal action. */
    val revealedApiToken: String? = null,
    val runtime: RuntimeSettingsView? = null,
    /**
     * Whether the Advanced section starts open: after an action inside it, or when the page is asked for `advanced=1`.
     */
    val openAdvanced: Boolean = false,
) {
    constructor(
        view: SettingsView,
        runtime: RuntimeSettingsView?,
    ) : this(
        view.groups,
        view.policy,
        view.changes,
        view.changeLimit,
        view.apiTokenSet,
        view.revealedApiToken,
        runtime,
        view.openAdvanced,
    )
}

private const val SET = "set"
private const val GENERATED = "generated"
private const val UNSET = "not set"

/** The settings as the server runs with them. Tokens are reported only as set, generated or not set. */
internal fun effectiveSettings(config: ServerConfig): List<SettingGroup> {
    fun s(name: String, value: Any?, default: Any?, note: String? = null) = Setting(
        name,
        value?.toString()?.takeIf { it.isNotEmpty() } ?: UNSET,
        value == default || (value is Collection<*> && value.isEmpty() && default == null),
        note,
    )
    fun secret(name: String, value: String?, note: String): Setting {
        val origin = config.secretOrigins[name]
        val from = when (origin) {
            SecretOrigin.GENERATED -> "Generated and stored in ${config.secretsFile ?: SecretsFile.NAME}. "

            SecretOrigin.FILE -> "Read from the file ${name}_FILE names. "

            SecretOrigin.ENVIRONMENT -> "Set in the environment. "

            SecretOrigin.SAVED ->
                "Saved from the portal in ${config.secretsFile ?: config.dataDirectory.resolve(SecretsFile.NAME)}. "

            null -> ""
        }
        return Setting(
            name,
            if (value == null) {
                UNSET
            } else if (origin == SecretOrigin.GENERATED) {
                GENERATED
            } else {
                SET
            },
            value == null,
            from + note,
        )
    }
    fun found(name: String) = if (name in
        config.foundInDataDirectory
    ) {
        "Found in SOFA_DATA_DIR; $name is unset"
    } else {
        null
    }
    val hdhr = config.hdhr
    val plex = config.plex
    return listOf(
        SettingGroup(
            "Server",
            listOf(
                s("SOFA_HOST", config.host, "127.0.0.1"),
                s("SOFA_PORT", config.port, 8080),
                s("SOFA_MDNS", config.mdns, !ServerConfig.isLoopback(config.host)),
                s("SOFA_DATA_DIR", config.dataDirectory, java.nio.file.Path.of("data")),
                s("SOFA_RECORDINGS_DIR", config.recordingsDirectory, config.dataDirectory.resolve("recordings")),
                s("SOFA_SOURCES", config.sourcesFile, null, found("SOFA_SOURCES")),
                s("SOFA_POLICY", config.policyFile, null, found("SOFA_POLICY")),
                s("SOFA_FFMPEG", config.ffmpeg, "ffmpeg"),
                s("SOFA_COMSKIP", config.comskip ?: "none", "comskip"),
                s("SOFA_PROBE_PARALLELISM", config.probeParallelism, 6),
                s("SOFA_RESTREAM_ORIGINS", config.restreamOrigins.sorted().joinToString(), ""),
                s("SOFA_OBSERVATION_RETENTION_DAYS", config.observationRetentionDays, 7),
                s("SOFA_ALLOW_LOOPBACK_UPSTREAMS", config.allowLoopbackUpstreams, false),
                s("SOFA_LOG_FORMAT", config.logging.format, "text"),
                s("SOFA_CORS_ORIGINS", config.corsOrigins.joinToString(), ""),
                s("SOFA_MEDIA_DIR", config.mediaDirectory, config.dataDirectory.resolve("media")),
                s("SOFA_TMDB_LANGUAGE", config.tmdb?.language ?: "en-US", "en-US"),
            ),
        ),
        SettingGroup(
            "Tokens",
            listOf(
                secret("SOFA_API_TOKEN", config.apiToken, "Opens /api/v1; the Sofa King app needs it"),
                secret(
                    "SOFA_ADMIN_TOKEN",
                    config.adminToken,
                    if (config.adminToken == null) {
                        "The API token signs in here and opens the admin API"
                    } else {
                        "Signs in here and opens the admin API"
                    },
                ),
                secret(
                    "SOFA_OUTPUT_TOKEN",
                    config.outputs.token,
                    "Players and Plex fetch /output with it; reveal it on the Plex page",
                ),
                secret("SOFA_PLEX_TOKEN", plex?.token, "Sent to Plex only"),
                secret("SOFA_TMDB_TOKEN", config.tmdb?.token, "Sent to TMDB only, for library details and artwork"),
            ),
        ),
        SettingGroup(
            "HDHomeRun",
            listOf(
                s("SOFA_HDHR_ENABLED", hdhr.enabled, true),
                s(
                    "SOFA_HDHR_DEVICE_ID",
                    hdhr.deviceId ?: "derived from the server identity",
                    "derived from the server identity",
                ),
                s("SOFA_HDHR_NAME", hdhr.friendlyName, "Sofa King"),
                s("SOFA_HDHR_ALLOW", hdhr.allow.joinToString(), ""),
                s("SOFA_HDHR_EXTRA_PORTS", hdhr.extraPorts.joinToString(), "", "${plural(hdhr.devices, "device")}"),
                s("SOFA_HDHR_DEVICE_SIZE", hdhr.deviceSize, 250),
                s("SOFA_HDHR_PUBLIC_PORTS", hdhr.publicPorts.joinToString(), ""),
            ),
        ),
        SettingGroup(
            "Plex sync",
            listOf(
                s("SOFA_PLEX_URL", plex?.url, null),
                s("SOFA_PLEX_TUNER_BASE", plex?.tunerBase, null),
            ),
        ),
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
    val notes = if (config.headers.isNotEmpty()) {
        listOf(
            "headers: ${config.headers.keys.sorted().joinToString()}",
        )
    } else {
        emptyList()
    }
    val where = config.provider?.let { "provider $it" } ?: hostOf(config.url) ?: "a URL"
    return SourceSummary(
        config.id,
        if (config.provider != null) {
            "provider guide"
        } else {
            "XMLTV"
        },
        where,
        every(config.refreshSeconds),
        notes,
    )
}

private fun every(seconds: Long): String = when {
    seconds % 3600 == 0L -> "every ${seconds / 3600} h"
    seconds % 60 == 0L -> "every ${seconds / 60} min"
    else -> "every $seconds s"
}

/**
 * The Save control of the runtime settings form: "No changes" and disabled while the fields match what the server uses.
 */
internal fun FlowContent.runtimeSave(changed: Boolean) = div("actions") {
    id = "runtime-save"
    button(type = ButtonType.submit, classes = "primary") {
        name = "op"
        value = "save"
        disabled = !changed
        +(if (changed) "Save" else "No changes")
    }
    button(type = ButtonType.submit, classes = "quiet") {
        name = "op"
        value = "reset"
        +"Back to the environment's values"
    }
}

/** The Save control alone, as the answer to the form's check request. */
internal fun runtimeSaveFragment(changed: Boolean): String =
    kotlinx.html.stream.createHTML(prettyPrint = false).div { runtimeSave(changed) }

/** Whether [proposed], the form's values by name, differs from what the server uses. */
internal fun runtimeChanged(current: RuntimeValues, proposed: Map<String, String>): Boolean =
    current.byName().any { (name, value) -> proposed[name]?.trim()?.lowercase() != value }

private fun FlowContent.runtimePanel(chrome: Chrome, view: RuntimeSettingsView) =
    panel("Runtime settings", id = "runtime") {
        val values = view.proposed ?: view.current.byName()
        p("muted small") {
            +(
                "These apply as soon as they are saved, survive restarts and take precedence over the environment. " +
                    "Saving the environment's own value puts the setting back to following the environment."
                )
        }
        if (view.errors.isNotEmpty()) notice("Not saved:", view.errors)
        form(action = "/admin/settings/runtime", method = FormMethod.post, classes = "stack") {
            id = "runtime-form"
            // With JavaScript, every change asks the server whether the form differs from what runs;
            // without it the button is Save.
            attributes["hx-post"] = "/admin/settings/runtime/check"
            attributes["hx-trigger"] = "change, load"
            attributes["hx-target"] = "#runtime-save"
            attributes["hx-select"] = "#runtime-save"
            attributes["hx-swap"] = "outerHTML"
            hiddenInput(name = "csrf") { value = chrome.session.csrf }
            div("runtime") {
                fun DIV.hint(name: String, environment: String) = span("hint") {
                    +(
                        if (name in
                            view.saved
                        ) {
                            "Saved here; the environment has $environment."
                        } else {
                            "From the environment."
                        }
                        )
                }
                div("field") {
                    label {
                        htmlFor = "r-level"
                        +"Log level"
                    }
                    select {
                        id = "r-level"
                        name = RuntimeValues.LOG_LEVEL
                        for (level in LogSettings.LEVELS) {
                            option {
                                value = level
                                selected =
                                    level == values[RuntimeValues.LOG_LEVEL]
                                +level
                            }
                        }
                    }
                    hint(RuntimeValues.LOG_LEVEL, view.environment.logLevel)
                }
                div("field") {
                    label {
                        htmlFor = "r-days"
                        +"XMLTV days ahead"
                    }
                    numberInput(name = RuntimeValues.GUIDE_DAYS) {
                        id = "r-days"
                        value =
                            values.getValue(RuntimeValues.GUIDE_DAYS)
                        min = "1"
                        max = "14"
                        required = true
                    }
                    hint(RuntimeValues.GUIDE_DAYS, view.environment.guideDays.toString())
                }
                div("field") {
                    label {
                        htmlFor = "r-plex"
                        +"Plex sync every (minutes)"
                    }
                    numberInput(name = RuntimeValues.PLEX_SYNC_MINUTES) {
                        id = "r-plex"
                        value =
                            values.getValue(RuntimeValues.PLEX_SYNC_MINUTES)
                        min = "5"
                        max = "1440"
                        required = true
                    }
                    if (view.plex) {
                        hint(RuntimeValues.PLEX_SYNC_MINUTES, view.environment.plexSyncMinutes.toString())
                    } else {
                        span("hint") {
                            +"Plex sync is not set up; this applies once SOFA_PLEX_URL and SOFA_PLEX_TOKEN are."
                        }
                    }
                }
                div("field") {
                    label {
                        htmlFor = "r-tuners"
                        +"Streams per HDHomeRun device"
                    }
                    numberInput(name = RuntimeValues.HDHR_TUNERS) {
                        id = "r-tuners"
                        value =
                            values.getValue(RuntimeValues.HDHR_TUNERS)
                        min = "1"
                        max = "16"
                        required = true
                    }
                    hint(RuntimeValues.HDHR_TUNERS, view.environment.hdhrTuners.toString())
                }
                div("field") {
                    label {
                        htmlFor = "r-sessions"
                        +"Sessions at once"
                    }
                    numberInput(name = RuntimeValues.MAX_SESSIONS) {
                        id = "r-sessions"
                        value =
                            values.getValue(RuntimeValues.MAX_SESSIONS)
                        min = "1"
                        max = "16"
                        required = true
                    }
                    hint(RuntimeValues.MAX_SESSIONS, view.environment.maxSessions.toString())
                }
                div("field") {
                    label {
                        htmlFor = "r-free"
                        +"Recordings: keep free (GiB)"
                    }
                    numberInput(name = RuntimeValues.RECORDINGS_FREE_GIB) {
                        id = "r-free"
                        value =
                            values.getValue(RuntimeValues.RECORDINGS_FREE_GIB)
                        min = "0"
                        max = "100000"
                        required = true
                    }
                    hint(RuntimeValues.RECORDINGS_FREE_GIB, view.environment.recordingsFreeGib.toString())
                }
                div("field") {
                    label {
                        htmlFor = "r-keep"
                        +"Recordings: keep for (days, 0 for no limit)"
                    }
                    numberInput(name = RuntimeValues.RECORDINGS_KEEP_DAYS) {
                        id = "r-keep"
                        value =
                            values.getValue(RuntimeValues.RECORDINGS_KEEP_DAYS)
                        min = "0"
                        max = "3650"
                        required = true
                    }
                    hint(RuntimeValues.RECORDINGS_KEEP_DAYS, view.environment.recordingsKeepDays.toString())
                }
                div("field") {
                    label {
                        htmlFor = "r-learned"
                        +"Learned ad breaks"
                    }
                    select {
                        id = "r-learned"
                        name = RuntimeValues.LEARNED_BREAKS
                        for (mode in LearnedBreaks.entries) {
                            option {
                                value = mode.setting
                                selected =
                                    mode.setting == values[RuntimeValues.LEARNED_BREAKS]
                                +mode.setting
                            }
                        }
                    }
                    hint(RuntimeValues.LEARNED_BREAKS, view.environment.learnedBreaks)
                }
                div("field") {
                    label {
                        htmlFor = "r-listeners"
                        +"Ad listener: channels at once (0 for off)"
                    }
                    numberInput(name = RuntimeValues.AD_LISTENERS) {
                        id = "r-listeners"
                        value =
                            values.getValue(RuntimeValues.AD_LISTENERS)
                        min = "0"
                        max = "24"
                        required = true
                    }
                    hint(RuntimeValues.AD_LISTENERS, view.environment.adListeners.toString())
                }
            }
            p("muted small") {
                +(
                    "Plex reads a device's tuner count when it adds the device, so a change here shows in Plex after " +
                        "the " +
                        "device is re-added; the server enforces it at once. Streams per device cannot exceed the " +
                        "sessions at once. Learned ad breaks are ones the server finds in a stream with no ad " +
                        "markers, such as a long silent slate: shadow only logs them (ADDETECT), on also " +
                        "shows the break screen over them. The ad listener plays channels nobody is " +
                        "watching, headless, so the server learns their ads."
                    )
            }
            runtimeSave(view.proposed != null && runtimeChanged(view.current, view.proposed))
        }
    }

/**
 * The source file's providers and guides, as a folded section: what each is and where it comes from, never a URL
 * path, query, header value or option value.
 */
internal fun FlowContent.sourceFilePanel(providers: List<SourceSummary>, guides: List<SourceSummary>) =
    panel("Source file", id = "sources", aside = {
        span("muted") { +"URL paths, queries and header values are not shown" }
    }) {
        if (providers.isEmpty() && guides.isEmpty()) {
            p("muted") {
                +(
                    "No providers or guides. Add sources.json to the data directory, or set SOFA_SOURCES to a source " +
                        "file, and restart."
                    )
            }
            return@panel
        }
        details {
            summary { +(plural(providers.size, "provider") + ", " + plural(guides.size, "guide") + " configured") }
            div("scroll") {
                table {
                    caption("sr") { +"Configured sources" }
                    thead {
                        tr {
                            col("ID")
                            col("Kind")
                            col("From")
                            col("Refresh")
                            col("Notes")
                        }
                    }
                    tbody {
                        for (source in providers + guides) {
                            tr {
                                th {
                                    attributes["scope"] = "row"
                                    +source.id
                                }
                                td { +source.kind }
                                td { +source.where }
                                td("nowrap") { +source.refresh }
                                td("muted small") { +source.notes.joinToString("; ") }
                            }
                        }
                    }
                }
            }
        }
    }

internal fun settingsPage(chrome: Chrome, view: SettingsView): String = layout("Settings", chrome, "/admin/settings") {
    val now = chrome.now
    pageHead(
        "Settings",
        "The settings that change at once. Everything else the server runs with, and the lineup policy's file, is " +
            "under Advanced.",
    )
    view.runtime?.let { runtimePanel(chrome, it) }
    details("advanced") {
        id = "advanced"
        if (view.openAdvanced) open = true
        summary {
            +"Advanced"
            span("muted small") { +" · the lineup policy, the startup settings and the change log" }
        }
        policyPanel(chrome, view.policy, now)
        h2("mt-16") { +"Startup settings" }
        p("muted small") {
            +"Read from the server's environment (.env) when it starts. Change one there and restart the server."
        }
        div("cols") {
            for (group in view.groups) {
                panel(group.title) {
                    div("settings") {
                        for (setting in group.settings) {
                            div("setting") {
                                div("name mono") { +setting.name }
                                div("val") {
                                    when (setting.value) {
                                        SET -> chip(SET, Tone.OK)
                                        GENERATED -> chip(GENERATED, Tone.OK)
                                        UNSET -> span("muted") { +UNSET }
                                        else -> +setting.value
                                    }
                                    if (setting.isDefault && setting.value !in setOf(UNSET, SET, GENERATED)) {
                                        span("muted small") { +" · default" }
                                    }
                                    if (setting.note != null) div("muted small") { +setting.note }
                                }
                            }
                        }
                        if (group.title == "Tokens" && view.apiTokenSet) {
                            div("mt-12") {
                                attributes["id"] = "api-token"
                                val token = view.revealedApiToken
                                if (token == null) {
                                    postForm("/admin/settings/reveal-api-token", chrome.session) {
                                        button(type = ButtonType.submit) { +"Reveal the API token" }
                                    }
                                } else {
                                    div("muted small") { +"API token, for the Sofa King app and API clients:" }
                                    code { +token }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (view.policy != null) changeLogPanel(view, now)
    }
}

private fun FlowContent.policyPanel(chrome: Chrome, policy: PolicySummary?, now: java.time.Instant) =
    panel("Lineup policy", id = "policy") {
        if (policy == null) {
            p("muted") {
                +(
                    "No lineup policy. Add policy.json to the data directory, or set SOFA_POLICY to a policy file in " +
                        "a writable directory, and restart; the server then builds the lineup from it."
                    )
            }
            return@panel
        }
        dl("kv") {
            dt { +"File" }
            dd { code { +policy.path } }
            dt { +"Revision" }
            dd {
                span("mono") {
                    attributes["title"] = policy.revision
                    +policy.revision.take(12)
                }
            }
            val p = policy.policy
            dt { +"Holds" }
            dd {
                +listOf(
                    plural(p.providers.size, "provider entry", "provider entries"),
                    plural(p.numbering.groups.size, "number block"),
                    plural(p.groups.map.size, "category mapping"),
                    plural(p.groups.weak.size, "weak category", "weak categories"),
                    plural(p.groups.patterns.size, "pattern"),
                    plural(p.stations.size, "station override"),
                    plural(p.names.aliases.size, "alias", "aliases"),
                    plural(p.names.learned.size, "learned alias", "learned aliases"),
                ).joinToString(", ")
            }
            dt { +"Last run" }
            dd {
                val run = policy.lastRun
                if (run == null) {
                    +"None since the server started"
                } else {
                    ago(run.appliedAt, now)
                    +(
                        ": ${thousands(run.enabledStations)} on of ${thousands(run.stations)}, " +
                            if (run.changed) plural(run.changes, "field") + " changed" else "no change"
                        )
                }
            }
        }
        div("actions mt-14") {
            postForm("/admin/policy/apply", chrome.session) {
                button(type = ButtonType.submit, classes = "primary") { +"Apply now" }
            }
            span("muted small") {
                +(
                    "Runs the policy over the current feeds and writes the lineup. Runs also follow every import and " +
                        "every 15 minutes."
                    )
            }
        }
    }

private fun FlowContent.changeLogPanel(view: SettingsView, now: java.time.Instant) =
    panel("Change log", id = "changes", aside = {
        if (view.changeLimit < 1000) {
            a("/admin/settings?changes=${minOf(1000, view.changeLimit * 4)}&advanced=1#changes") { +"Show more" }
        }
    }) {
        if (view.changes.isEmpty()) {
            p("muted") { +"No policy run has changed the lineup yet." }
            return@panel
        }
        div("scroll") {
            table {
                caption("sr") { +"Policy change log" }
                thead {
                    tr {
                        col("When")
                        col("Station")
                        col("Field")
                        col("Before")
                        col("After")
                        col("Cause")
                    }
                }
                tbody {
                    for (entry in view.changes) {
                        tr {
                            val change = entry.change
                            td("nowrap") { ago(entry.appliedAt, now) }
                            td {
                                if (change.entryId != null) {
                                    a("/admin/lineup/${change.entryId}", classes = "rowlink") { +change.entryName }
                                } else {
                                    +change.entryName
                                }
                                change.key?.let {
                                    br()
                                    span("muted small mono") { +it }
                                }
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
