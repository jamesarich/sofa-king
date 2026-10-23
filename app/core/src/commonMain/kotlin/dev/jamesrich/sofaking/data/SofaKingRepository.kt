package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/** A loaded guide: programmes by channel, and the requested channels the server said have left the lineup. */
data class GuideLoad(val programmes: Map<String, List<Programme>>, val removed: Set<String>)

/**
 * The lineup from a Sofa King server. The catalog is fetched with its ETag, so an unchanged lineup costs one
 * header round trip; the guide is queried in chunks of [GUIDE_CHUNK] channels over the same window the guide
 * grid shows, 24 hours from the half hour that contains [now]. Both are mapped to the models on [background].
 */
class SofaKingRepository(
    private val api: SofaKingApi,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Instant = Clock.System::now,
) {
    private var etag: String? = null
    private var channels: List<Channel>? = null

    /** The catalog alone: small and fast, so the UI can show channels before the guide arrives. */
    suspend fun loadChannels(): List<Channel> = when (val result = api.catalog(etag)) {
        is CatalogResult.Changed -> withContext(background) {
            SofaKingMapper.channels(result.catalog, api::logoUrl)
        }.also {
            channels = it
            etag = result.etag
        }

        CatalogResult.NotModified -> channels ?: run {
            // Nothing to answer 304 with: the ETag is stale, so ask again without one.
            etag = null
            loadChannels()
        }
    }

    /**
     * Programmes for [channels] over the guide window starting at [from] (the one that contains now unless the
     * guide asked for a later day). Chunks share one catalog revision; a revision that moves
     * under them restarts without one.
     */
    suspend fun loadGuide(channels: List<Channel>, from: Instant = GuideWindow.start(now())): GuideLoad {
        val to = GuideWindow.end(from)
        val chunks = channels.map { it.guideId }.distinct().chunked(GUIDE_CHUNK)
        return try {
            query(chunks, from, to, pinRevision = true)
        } catch (e: SofaKingException) {
            if (e.status != 409) throw e
            query(chunks, from, to, pinRevision = false)
        }
    }

    /**
     * The first chunk answers the revision the rest pin; the rest run [GUIDE_PARALLEL] at a time,
     * and any failure cancels them all.
     */
    private suspend fun query(chunks: List<List<String>>, from: Instant, to: Instant, pinRevision: Boolean): GuideLoad {
        if (chunks.isEmpty()) return GuideLoad(emptyMap(), emptySet())
        val first = api.guide(GuideQueryDto(chunks.first(), from.toString(), to.toString(), null))
        val revision = first.revision.takeIf { pinRevision }
        val limit = Semaphore(GUIDE_PARALLEL)
        val rest = coroutineScope {
            chunks.drop(1).map { chunk ->
                async { limit.withPermit { api.guide(GuideQueryDto(chunk, from.toString(), to.toString(), revision)) } }
            }.awaitAll()
        }
        return withContext(background) {
            val guide = HashMap<String, List<Programme>>()
            val removed = HashSet<String>()
            for (window in listOf(first) + rest) {
                guide += SofaKingMapper.programmes(window)
                removed += SofaKingMapper.removed(window)
            }
            GuideLoad(guide, removed)
        }
    }

    companion object {
        const val GUIDE_CHUNK = Wire.GUIDE_MAX_CHANNELS

        /** Guide chunks in flight at once after the first. */
        const val GUIDE_PARALLEL = 4
    }
}
