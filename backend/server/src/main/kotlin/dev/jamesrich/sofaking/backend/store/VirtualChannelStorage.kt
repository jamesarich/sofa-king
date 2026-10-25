package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.MINUTES_PER_HOUR
import dev.jamesrich.sofaking.backend.api.VirtualBreaks
import dev.jamesrich.sofaking.backend.api.VirtualChannel
import dev.jamesrich.sofaking.backend.api.VirtualSlot
import dev.jamesrich.sofaking.backend.api.VirtualSource
import dev.jamesrich.sofaking.backend.virtual.Airing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.temporal.ChronoUnit

/** What a virtual channel keeps beside its name, number and slots. */
@Serializable private class StoredSettings(
    val filler: VirtualSource? = null,
    val padMinutes: Int = 0,
    val breaks: VirtualBreaks? = null,
)

/** The virtual channels in the order they were made, each with its slots. */
suspend fun BackendStore.virtualChannels(): List<VirtualChannel> = read {
    val slots = VirtualSlots.selectAll().orderBy(VirtualSlots.position).groupBy({ it[VirtualSlots.channelId] }) {
        VirtualSlot(
            Json.decodeFromString(it[VirtualSlots.sourceJson]),
            it[VirtualSlots.shuffle],
            it[VirtualSlots.startMinute]?.let { minute ->
                "%02d:%02d".format(
                    minute / MINUTES_PER_HOUR,
                    minute % MINUTES_PER_HOUR,
                )
            },
        )
    }
    VirtualChannels.selectAll().orderBy(VirtualChannels.createdAt to SortOrder.ASC, VirtualChannels.id to SortOrder.ASC)
        .map {
            val settings = Json.decodeFromString<StoredSettings>(it[VirtualChannels.settingsJson])
            VirtualChannel(
                it[VirtualChannels.id],
                it[VirtualChannels.name],
                it[VirtualChannels.number],
                slots[it[VirtualChannels.id]].orEmpty(),
                settings.filler,
                settings.padMinutes,
                settings.breaks,
            )
        }
}

/**
 * Saves [channel], a new one or a change to one, with its slots; a change drops the airings that start after [keepUntil],
 * so the schedule is built again from there.
 */
suspend fun BackendStore.saveVirtualChannel(channel: VirtualChannel, now: Instant, keepUntil: Instant) = query {
    val settings = Json.encodeToString(StoredSettings(channel.filler, channel.padMinutes, channel.breaks))
    val changed = VirtualChannels.update({ VirtualChannels.id eq channel.id }) {
        it[name] = channel.name
        it[number] = channel.number
        it[settingsJson] = settings
        it[updatedAt] = now.toString()
    }
    if (changed == 0) {
        VirtualChannels.insert {
            it[id] = channel.id
            it[name] = channel.name
            it[number] = channel.number
            it[settingsJson] = settings
            it[createdAt] = now.toString()
            it[updatedAt] = now.toString()
        }
    }
    VirtualSlots.deleteWhere { channelId eq channel.id }
    VirtualSlots.batchInsert(channel.slots.withIndex()) { (index, slot) ->
        this[VirtualSlots.channelId] = channel.id
        this[VirtualSlots.position] = index
        this[VirtualSlots.sourceJson] = Json.encodeToString(slot.source)
        this[VirtualSlots.shuffle] = slot.shuffle
        this[VirtualSlots.startMinute] = slot.start?.let { minuteOf(it) }
    }
    VirtualAirings.deleteWhere { (channelId eq channel.id) and (startsAt greaterEq stamp(keepUntil)) }
    Unit
}

/** Deletes virtual channel [id], its slots and its schedule; false when there is none. */
suspend fun BackendStore.deleteVirtualChannel(id: String): Boolean = query {
    VirtualSlots.deleteWhere { channelId eq id }
    VirtualAirings.deleteWhere { channelId eq id }
    VirtualChannels.deleteWhere { VirtualChannels.id eq id } > 0
}

/** Channel [id]'s airings that start at or after [from], in order. */
suspend fun BackendStore.virtualAirings(id: String, from: Instant): List<Airing> = read {
    VirtualAirings.selectAll().where {
        (VirtualAirings.channelId eq id) and
            (VirtualAirings.startsAt greaterEq stamp(from))
    }
        .orderBy(VirtualAirings.startsAt)
        .map {
            Airing(
                Instant.parse(it[VirtualAirings.startsAt]),
                Instant.parse(it[VirtualAirings.endsAt]),
                it[VirtualAirings.itemId],
                it[VirtualAirings.slot],
                it[VirtualAirings.filler],
            )
        }
}

/** Adds [airings] to channel [id]'s schedule and forgets the ones that ended before [forgetBefore]. */
suspend fun BackendStore.addVirtualAirings(id: String, airings: List<Airing>, forgetBefore: Instant) = query {
    VirtualAirings.deleteWhere { (channelId eq id) and (endsAt less stamp(forgetBefore)) }
    VirtualAirings.batchInsert(airings) { airing ->
        this[VirtualAirings.channelId] = id
        this[VirtualAirings.startsAt] = stamp(airing.startsAt)
        this[VirtualAirings.endsAt] = stamp(airing.endsAt)
        this[VirtualAirings.itemId] = airing.itemId
        this[VirtualAirings.slot] = airing.slot
        this[VirtualAirings.filler] = airing.filler
    }
    Unit
}

/** An instant in whole seconds, so stored times sort as text. */
private fun stamp(at: Instant): String = at.truncatedTo(ChronoUnit.SECONDS).toString()

/** Minutes after midnight of an `HH:MM` time. */
internal fun minuteOf(time: String): Int {
    val (hours, minutes) = time.split(':').map { it.toInt() }
    return hours * MINUTES_PER_HOUR + minutes
}
