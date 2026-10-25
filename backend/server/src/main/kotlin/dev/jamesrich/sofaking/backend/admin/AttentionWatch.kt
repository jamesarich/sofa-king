package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.events.EventType
import dev.jamesrich.sofaking.backend.events.ServerEvents
import dev.jamesrich.sofaking.backend.launchEvery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How often the home page's attention items are read again for the event stream. */
private const val ATTENTION_EVERY_MS = 60_000L

/**
 * Publishes `attention.changed` when the home page's attention items change: an item that appears or changes tone is
 * raised, one that goes is cleared. An item is known by its key, so a title that only counts on ("failing for 3 h")
 * raises nothing new. The first read is the baseline and publishes nothing.
 */
internal class AttentionWatch(private val overview: OverviewReader, private val events: ServerEvents) {
    private var last: Map<String, Attention>? = null

    suspend fun check() {
        val now = overview.read().attention.associateBy { it.key }
        val before = last
        last = now
        if (before == null) return
        val raised = now.values.filter { before[it.key]?.tone != it.tone }
        val cleared = before.values.filter { it.key !in now }
        if (raised.isEmpty() && cleared.isEmpty()) return
        events.publish(
            EventType.ATTENTION_CHANGED,
            buildJsonObject {
                put("raised", raised.json())
                put("cleared", cleared.json())
            },
        )
    }

    fun start(scope: CoroutineScope): Job = scope.launchEvery("attention-watch", ATTENTION_EVERY_MS) { check() }
}

private fun List<Attention>.json(): JsonArray = buildJsonArray {
    for (item in this@json) {
        add(
            buildJsonObject {
                put("key", item.key)
                put("tone", item.tone.css)
                put("title", item.title)
                put("detail", item.detail)
                put("href", item.href)
            },
        )
    }
}
