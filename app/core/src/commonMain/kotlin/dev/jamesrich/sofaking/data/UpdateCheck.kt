package dev.jamesrich.sofaking.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A build GitHub publishes: the rolling `snapshot` prerelease, one per push to `main` that changed the app, or the
 * latest tagged release, such as `v0.1.0`.
 */
data class PublishedBuild(
    /**
     * The release title's version, such as `0.4.0-dev.123+abc1234` or `0.4.0`, or the tag when the title carries none.
     */
    val version: String,
    /**
     * The build number, the commit count `.github/version.sh` gives, parsed from [version]; null for a release not
     * built by the snapshot workflow.
     */
    val run: Int?,
    /** The release page, which lists every package. */
    val pageUrl: String,
    /** The Android package's download address, when the release carries one. */
    val apkUrl: String?,
    /**
     * The package's SHA-256 in lowercase hex, from the digest GitHub records for the asset; null when it records none.
     */
    val apkSha256: String? = null,
    /**
     * The user-facing changes of the recent snapshots, or of the release, newest first, read from the release notes.
     */
    val notes: List<ReleaseNote> = emptyList(),
)

/**
 * One line of the release notes: a new feature or an upgrade note, or a fix when [fix] is set, in the snapshot numbered
 * [run]; [run] is 0 for a line of a tagged release, whose notes cover that release alone.
 */
data class ReleaseNote(val run: Int, val fix: Boolean, val text: String)

/** How the running build compares with the published one. */
sealed interface UpdateState {
    data object Checking : UpdateState

    /** The running build is the published one, or newer. */
    data class UpToDate(val latest: PublishedBuild) : UpdateState

    /**
     * A newer snapshot or release is published; [changes] are its notes for the builds newer than the running one.
     */
    data class Available(val latest: PublishedBuild, val changes: List<ReleaseNote> = emptyList()) : UpdateState

    /**
     * The running build and [latest] are not on one channel, or either carries no version to compare, so there is
     * nothing to compare; [latest] is shown as is.
     */
    data class Unknown(val latest: PublishedBuild) : UpdateState

    /** GitHub could not be asked, or answered with something that is not the release. */
    data object Failed : UpdateState
}

/**
 * Reads the published snapshot, or the latest tagged release, from GitHub's public releases API; no token, so the
 * per-address limit of 60 an hour applies.
 */
