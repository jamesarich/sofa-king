package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.EpisodeNumber
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.catalog.PreviouslyShown
import dev.jamesrich.sofaking.backend.catalog.ProgrammeRating
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** SQLite binds at most 32,766 variables per statement; multi-row inserts stay well under that. */
private const val INSERT_CHUNK = 2000

/** Rows a guide import writes or deletes per transaction: big enough to keep imports quick, small enough that other writes wait at most about a second. */
private const val PROGRAMMES_PER_TRANSACTION = 20_000

suspend fun BackendStore.lineup(): List<LineupEntryRecord> = read {
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
            createdBy = row[LineupEntries.createdBy],
            autoDisabledAt = row[LineupEntries.autoDisabledAt]?.let(Instant::parse),
            autoDisabledReason = row[LineupEntries.autoDisabledReason],
        )
    }
}

/** Replaces the whole lineup atomically; the list order is the household order. */
suspend fun BackendStore.saveLineup(entries: List<LineupEntryRecord>, at: Instant) = query {
    LineupOfferings.deleteAll()
    LineupEntries.deleteAll()
    entries.withIndex().chunked(INSERT_CHUNK).forEach { chunk ->
        LineupEntries.batchInsert(chunk, shouldReturnGeneratedValues = false) { (position, entry) ->
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
            this[LineupEntries.createdBy] = entry.createdBy
            this[LineupEntries.autoDisabledAt] = entry.autoDisabledAt?.toString()
            this[LineupEntries.autoDisabledReason] = entry.autoDisabledReason
        }
    }
    entries.flatMap { entry -> entry.offeringIds.withIndex().map { (position, offeringId) -> Triple(entry.id, position, offeringId) } }
        .chunked(INSERT_CHUNK).forEach { chunk ->
            LineupOfferings.batchInsert(chunk, shouldReturnGeneratedValues = false) { (entryId, position, offeringId) ->
                this[LineupOfferings.entryId] = entryId
                this[LineupOfferings.position] = position
                this[LineupOfferings.offeringId] = offeringId
            }
        }
    Unit
}

class StoredGuide(val guideId: String, val importedAt: Instant, val channels: List<GuideChannel>, val programmeEnds: Map<String, Instant>, val programmes: Int)

/** Each guide's current generation of rows, as the import last published it. */
private fun currentRows(table: Table, guideId: Column<String>, generation: Column<Long>) =
    table.innerJoin(GuideImports, { guideId }, { GuideImports.guideId }) { generation eq GuideImports.generation }

suspend fun BackendStore.guides(): Map<String, StoredGuide> = read {
    val channels = currentRows(GuideChannels, GuideChannels.guideId, GuideChannels.generation)
        .select(GuideChannels.columns).groupBy({ it[GuideChannels.guideId] }) {
            GuideChannel(it[GuideChannels.channelId], it[GuideChannels.displayName], it[GuideChannels.icon])
        }
    val maxEnd = GuideProgrammes.endsAt.max()
    val count = GuideProgrammes.channelId.count()
    val ends = currentRows(GuideProgrammes, GuideProgrammes.guideId, GuideProgrammes.generation)
        .select(GuideProgrammes.guideId, GuideProgrammes.channelId, maxEnd, count)
        .groupBy(GuideProgrammes.guideId, GuideProgrammes.channelId)
        .groupBy({ it[GuideProgrammes.guideId] }) { Triple(it[GuideProgrammes.channelId], Instant.ofEpochSecond(it[maxEnd]!!), it[count]) }
    GuideImports.selectAll().associate { row ->
        val id = row[GuideImports.guideId]
        val rows = ends[id].orEmpty()
        id to StoredGuide(id, Instant.parse(row[GuideImports.importedAt]), channels[id].orEmpty(), rows.associate { it.first to it.second }, rows.sumOf { it.third.toInt() })
    }
}

/**
 * Replaces one guide's channels and retained programmes, then refreshes the planner statistics the import shifted.
 * The new rows go in as the guide's next generation over several short transactions, so other writes interleave with
 * a large import; one small transaction then makes them current, and the old generation is deleted in steps. Readers
 * see the old rows or the new ones, never a mix; a failed import leaves the old rows current and its own for the next
 * import to clear.
 */
