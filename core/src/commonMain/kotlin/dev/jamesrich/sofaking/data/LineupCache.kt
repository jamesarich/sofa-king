package dev.jamesrich.sofaking.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readDouble
import kotlinx.io.readString
import kotlinx.io.writeDouble
import kotlinx.io.writeString
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The last successfully loaded [Lineup], kept in app-private storage so a cold start can show it before the network answers.
 * [key] identifies the server; a cache written for another key is ignored and left in place. The layout is the one
 * `DataOutputStream` wrote before the multiplatform port (big-endian numbers, length-prefixed UTF-8), so caches
 * already on a TV stay readable.
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
            if (input.readText() != key) return@withContext null
            readLineup(input, now())
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
        out.writeText(key)
        writeLineup(out, lineup)
        store.write(out.readByteArray())
    }

    private fun writeLineup(out: Sink, lineup: Lineup) {
        out.writeInt(lineup.channels.size)
        for (channel in lineup.channels) {
            out.writeText(channel.guideId)
            out.writeDouble(channel.number)
            out.writeText(channel.name)
            out.writeNullableText(channel.logo)
            out.writeInt(channel.groups.size)
            for (group in channel.groups) out.writeText(group)
            out.writeText(channel.guideStatus.name)
        }
        // Empty lists are dropped on read, so skipping them here keeps the file minimal without changing the result.
        val guide = lineup.guide.filterValues { it.isNotEmpty() }
        out.writeInt(guide.size)
        for ((channelId, programmes) in guide) {
            out.writeText(channelId)
            out.writeInt(programmes.size)
            for (programme in programmes) {
                // The channel id is the map key, so it is not repeated per programme.
                out.writeLong(programme.start.epochSeconds)
                out.writeLong(programme.stop.epochSeconds)
                out.writeText(programme.title)
                out.writeNullableText(programme.description)
                out.writeNullableText(programme.subTitle)
                out.writeNullableText(programme.episode)
                out.writeByte(if (programme.isNew) 1 else 0)
                out.writeNullableText(programme.rating)
                out.writeNullableText(programme.icon)
            }
        }
    }

    private fun readLineup(input: Source, at: Instant): Lineup {
        val channelCount = input.readCount()
        val channels = List(channelCount) {
            val guideId = input.readText()
            val number = input.readDouble()
            val name = input.readText()
            val logo = input.readNullableText()
            val groups = List(input.readCount()) { input.readText() }
            require(groups.isNotEmpty()) { "channel without a group" }
            val guideStatus = GuideStatus.valueOf(input.readText())
            Channel(guideId = guideId, number = number, name = name, logo = logo, groups = groups, guideStatus = guideStatus)
        }
        val channelIds = input.readCount()
        val guide = HashMap<String, List<Programme>>()
        repeat(channelIds) {
            val channelId = input.readText()
            val count = input.readCount()
            val programmes = ArrayList<Programme>()
            repeat(count) {
                val start = Instant.fromEpochSeconds(input.readLong())
                val stop = Instant.fromEpochSeconds(input.readLong())
                val title = input.readText()
                val description = input.readNullableText()
                val subTitle = input.readNullableText()
                val episode = input.readNullableText()
                val isNew = input.readByte() != 0.toByte()
                val rating = input.readNullableText()
                val icon = input.readNullableText()
                if (stop > at) {
                    programmes.add(Programme(channelId, start, stop, title, description, subTitle, episode, isNew, rating, icon))
                }
            }
            if (programmes.isNotEmpty()) guide[channelId] = programmes
        }
        return Lineup(channels, guide)
    }

    private fun Sink.writeText(value: String) {
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size)
        write(bytes)
    }

    private fun Sink.writeNullableText(value: String?) {
        writeByte(if (value != null) 1 else 0)
        if (value != null) writeText(value)
    }

    private fun Source.readCount(): Int {
        val count = readInt()
        require(count in 0..MAX_COUNT) { "corrupt count $count" }
        return count
    }

    private fun Source.readText(): String {
        val length = readInt()
        require(length in 0..MAX_TEXT_BYTES) { "corrupt text length $length" }
        return readString(length.toLong())
    }

    private fun Source.readNullableText(): String? = if (readByte() != 0.toByte()) readText() else null

    private companion object {
        /** Bump when the layout changes; a cache with any other version is deleted on read and the lineup refetched. */
        const val VERSION = 5
        const val MAX_COUNT = 1_000_000
        const val MAX_TEXT_BYTES = 1 shl 20
    }
}