class UpdateChecker(
    private val http: HttpClient = apiHttpClient,
    private val releaseUrl: String = RELEASE_URL,
    private val stableUrl: String = STABLE_URL,
) {
    /**
     * The published build of the channel [running] is on: the latest tagged release for a version without a snapshot
     * suffix, the snapshot otherwise. Null when GitHub did not answer with it; transport failures surface as
     * exceptions.
     */
    suspend fun latest(running: String = ""): PublishedBuild? {
        val url = if (isStable(running)) stableUrl else releaseUrl
        val response = http.get(url) { header(HttpHeaders.Accept, "application/vnd.github+json") }
        if (!response.status.isSuccess()) return null
        val body = response.bodyAsText()
        val release = try {
            json.decodeFromString<ReleaseDto>(body)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Titled `Snapshot 0.4.0-dev.123+abc1234` or `Sofa King 0.4.0`.
        val version = release.name.removePrefix("Snapshot").removePrefix("Sofa King").trim()
            .ifEmpty { release.tagName.removePrefix("v") }
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
        return PublishedBuild(
            version = version,
            run = runNumber(version),
            pageUrl = release.htmlUrl,
            apkUrl = apk?.url,
            apkSha256 = apk?.digest?.let(::sha256Hex),
            notes = releaseNotes(release.body),
        )
    }

    @Serializable
    private data class ReleaseDto(
        val name: String = "",
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val body: String? = null,
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(
        val name: String = "",
        @SerialName("browser_download_url") val url: String = "",
        /** `sha256:` and the hex digest; GitHub computes it on upload. */
        val digest: String? = null,
    )

    companion object {
        const val RELEASE_URL = "https://api.github.com/repos/jamesarich/sofa-king/releases/tags/snapshot"

        /** GitHub's latest release, which skips prereleases such as the snapshot. */
        const val STABLE_URL = "https://api.github.com/repos/jamesarich/sofa-king/releases/latest"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The snapshot's build number, the commit count, in a version like `0.4.0-dev.123+abc1234`,
         * or null for any other version.
         */
        fun runNumber(version: String): Int? = RUN.find(version)?.groupValues?.get(1)?.toIntOrNull()

        /** Whether [version] is a tagged release's, such as `0.4.0`, with no snapshot suffix. */
        fun isStable(version: String): Boolean = release(version) != null

        /** Where [running], the version this build reports, stands against [latest]. */
        fun compare(running: String, latest: PublishedBuild): UpdateState {
            val stable = release(running)
            if (stable != null) {
                val theirs = release(latest.version) ?: return UpdateState.Unknown(latest)
                return if (compareReleases(theirs, stable) > 0) {
                    UpdateState.Available(latest, latest.notes)
                } else {
                    UpdateState.UpToDate(latest)
                }
            }
            val mine = runNumber(running) ?: return UpdateState.Unknown(latest)
            val theirs = latest.run ?: return UpdateState.Unknown(latest)
            if (theirs <= mine) return UpdateState.UpToDate(latest)
            return UpdateState.Available(latest, latest.notes.filter { it.run > mine })
        }

        /**
         * The notes `publish-snapshot.yml` and `publish-release.yml` write with `.github/cliff.toml`: a `## <version>`
         * section per recent snapshot, or one for the release, `### Upgrade notes`, `### New` and `### Fixes` within
         * it, and a `- scope: summary (#123)` line per change. Upgrade notes read as new, ahead of the rest. The scope
         * and the pull request are dropped, since neither means anything on a TV; lines outside a versioned section
         * are skipped.
         */
        fun releaseNotes(body: String?): List<ReleaseNote> {
            var section: Int? = null
            var fix: Boolean? = null
            val notes = mutableListOf<ReleaseNote>()
            for (line in body.orEmpty().lineSequence().map { it.trimEnd() }) {
                when {
                    line.startsWith("## ") -> {
                        val heading = line.removePrefix("## ").trim()
                        section = runNumber(heading) ?: release(heading)?.let { 0 }
                        fix = null
                    }

                    line.startsWith("### ") -> fix = when (line.removePrefix("### ").trim()) {
                        "Upgrade notes", "New" -> false
                        "Fixes" -> true
                        else -> null
                    }

                    else -> note(section, fix, line)?.let(notes::add)
                }
            }
            return notes
        }

        /** The change on [line], a `- ` line of the build numbered [run] under a known group, or null for any other. */
        private fun note(run: Int?, fix: Boolean?, line: String): ReleaseNote? {
            val text = NOTE.matchEntire(line)?.groupValues?.get(1)
            return if (run != null && fix != null && text != null) ReleaseNote(run, fix, text) else null
        }

        /** The hex of a `sha256:<hex>` asset digest, lowercased, or null for any other algorithm or shape. */
        fun sha256Hex(digest: String): String? =
            digest.removePrefix("sha256:").takeIf { it != digest && SHA256.matches(it) }?.lowercase()

        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val NOTE = Regex("""- (?:[a-z][a-z0-9,-]*: )?(.+?)(?: \(#\d+\))?""")
        private val RUN = Regex("""-dev\.(\d+)(?:\+|$)""")
        private val RELEASE = Regex("""(\d+)\.(\d+)\.(\d+)""")

        /** The major, minor and patch numbers of a plain `X.Y.Z` version, or null for any other. */
        private fun release(version: String): List<Int>? =
            RELEASE.matchEntire(version)?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return null }

        private fun compareReleases(a: List<Int>, b: List<Int>): Int =
            a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0
    }
}
