package dev.jamesrich.sofaking.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant

/**
 * The last successfully loaded [Lineup], kept in app-private storage so a cold start can show it before the network answers.
 * [key] identifies the server; a file written for another key is ignored and left in place.
 */
class LineupCache(
    private val file: File,
    private val key: String,
    private val now: () -> Instant = Instant::now,
) {
    /** The cached lineup without programmes that have already ended, or null if there is none usable. */
    suspend fun read(): Lineup? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            DataInputStream(BufferedInputStream(FileInputStream(file), BUFFER_SIZE)).use { input ->
                if (input.readInt() != VERSION) {
                    file.delete()
                    return@withContext null
                }
                if (input.readText() != key) return@withContext null
                readLineup(input, now())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /** Replaces the cache atomically: a crash mid-write leaves the previous file intact. */
    suspend fun write(lineup: Lineup): Unit = withContext(Dispatchers.IO) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temp), BUFFER_SIZE)).use { out ->
                out.writeInt(VERSION)
                out.writeText(key)
                writeLineup(out, lineup)
            }
            if (!temp.renameTo(file)) throw IOException("could not replace ${file.name}")
        } finally {
            temp.delete()
        }
    }

    private fun writeLineup(out: DataOutputStream, lineup: Lineup) {
        out.writeInt(lineup.channels.size)
        for (channel in lineup.channels) {
            out.writeText(channel.guideId)
            out.writeDouble(channel.number)
            out.writeText(channel.name)
            out.writeNullableText(channel.logo)
            out.writeInt(channel.groups.size)
            for (group in channel.groups) out.writeText(group)
        }
        // Empty lists are dropped on read, so skipping them here keeps the file minimal without changing the result.
        val guide = lineup.guide.filterValues { it.isNotEmpty() }
        out.writeInt(guide.size)
        for ((channelId, programmes) in guide) {
            out.writeText(channelId)
            out.writeInt(programmes.size)
            for (programme in programmes) {
                // The channel id is the map key, so it is not repeated per programme.
                out.writeLong(programme.start.epochSecond)
                out.writeLong(programme.stop.epochSecond)
                out.writeText(programme.title)
                out.writeNullableText(programme.subtitle)
                out.writeNullableText(programme.description)
            }
        }
    }

    private fun readLineup(input: DataInputStream, at: Instant): Lineup {
        val channelCount = input.readCount()
        val channels = List(channelCount) {
            val guideId = input.readText()
            val number = input.readDouble()
            val name = input.readText()
            val logo = input.readNullableText()
            val groups = List(input.readCount()) { input.readText() }
            require(groups.isNotEmpty()) { "channel without a group" }
            Channel(guideId = guideId, number = number, name = name, logo = logo, groups = groups)
        }
        val channelIds = input.readCount()
        val guide = HashMap<String, List<Programme>>()
        repeat(channelIds) {
            val channelId = input.readText()
            val count = input.readCount()
            val programmes = ArrayList<Programme>()
            repeat(count) {
                val start = Instant.ofEpochSecond(input.readLong())
                val stop = Instant.ofEpochSecond(input.readLong())
                val title = input.readText()
                val subtitle = input.readNullableText()
                val description = input.readNullableText()
                if (stop.isAfter(at)) {
                    programmes.add(Programme(channelId, start, stop, title, subtitle, description))
                }
            }
            if (programmes.isNotEmpty()) guide[channelId] = programmes
        }
        return Lineup(channels, guide)
    }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableText(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeText(value)
    }

    private fun DataInputStream.readCount(): Int {
        val count = readInt()
        require(count in 0..MAX_COUNT) { "corrupt count $count" }
        return count
    }

    private fun DataInputStream.readText(): String {
        val length = readInt()
        require(length in 0..MAX_TEXT_BYTES) { "corrupt text length $length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataInputStream.readNullableText(): String? = if (readBoolean()) readText() else null

    private companion object {
        /** Bump when the layout changes; older files are deleted on read. */
        const val VERSION = 3
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_COUNT = 1_000_000
        const val MAX_TEXT_BYTES = 1 shl 20
    }
}
