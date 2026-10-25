package dev.jamesrich.sofaking.backend.virtual

import dev.jamesrich.sofaking.backend.api.VirtualAiring
import dev.jamesrich.sofaking.backend.api.VirtualBreaks
import dev.jamesrich.sofaking.backend.api.VirtualChannel
import dev.jamesrich.sofaking.backend.api.VirtualSource
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.library.LibraryCatalog
import dev.jamesrich.sofaking.backend.library.Snapshot
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.addVirtualAirings
import dev.jamesrich.sofaking.backend.store.deleteVirtualChannel
import dev.jamesrich.sofaking.backend.store.minuteOf
import dev.jamesrich.sofaking.backend.store.saveVirtualChannel
import dev.jamesrich.sofaking.backend.store.virtualAirings
import dev.jamesrich.sofaking.backend.store.virtualChannels
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import kotlin.random.Random

/**
 * The virtual channels: each a schedule of library items that the server plays as a live channel. The schedule is
 * built [AHEAD] ahead and stored, so the guide, the stream and a restart agree on what airs when; it is extended
 * whenever less than [REFILL] of it is left, and an edit rebuilds it from the end of what airs now. [onChanged] hears
 * of every channel added, changed or removed, and [zone] is where the slots' times of day are. [live] is the lineup a
 * slot can play a live channel from, set once the playback sessions exist.
 */
