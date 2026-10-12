package dev.jamesrich.sofaking.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The lineup from a Sofa King server. The catalog is fetched with its ETag, so an unchanged lineup costs one
 * header round trip; the guide is queried in chunks of [GUIDE_CHUNK] channels over the same window the guide
 * grid shows, 24 hours from the half hour that contains [now].
 */
class SofaKingRepository(
    private val api: SofaKingApi,
    private val now: () -> Instant = Clock.System::now,
) {
    private var etag: String? = null
    private var channels: List<Channel>? = null

    /** The catalog alone: small and fast, so the UI can show channels before the guide arrives. */
    suspend fun loadChannels(): List<Channel> {
        return when (val result = api.catalog(etag)) {
            is CatalogResult.Changed -> SofaKingMapper.channels(result.catalog).also {
                channels = it
                etag = result.etag
            }
            CatalogResult.NotModified -> channels ?: run {
                // Nothing to answer 304 with: the ETag is stale, so ask again without one.
                etag = null
                loadChannels()
            }
        }
    }

    /** Programmes for [channels] over the guide window. Chunks share one catalog revision; a revision that moves under them restarts without one. */
    suspend fun loadGuide(channels: List<Channel>): Map<String, List<Programme>> {
        val from = GuideWindow.start(now())
        val to = GuideWindow.end(from)
        val chunks = channels.map { it.guideId }.distinct().chunked(GUIDE_CHUNK)
        return try {
            query(chunks, from, to, pinRevision = true)
        } catch (e: SofaKingException) {
            if (e.status != 409) throw e
            query(chunks, from, to, pinRevision = false)
        }
    }

    /** The first chunk answers the revision the rest pin; the rest run [GUIDE_PARALLEL] at a time, and any failure cancels them all. */
    private suspend fun query(chunks: List<List<String>>, from: Instant, to: Instant, pinRevision: Boolean): Map<String, List<Programme>> {
        if (chunks.isEmpty()) return emptyMap()
        val first = api.guide(GuideQueryDto(chunks.first(), from.toString(), to.toString(), null))
        val revision = first.revision.takeIf { pinRevision }
        val limit = Semaphore(GUIDE_PARALLEL)
        val rest = coroutineScope {
            chunks.drop(1).map { chunk ->
                async { limit.withPermit { api.guide(GuideQueryDto(chunk, from.toString(), to.toString(), revision)) } }
            }.awaitAll()
        }
        val guide = HashMap<String, List<Programme>>()
        for (window in listOf(first) + rest) guide += SofaKingMapper.programmes(window)
        return guide
    }

    companion object {
        const val GUIDE_CHUNK = 200

        /** Guide chunks in flight at once after the first. */
        const val GUIDE_PARALLEL = 4
    }
}
