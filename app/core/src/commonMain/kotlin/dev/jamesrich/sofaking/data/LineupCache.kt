package dev.jamesrich.sofaking.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The last successfully loaded [Lineup], kept in app-private storage so a cold start can show it before the network answers.
 * [key] identifies the server; a cache written for another key is ignored and left in place. The file is a big-endian
 * [VERSION] followed by a protobuf [CachedLineup]; a cache with any other version, such as the hand-written layout
 * before version 6, is deleted on read and the lineup refetched.
 */
class LineupCache(
    private val store: ByteStore,
    private val key: String,
    private val now: () -> Instant = Clock.System::now,
) {
    /** The cached lineup without programmes that have already ended, or null if there is none usable. */
    suspend fun read(): Lineup? = withContext(ioDispatcher) {
        val bytes = try {
            store.read()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        try {
            val input = Buffer().apply { write(bytes) }
            if (input.readInt() != VERSION) {
                store.delete()
                return@withContext null
            }
            val cached = ProtoBuf.decodeFromByteArray<CachedLineup>(input.readByteArray())
            if (cached.key != key) return@withContext null
            cached.toLineup(now())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            store.delete()
            null
        }
    }

    /** Replaces the cache whole: a crash mid-write leaves the previous one intact. */
    suspend fun write(lineup: Lineup): Unit = withContext(ioDispatcher) {
        val out = Buffer()
        out.writeInt(VERSION)
        out.write(ProtoBuf.encodeToByteArray(CachedLineup.of(key, lineup)))
        store.write(out.readByteArray())
    }

    private companion object {
        /** Bump when [CachedLineup] changes incompatibly; a cache with any other version is deleted on read and the lineup refetched. */
        const val VERSION = 6
    }
}

// Field numbers are the format: add new fields with new numbers and defaults, never renumber.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class CachedLineup(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val channels: List<CachedChannel> = emptyList(),
    /** Programmes grouped by channel, so the channel id is not repeated per programme. */
    @ProtoNumber(3) val guide: List<CachedGuide> = emptyList(),
) {
    fun toLineup(at: Instant): Lineup {
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

    companion object {
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
private class CachedChannel(
    @ProtoNumber(1) val guideId: String,
    @ProtoNumber(2) val number: Double,
    @ProtoNumber(3) val name: String,
    @ProtoNumber(4) val logo: String? = null,
    @ProtoNumber(5) val groups: List<String> = emptyList(),
    @ProtoNumber(6) val guideStatus: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class CachedGuide(
    @ProtoNumber(1) val channelId: String,
    @ProtoNumber(2) val programmes: List<CachedProgramme> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class CachedProgramme(
    @ProtoNumber(1) val start: Long,
    @ProtoNumber(2) val stop: Long,
    @ProtoNumber(3) val title: String,
    @ProtoNumber(4) val description: String? = null,
    @ProtoNumber(5) val subTitle: String? = null,
    @ProtoNumber(6) val episode: String? = null,
    @ProtoNumber(7) val isNew: Boolean = false,
    @ProtoNumber(8) val rating: String? = null,
    @ProtoNumber(9) val icon: String? = null,
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
        )
    }
}