class VirtualChannelService(
    private val store: BackendStore,
    private val catalog: LibraryCatalog,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val random: Random = Random.Default,
    private val onChanged: () -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(VirtualChannelService::class.java)
    private val lock = Mutex()

    @Volatile var live: LiveLineup? = null

    suspend fun channels(): List<VirtualChannel> = store.virtualChannels()

    suspend fun channel(id: String): VirtualChannel = channels().firstOrNull { it.id == id } ?: throw missing()

    suspend fun create(channel: VirtualChannel): VirtualChannel = save(channel.copy(id = newId()))

    suspend fun update(id: String, channel: VirtualChannel): VirtualChannel {
        channel(id)
        return save(channel.copy(id = id))
    }

    suspend fun delete(id: String) {
        if (!lock.withLock { store.deleteVirtualChannel(id) }) throw missing()
        log.info("virtual channel deleted id={}", id)
        onChanged()
    }

    /** Channel [id]'s airings from [from] until [to], built as far as the schedule reaches. */
    suspend fun schedule(id: String, from: Instant, to: Instant): List<VirtualAiring> {
        val airings = airings(channel(id), from, to)
        val snapshot = catalog.snapshot()
        val lineup = live?.choices().orEmpty()
        return airings.map { airing ->
            val entry = liveEntryOf(airing.itemId)
            val slide = slideOf(airing.itemId)
            val title = when {
                entry != null -> lineup[entry]?.let { "Live: $it" } ?: GONE_LIVE
                slide != null -> "Break slide ${slide + 1}"
                airing.itemId == null -> BLANK
                else -> snapshot.playable(airing.itemId)?.title ?: GONE
            }
            VirtualAiring(airing.startsAt.toString(), airing.endsAt.toString(), title, airing.itemId, airing.filler)
        }
    }

    /** The airing of channel [id] that holds [at], and the ones after it until [until]. */
    internal suspend fun airingsFrom(id: String, at: Instant, until: Instant): List<Airing> =
        airings(channel(id), at, until).dropWhile { it.endsAt <= at }

    /** The item [id] as a channel airs it, or null when it is gone from the libraries. */
    internal suspend fun playable(id: String): Playable? = catalog.snapshot().playable(id)

    /**
     * The guide of channels [wanted] from now until the schedule's end. Filler and blank go to the item before them, so
     * the guide lists items only; a stretch with no item before it is listed under the channel's name.
     */
    internal suspend fun programs(wanted: Set<String>): List<GuideProgram> {
        val now = clock.instant()
        val snapshot = catalog.snapshot()
        return channels().filter { it.id in wanted }.flatMap { channel ->
            val programs = ArrayList<GuideProgram>()
            for (airing in airings(channel, now, now.plus(AHEAD)).dropWhile { it.endsAt <= now }) {
                val entry = liveEntryOf(airing.itemId)
                if (entry != null) {
                    programs += live.programsOf(channel, entry, airing)
                    continue
                }
                val item = airing.itemId?.takeUnless { airing.filler }?.let { snapshot.playable(it) }
                val last = programs.lastOrNull()
                programs += when {
                    item != null -> item.program(channel.id, airing.startsAt, airing.endsAt)
                    last != null -> last.copy(endsAt = airing.endsAt).also { programs.removeAt(programs.size - 1) }
                    else -> GuideProgram(channel.id, airing.startsAt, airing.endsAt, channel.name)
                }
            }
            programs
        }
    }

    private suspend fun save(channel: VirtualChannel): VirtualChannel {
        val clean = validated(channel, catalog.snapshot(), live?.choices().orEmpty())
        lock.withLock {
            store.saveVirtualChannel(clean, clock.instant(), keepUntil = clock.instant())
        }
        log.info("virtual channel saved id={} slots={}", clean.id, clean.slots.size)
        airings(clean, clock.instant(), clock.instant())
        onChanged()
        return clean
    }

    /** Channel [channel]'s stored airings from [from] until [to], extending the schedule first when it runs short. */
    private suspend fun airings(channel: VirtualChannel, from: Instant, to: Instant): List<Airing> = lock.withLock {
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val kept = store.virtualAirings(channel.id, now.minus(HISTORY))
        val end = kept.lastOrNull()?.endsAt
        if (end == null || end < now.plus(REFILL) || end < to) {
            // A schedule that starts now starts a little earlier, so its stream has segments to list at once.
            val start = if (end == null || end < now) now.minus(BACKDATE) else end
            val history = kept.filter { it.endsAt <= start }
            val added = catalog.snapshot().builder(channel, history, zone, random)
                .extend(start, maxOf(now.plus(AHEAD), to))
            store.addVirtualAirings(channel.id, added, forgetBefore = now.minus(HISTORY))
            log.info("virtual schedule extended id={} airings={} until={}", channel.id, added.size, added.last().endsAt)
            (kept.filter { it.endsAt <= start } + added)
        } else {
            kept
        }.filter { it.endsAt > from && it.startsAt < to.coerceAtLeast(from.plusSeconds(1)) }
    }

    companion object {
        /** How far ahead the schedule is built, and how little of it may be left before it is built further. */
        val AHEAD: Duration = Duration.ofHours(48)
        val REFILL: Duration = Duration.ofHours(24)

        /** How long before now a schedule built afresh starts. */
        val BACKDATE: Duration = Duration.ofMinutes(1)

        /** How long past airings are kept, for the order and the shuffle to continue from. */
        val HISTORY: Duration = Duration.ofDays(7)

        const val BLANK = "Blank"
        const val GONE = "Missing item"
        const val GONE_LIVE = "Missing live channel"
        private const val NAME_LIMIT = 100
        private const val SLOT_LIMIT = 50
        private val PAD_MINUTES = setOf(0, 15, 30, 60)
        private val SLIDE_SECONDS = 3..60
        private const val SLIDE_LIMIT = 5
        private val NUMBER = Regex("[0-9]{1,5}(\\.[0-9]{1,3})?")
        private val TIME = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

        private fun missing() = ApiFailure(HttpStatusCode.NotFound, "NOT_FOUND", "No such virtual channel")

        private fun invalid(message: String): Nothing =
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", message)

        /** [channel] trimmed, or a 400 naming what is wrong with it. */
        internal fun validated(channel: VirtualChannel, snapshot: Snapshot, live: Map<String, String>): VirtualChannel {
            val name = channel.name.trim()
            if (name.isEmpty() || name.length > NAME_LIMIT) invalid("A name of 1 to $NAME_LIMIT characters is required")
            val number = channel.number?.trim()?.ifEmpty { null }
            if (number != null && !number.matches(NUMBER)) invalid("The number must be digits, such as 2001 or 20.1")
            if (channel.slots.isEmpty() ||
                channel.slots.size > SLOT_LIMIT
            ) {
                invalid("1 to $SLOT_LIMIT slots are required")
            }
            if (channel.padMinutes !in PAD_MINUTES) invalid("padMinutes must be one of ${PAD_MINUTES.joinToString()}")
            val starts = channel.slots.map { it.start?.trim()?.ifEmpty { null } }
            if (starts.any { it != null } && starts.any { it == null }) invalid("Give every slot a start time, or none")
            if (starts.filterNotNull().any { !it.matches(TIME) }) invalid("A start time is HH:MM, from 00:00 to 23:59")
            if (starts.filterNotNull().distinct().size !=
                starts.filterNotNull().size
            ) {
                invalid("Two slots start together")
            }
            checkSources(channel, timed = starts.all { it != null }, snapshot, live)
            return channel.copy(
                name = name,
                number = number,
                slots = channel.slots.mapIndexed { i, slot ->
                    slot.copy(source = slot.source.trimmed(), start = starts[i])
                },
                filler = channel.filler?.trimmed(),
                breaks = channel.breaks?.let { checkedBreaks(it, snapshot) },
            )
        }

        /** [breaks] trimmed, null when they air nothing, or a 400 naming what is wrong with them. */
        private fun checkedBreaks(breaks: VirtualBreaks, snapshot: Snapshot): VirtualBreaks? {
            if (breaks.everyMinutes !in
                PAD_MINUTES
            ) {
                invalid("everyMinutes must be one of ${PAD_MINUTES.joinToString()}")
            }
            if (breaks.slideSeconds !in SLIDE_SECONDS) invalid("slideSeconds must be from 3 to 60")
            val slides = breaks.slides.map { it.trim() }.filter { it.isNotEmpty() }
            if (slides.size > SLIDE_LIMIT) invalid("A break shows at most $SLIDE_LIMIT slides")
            slides.firstOrNull { !isWebUrl(it) }?.let { invalid("A slide is an http or https image URL") }
            val source = breaks.source?.trimmed()
            if (source?.kind == SourceKind.CHANNEL) invalid("A live channel cannot air in a break")
            if (source != null && !snapshot.exists(source)) invalid("No ${source.kind} ${source.value}")
            return breaks.copy(source = source, slides = slides).takeIf { source != null || slides.isNotEmpty() }
        }

        private fun isWebUrl(text: String): Boolean = runCatching { URI(text) }.getOrNull()
            ?.let { it.scheme in setOf("http", "https") && !it.host.isNullOrEmpty() } == true

        /** A 400 for a source of [channel] that does not exist or a live channel where one cannot play. */
        private fun checkSources(
            channel: VirtualChannel,
            timed: Boolean,
            snapshot: Snapshot,
            live: Map<String, String>,
        ) {
            val sources = channel.slots.map { it.source.trimmed() } + listOfNotNull(channel.filler?.trimmed())
            if (!timed &&
                sources.any { it.kind == SourceKind.CHANNEL }
            ) {
                invalid("A live channel plays in a timed slot only")
            }
            if (channel.filler?.kind == SourceKind.CHANNEL) invalid("A live channel cannot be the filler")
            sources.firstOrNull { if (it.kind == SourceKind.CHANNEL) it.value !in live else !snapshot.exists(it) }
                ?.let { invalid("No ${it.kind} ${it.value}") }
        }

        private fun VirtualSource.trimmed(): VirtualSource {
            if (kind !in SourceKind.all) invalid("A source kind is one of ${SourceKind.all.joinToString()}")
            if (value.isBlank()) invalid("A source needs a value")
            return VirtualSource(kind, value.trim())
        }
    }
}

