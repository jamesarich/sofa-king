package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import java.time.Instant
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** SQLite binds at most 32,766 variables per statement; multi-row inserts stay well under that. */
private const val INSERT_CHUNK = 2000

suspend fun BackendStore.lineup(): List<LineupEntryRecord> = query {
    val offerings = LineupOfferings.selectAll().orderBy(LineupOfferings.position).groupBy({ it[LineupOfferings.entryId] }, { it[LineupOfferings.offeringId] })
    LineupEntries.selectAll().orderBy(LineupEntries.position).map { row ->
        val guideId = row[LineupEntries.guideId]
        val guideChannel = row[LineupEntries.guideChannel]
        LineupEntryRecord(
            id = row[LineupEntries.id],
            number = row[LineupEntries.number],
            name = row[LineupEntries.name],
            group = row[LineupEntries.groupName],
            logo = row[LineupEntries.logo],
            enabled = row[LineupEntries.enabled],
            guide = if (guideId != null && guideChannel != null) GuideMapping(guideId, guideChannel) else null,
            offeringIds = offerings[row[LineupEntries.id]].orEmpty(),
        )
    }
}

/** Replaces the whole lineup atomically; the list order is the household order. */
suspend fun BackendStore.saveLineup(entries: List<LineupEntryRecord>, at: Instant) = query {
    LineupOfferings.deleteAll()
    LineupEntries.deleteAll()
    entries.withIndex().chunked(INSERT_CHUNK).forEach { chunk ->
        LineupEntries.batchInsert(chunk) { (position, entry) ->
            this[LineupEntries.id] = entry.id
            this[LineupEntries.position] = position
            this[LineupEntries.number] = entry.number
            this[LineupEntries.name] = entry.name
            this[LineupEntries.groupName] = entry.group
            this[LineupEntries.logo] = entry.logo
            this[LineupEntries.enabled] = entry.enabled
            this[LineupEntries.guideId] = entry.guide?.guideId
            this[LineupEntries.guideChannel] = entry.guide?.channelId
            this[LineupEntries.updatedAt] = at.toString()
        }
    }
    entries.flatMap { entry -> entry.offeringIds.withIndex().map { (position, offeringId) -> Triple(entry.id, position, offeringId) } }
        .chunked(INSERT_CHUNK).forEach { chunk ->
            LineupOfferings.batchInsert(chunk) { (entryId, position, offeringId) ->
                this[LineupOfferings.entryId] = entryId
                this[LineupOfferings.position] = position
                this[LineupOfferings.offeringId] = offeringId
            }
        }
    Unit
}

class StoredGuide(val guideId: String, val importedAt: Instant, val channels: List<GuideChannel>, val programmeEnds: Map<String, Instant>, val programmes: Int)

suspend fun BackendStore.guides(): Map<String, StoredGuide> = query {
    val channels = GuideChannels.selectAll().groupBy({ it[GuideChannels.guideId] }) {
        GuideChannel(it[GuideChannels.channelId], it[GuideChannels.displayName], it[GuideChannels.icon])
    }
    val maxEnd = GuideProgrammes.endsAt.max()
    val count = GuideProgrammes.channelId.count()
    val ends = GuideProgrammes.select(GuideProgrammes.guideId, GuideProgrammes.channelId, maxEnd, count)
        .groupBy(GuideProgrammes.guideId, GuideProgrammes.channelId)
        .groupBy({ it[GuideProgrammes.guideId] }) { Triple(it[GuideProgrammes.channelId], Instant.ofEpochSecond(it[maxEnd]!!), it[count]) }
    GuideImports.selectAll().associate { row ->
        val id = row[GuideImports.guideId]
        val rows = ends[id].orEmpty()
        id to StoredGuide(id, Instant.parse(row[GuideImports.importedAt]), channels[id].orEmpty(), rows.associate { it.first to it.second }, rows.sumOf { it.third.toInt() })
    }
}

/** Replaces one guide's channels and retained programmes atomically. */
suspend fun BackendStore.saveGuide(guideId: String, importedAt: Instant, channels: List<GuideChannel>, programmes: List<GuideProgramme>) = query {
    GuideProgrammes.deleteWhere { GuideProgrammes.guideId eq guideId }
    GuideChannels.deleteWhere { GuideChannels.guideId eq guideId }
    GuideImports.deleteWhere { GuideImports.guideId eq guideId }
    GuideImports.insert { it[GuideImports.guideId] = guideId; it[GuideImports.importedAt] = importedAt.toString() }
    channels.distinctBy { it.id }.chunked(INSERT_CHUNK).forEach { chunk ->
        GuideChannels.batchInsert(chunk) { channel ->
            this[GuideChannels.guideId] = guideId
            this[GuideChannels.channelId] = channel.id
            this[GuideChannels.displayName] = channel.displayName
            this[GuideChannels.icon] = channel.icon
        }
    }
    programmes.chunked(INSERT_CHUNK).forEach { chunk ->
        GuideProgrammes.batchInsert(chunk, ignore = true) { programme ->
            this[GuideProgrammes.guideId] = guideId
            this[GuideProgrammes.channelId] = programme.channelId
            this[GuideProgrammes.startsAt] = programme.startsAt.epochSecond
            this[GuideProgrammes.endsAt] = programme.endsAt.epochSecond
            this[GuideProgrammes.title] = programme.title
            this[GuideProgrammes.description] = programme.description
        }
    }
    Unit
}

/** Programmes overlapping [from, to) for the given guide channels, grouped by channel and ordered by start. */
suspend fun BackendStore.programmes(guideId: String, channelIds: Collection<String>, from: Instant, to: Instant): Map<String, List<GuideProgramme>> = query {
    if (channelIds.isEmpty()) {
        emptyMap()
    } else {
        GuideProgrammes.selectAll().where {
            (GuideProgrammes.guideId eq guideId) and (GuideProgrammes.channelId inList channelIds.toSet()) and
                (GuideProgrammes.startsAt less to.epochSecond) and (GuideProgrammes.endsAt greater from.epochSecond)
        }.orderBy(GuideProgrammes.startsAt).groupBy({ it[GuideProgrammes.channelId] }) {
            GuideProgramme(
                it[GuideProgrammes.channelId], Instant.ofEpochSecond(it[GuideProgrammes.startsAt]), Instant.ofEpochSecond(it[GuideProgrammes.endsAt]),
                it[GuideProgrammes.title], it[GuideProgrammes.description],
            )
        }
    }
}