suspend fun BackendStore.saveGuide(guideId: String, importedAt: Instant, channels: List<GuideChannel>, programmes: List<GuideProgramme>) {
    val current = query { GuideImports.select(GuideImports.generation).where { GuideImports.guideId eq guideId }.singleOrNull()?.get(GuideImports.generation) }
    deleteGuideRows(guideId, except = current)
    val next = (current ?: 0) + 1
    query {
        channels.distinctBy { it.id }.chunked(INSERT_CHUNK).forEach { chunk ->
            GuideChannels.batchInsert(chunk, shouldReturnGeneratedValues = false) { channel ->
                this[GuideChannels.guideId] = guideId
                this[GuideChannels.generation] = next
                this[GuideChannels.channelId] = channel.id
                this[GuideChannels.displayName] = channel.displayName
                this[GuideChannels.icon] = channel.icon
            }
        }
    }
    programmes.chunked(PROGRAMMES_PER_TRANSACTION).forEach { part ->
        query {
            part.chunked(INSERT_CHUNK).forEach { chunk ->
                GuideProgrammes.batchInsert(chunk.map(GuideProgramme::bounded), ignore = true, shouldReturnGeneratedValues = false) { programme ->
                    this[GuideProgrammes.guideId] = guideId
                    this[GuideProgrammes.generation] = next
                    this[GuideProgrammes.channelId] = programme.channelId
                    this[GuideProgrammes.startsAt] = programme.startsAt.epochSecond
                    this[GuideProgrammes.endsAt] = programme.endsAt.epochSecond
                    this[GuideProgrammes.title] = programme.title
                    this[GuideProgrammes.description] = programme.description
                    this[GuideProgrammes.detailsJson] = detailsJson(programme)
                }
            }
        }
    }
    query {
        GuideImports.upsert {
            it[GuideImports.guideId] = guideId
            it[GuideImports.importedAt] = importedAt.toString()
            it[generation] = next
        }
        Unit
    }
    deleteGuideRows(guideId, except = next)
    optimize()
}

/** Deletes [guideId]'s channel and programme rows of every generation but [except] (null: all), a step at a time. */
private suspend fun BackendStore.deleteGuideRows(guideId: String, except: Long?) {
    query { GuideChannels.deleteWhere { (GuideChannels.guideId eq guideId) and (if (except == null) Op.TRUE else GuideChannels.generation neq except) } }
    val args = listOf<Pair<IColumnType<*>, Any?>>(TextColumnType() to guideId, LongColumnType() to (except ?: -1L), IntegerColumnType() to PROGRAMMES_PER_TRANSACTION)
    while (true) {
        val deleted = query {
            exec(
                "DELETE FROM guide_programme WHERE rowid IN (SELECT rowid FROM guide_programme WHERE guide_id = ? AND generation <> ? LIMIT ?)",
                args,
            )
            exec("SELECT changes()") { it.next(); it.getInt(1) } ?: 0
        }
        if (deleted < PROGRAMMES_PER_TRANSACTION) break
    }
}

private val programmeJson = Json { encodeDefaults = false; ignoreUnknownKeys = true; explicitNulls = false }

/** A programme's metadata beyond title and description, under short keys to keep a million rows small. */
@Serializable private data class StoredDetails(
    @SerialName("s") val subTitle: String? = null,
    @SerialName("e") val episodeNumbers: List<EpisodeNumber> = emptyList(),
    @SerialName("i") val icon: String? = null,
    @SerialName("c") val categories: List<String> = emptyList(),
    @SerialName("r") val rating: ProgrammeRating? = null,
    @SerialName("n") val isNew: Boolean = false,
    @SerialName("p") val previouslyShown: PreviouslyShown? = null,
    @SerialName("d") val date: String? = null,
    @SerialName("l") val language: String? = null,
)

private fun detailsJson(programme: GuideProgramme): String? {
    val details = StoredDetails(
        programme.subTitle, programme.episodeNumbers, programme.icon, programme.categories, programme.rating, programme.isNew,
        programme.previouslyShown, programme.date, programme.language,
    )
    return if (details == StoredDetails()) null else programmeJson.encodeToString(details)
}

private fun programmeAt(row: ResultRow): GuideProgramme {
    val details = row[GuideProgrammes.detailsJson]?.let { stored ->
        runCatching { programmeJson.decodeFromString<StoredDetails>(stored) }.getOrNull()
    } ?: StoredDetails()
    return GuideProgramme(
        row[GuideProgrammes.channelId], Instant.ofEpochSecond(row[GuideProgrammes.startsAt]), Instant.ofEpochSecond(row[GuideProgrammes.endsAt]),
        row[GuideProgrammes.title], row[GuideProgrammes.description], details.subTitle, details.episodeNumbers, details.icon, details.categories,
        details.rating, details.isNew, details.previouslyShown, details.date, details.language,
    )
}

/** Programmes overlapping [from, to) for the given guide channels, grouped by channel and ordered by start. */
suspend fun BackendStore.programmes(guideId: String, channelIds: Collection<String>, from: Instant, to: Instant): Map<String, List<GuideProgramme>> = read {
    if (channelIds.isEmpty()) {
        emptyMap()
    } else {
        currentRows(GuideProgrammes, GuideProgrammes.guideId, GuideProgrammes.generation).select(GuideProgrammes.columns).where {
            (GuideProgrammes.guideId eq guideId) and (GuideProgrammes.channelId inList channelIds.toSet()) and
                (GuideProgrammes.startsAt less to.epochSecond) and (GuideProgrammes.endsAt greater from.epochSecond)
        }.orderBy(GuideProgrammes.startsAt).groupBy({ it[GuideProgrammes.channelId] }, ::programmeAt)
    }
}