/** The schedule builder of [channel], over this snapshot's items, continuing from [history]. */
private fun Snapshot.builder(
    channel: VirtualChannel,
    history: List<Airing>,
    zone: ZoneId,
    random: Random,
): ScheduleBuilder {
    val slots = channel.slots.mapIndexed { index, slot ->
        val live = slot.source.takeIf { it.kind == SourceKind.CHANNEL }?.let { liveAiringId(it.value) }
        ScheduleSlot(index, playables(slot.source).map { it.pool }, slot.shuffle, slot.start?.let(::minuteOf), live)
    }
    val filler = channel.filler?.let { source -> playables(source).map { it.pool } }.orEmpty()
    val breaks = channel.breaks?.let { plan ->
        BreakPlan(
            Duration.ofMinutes(plan.everyMinutes.toLong()).toMillis(),
            plan.source?.let { source -> playables(source).map { it.pool } }.orEmpty(),
            plan.slides.indices.map(::slideAiringId),
            Duration.ofSeconds(plan.slideSeconds.toLong()).toMillis(),
        )
    }
    return ScheduleBuilder(slots, filler, channel.padMinutes, zone, history, random, breaks)
}

private fun newId(): String = HexFormat.of().formatHex(ByteArray(ID_BYTES).also(SecureRandom()::nextBytes))

private const val ID_BYTES = 6

/**
 * What the guide lists on live channel [entry] during [airing], cut to it and moved to [channel]; the entry's name
 * where its guide has nothing.
 */
private suspend fun LiveLineup?.programsOf(channel: VirtualChannel, entry: String, airing: Airing): List<GuideProgram> {
    val listed = this?.programs(entry, airing.startsAt, airing.endsAt).orEmpty()
        .filter { it.endsAt > airing.startsAt && it.startsAt < airing.endsAt }
        .map {
            it.copy(
                channelId = channel.id,
                startsAt = maxOf(it.startsAt, airing.startsAt),
                endsAt = minOf(it.endsAt, airing.endsAt),
            )
        }
    if (listed.isNotEmpty()) return listed
    val name = this?.choices()?.get(entry)?.substringAfter(' ') ?: channel.name
    return listOf(GuideProgram(channel.id, airing.startsAt, airing.endsAt, name))
}
