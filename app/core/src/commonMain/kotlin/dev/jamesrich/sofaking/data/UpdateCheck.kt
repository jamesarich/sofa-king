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

/** The build GitHub publishes as the rolling `snapshot` prerelease: one per push to `main` that changed the app. */
data class PublishedBuild(
    /** The release title's version, such as `0.4.0-dev.123+abc1234`, or the tag when the title carries none. */
    val version: String,
    /** The build number, the commit count `.github/version.sh` gives, parsed from [version]; null for a release not built by the snapshot workflow. */
    val run: Int?,
    /** The release page, which lists every package. */
    val pageUrl: String,
    /** The Android package's download address, when the release carries one. */
    val apkUrl: String?,
    /** The package's SHA-256 in lowercase hex, from the digest GitHub records for the asset; null when it records none. */
    val apkSha256: String? = null,
)

/** How the running build compares with the published one. */
sealed interface UpdateState {
    data object Checking : UpdateState

    /** The running build is the published one, or newer. */
    data class UpToDate(val latest: PublishedBuild) : UpdateState

    /** A newer snapshot is published. */
    data class Available(val latest: PublishedBuild) : UpdateState

    /** The running build is not a snapshot (a local or release build), so there is nothing to compare; [latest] is shown as is. */
    data class Unknown(val latest: PublishedBuild) : UpdateState

    /** GitHub could not be asked, or answered with something that is not the release. */
    data object Failed : UpdateState
}

/** Reads the published snapshot from GitHub's public releases API; no token, so the per-address limit of 60 an hour applies. */
class UpdateChecker(private val http: HttpClient = apiHttpClient, private val releaseUrl: String = RELEASE_URL) {
    /** The published build, or null when GitHub did not answer with it. Transport failures surface as exceptions. */
    suspend fun latest(): PublishedBuild? {
        val response = http.get(releaseUrl) { header(HttpHeaders.Accept, "application/vnd.github+json") }
        if (!response.status.isSuccess()) return null
        val body = response.bodyAsText()
        val release = try {
            json.decodeFromString<ReleaseDto>(body)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Titled `Snapshot 0.4.0-dev.123+abc1234`.
        val version = release.name.removePrefix("Snapshot").trim().ifEmpty { release.tagName }
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
        return PublishedBuild(
            version = version,
            run = runNumber(version),
            pageUrl = release.htmlUrl,
            apkUrl = apk?.url,
            apkSha256 = apk?.digest?.let(::sha256Hex),
        )
    }

    @Serializable
    private data class ReleaseDto(
        val name: String = "",
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
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
        private val json = Json { ignoreUnknownKeys = true }

        /** The snapshot's build number, the commit count, in a version like `0.4.0-dev.123+abc1234`, or null for any other version. */
        fun runNumber(version: String): Int? = RUN.find(version)?.groupValues?.get(1)?.toIntOrNull()

        /** Where [running], the version this build reports, stands against [latest]. */
        fun compare(running: String, latest: PublishedBuild): UpdateState {
            val mine = runNumber(running) ?: return UpdateState.Unknown(latest)
            val theirs = latest.run ?: return UpdateState.Unknown(latest)
            return if (theirs > mine) UpdateState.Available(latest) else UpdateState.UpToDate(latest)
        }

        /** The hex of a `sha256:<hex>` asset digest, lowercased, or null for any other algorithm or shape. */
        fun sha256Hex(digest: String): String? =
            digest.removePrefix("sha256:").takeIf { it != digest && SHA256.matches(it) }?.lowercase()

        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val RUN = Regex("""-dev\.(\d+)(?:\+|$)""")
    }
}
