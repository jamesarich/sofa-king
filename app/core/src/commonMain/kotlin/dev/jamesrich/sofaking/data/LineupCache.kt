package dev.jamesrich.sofaking.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okio.BufferedSink
import okio.BufferedSource
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The last successfully loaded [Lineup], kept in app-private storage so a cold start can show it before the network answers.
 * [key] identifies the server; a cache written for another key is ignored and left in place. The store is a DataStore
 * of one protobuf [CachedLineup] (a file through [lineupCacheDataStore], or the browser's storage), which replaces the file whole and wipes one it cannot read.
 * A read builds the lineup on [background].
 */
class LineupCache(
    private val store: DataStore<CachedLineup?>,
    private val key: String,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Instant = Clock.System::now,
) {
    /** The cached lineup without programmes that have already ended, or null if there is none usable. */
    suspend fun read(): Lineup? {
        val cached = try {
            store.data.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        if (cached.key != key) return null
        return try {
            withContext(background) { cached.toLineup(now()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            clear()
            null
        }
    }

    /** Replaces the cache whole: a crash mid-write leaves the previous one intact. */
    suspend fun write(lineup: Lineup) {
        store.updateData { CachedLineup.of(key, lineup) }
    }

    private suspend fun clear() {
        try {
            store.updateData { null }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing to do: the next read fails the same way and the next write replaces it.
        }
    }
}

/** The protobuf record as the bytes of the store; an empty store is no lineup, and anything undecodable is corruption. */
@OptIn(ExperimentalSerializationApi::class)
object CachedLineupSerializer : OkioSerializer<CachedLineup?> {
    override val defaultValue: CachedLineup? = null

    override suspend fun readFrom(source: BufferedSource): CachedLineup? {
        val bytes = source.readByteArray()
        if (bytes.isEmpty()) return null
        return try {
            ProtoBuf.decodeFromByteArray<CachedLineup>(bytes)
        } catch (e: Exception) {
            throw CorruptionException("The lineup cache does not decode", e)
        }
    }

    override suspend fun writeTo(t: CachedLineup?, sink: BufferedSink) {
        if (t != null) sink.write(ProtoBuf.encodeToByteArray(t))
    }
}

// Field numbers are the format: add new fields with new numbers and defaults, never renumber.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
class CachedLineup internal constructor(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) internal val channels: List<CachedChannel> = emptyList(),
    /** Programmes grouped by channel, so the channel id is not repeated per programme. */
    @ProtoNumber(3) internal val guide: List<CachedGuide> = emptyList(),
) {
    internal fun toLineup(at: Instant): Lineup {
        val channels = channels.map { channel ->
            require(channel.groups.isNotEmpty()) { "channel without a group" }
            Channel(
                guideId = channel.guideId,
                number = channel.number,
                name = channel.name,
                logo = channel.logo,
                groups = channel.groups,
                guideStatus = GuideStatus.valueOf(channel.guideStatus),
            )
        }
        val guide = HashMap<String, List<Programme>>()
        for (entry in this.guide) {
            val programmes = entry.programmes.mapNotNull { it.toProgramme(entry.channelId).takeIf { programme -> programme.stop > at } }
            if (programmes.isNotEmpty()) guide[entry.channelId] = programmes
        }
        return Lineup(channels, guide)
    }

    internal companion object {
        fun of(key: String, lineup: Lineup) = CachedLineup(
            key = key,
            channels = lineup.channels.map {
                CachedChannel(it.guideId, it.number, it.name, it.logo, it.groups, it.guideStatus.name)
            },
            // Empty lists are dropped on read, so skipping them here keeps the file minimal without changing the result.
            guide = lineup.guide.filterValues { it.isNotEmpty() }.map { (channelId, programmes) ->
                CachedGuide(channelId, programmes.map(CachedProgramme::of))
            },
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class CachedChannel(
    @ProtoNumber(1) val guideId: String,
    @ProtoNumber(2) val number: Double,
    @ProtoNumber(3) val name: String,
    @ProtoNumber(4) val logo: String? = null,
    @ProtoNumber(5) val groups: List<String> = emptyList(),
    @ProtoNumber(6) val guideStatus: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class CachedGuide(
    @ProtoNumber(1) val channelId: String,
    @ProtoNumber(2) val programmes: List<CachedProgramme> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class CachedProgramme(
    @ProtoNumber(1) val start: Long,
    @ProtoNumber(2) val stop: Long,
    @ProtoNumber(3) val title: String,
    @ProtoNumber(4) val description: String? = null,
    @ProtoNumber(5) val subTitle: String? = null,
    @ProtoNumber(6) val episode: String? = null,
    @ProtoNumber(7) val isNew: Boolean = false,
    @ProtoNumber(8) val rating: String? = null,
    @ProtoNumber(9) val icon: String? = null,
    @ProtoNumber(10) val kinds: Int = 0,
) {
    fun toProgramme(channelId: String) = Programme(
        channelId = channelId,
        start = Instant.fromEpochSeconds(start),
        stop = Instant.fromEpochSeconds(stop),
        title = title,
        description = description,
        subTitle = subTitle,
        episode = episode,
        isNew = isNew,
        rating = rating,
        icon = icon,
        kinds = kindsOf(kinds),
    )

    companion object {
        fun of(programme: Programme) = CachedProgramme(
            start = programme.start.epochSeconds,
            stop = programme.stop.epochSeconds,
            title = programme.title,
            description = programme.description,
            subTitle = programme.subTitle,
            episode = programme.episode,
            isNew = programme.isNew,
            rating = programme.rating,
            icon = programme.icon,
            kinds = programme.kinds.mask(),
        )
    }
}
