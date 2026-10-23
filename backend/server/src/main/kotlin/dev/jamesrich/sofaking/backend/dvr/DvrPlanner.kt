package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.contract.Wire
import java.time.Duration
import java.time.Instant

/** One guide program on one lineup channel, as a rule sees it. */
data class Airing(val channelId: String, val channelName: String, val program: GuideProgram)

/**
 * What the scheduler does to the stored recordings after one pass: airings to add and
 * rule-scheduled recordings to drop.
 */
data class Plan(val add: List<Pair<StoredRule, Airing>>, val drop: List<StoredRecording>)

/**
 * The decisions behind the DVR's schedule, kept apart from storage and time so they test on their own: which
 * airings a rule takes, which it has already, and which scheduled recordings overlap more than the slots allow.
 */
object DvrPlanner {
    /** States that count as having an episode: one is scheduled, running or kept. */
    private val HELD =
        setOf(Wire.RecordingState.SCHEDULED, Wire.RecordingState.RECORDING, Wire.RecordingState.COMPLETED)

    /** A guide that marks neither `new` nor `previously-shown` makes every airing new. */
    fun isNew(program: GuideProgram): Boolean = program.isNew || program.previouslyShown == null

    fun matches(rule: StoredRule, airing: Airing): Boolean =
        airing.program.title.trim().equals(rule.title.trim(), ignoreCase = true) &&
            (rule.channelId == null || rule.channelId == airing.channelId) &&
            (!rule.newOnly || isNew(airing.program))

    /**
     * What makes two airings of a show the same episode: its season and episode numbers, else its episode title.
     * Null when the guide gives neither, so every airing counts as its own (a daily news show).
     */
    fun episodeKey(title: String, season: Int?, episode: Int?, episodeTitle: String?): String? {
        val show = title.trim().lowercase()
        return when {
            season != null && episode != null -> "$show|s$season|e$episode"
            !episodeTitle.isNullOrBlank() -> "$show|t|${episodeTitle.trim().lowercase()}"
            else -> null
        }
    }

    private fun key(airing: Airing): String? {
        val name = RecordingTitle.of(airing.program)
        return episodeKey(name.show, name.numbering?.season, name.numbering?.episode, name.episodeTitle)
    }

    private fun key(recording: StoredRecording): String? =
        episodeKey(recording.title, recording.season, recording.episode, recording.episodeTitle)

    /**
     * The airings [rules] add, by priority, and the scheduled recordings a rule made that no longer match anything in
     * [airings] (the guide moved or the rule changed). An airing is skipped when a recording already holds its channel
     * and start, whatever its state, or holds the same episode; and one already ended is never added.
     */
    fun plan(rules: List<StoredRule>, airings: List<Airing>, existing: List<StoredRecording>, now: Instant): Plan {
        val taken = existing.mapTo(HashSet()) { it.channelId to it.startsAt }
        val episodes = existing.filter { it.state in HELD }.mapNotNullTo(HashSet(), ::key)
        val add = mutableListOf<Pair<StoredRule, Airing>>()
        val wanted = HashSet<Pair<String, Instant>>()
        for (rule in rules.sortedByDescending { it.priority }) {
            for (airing in airings.filter { matches(rule, it) }.sortedBy { it.program.startsAt }) {
                val slot = airing.channelId to airing.program.startsAt
                wanted += slot
                if (airing.program.endsAt <= now || slot in taken) continue
                val episode = key(airing)
                if (episode != null && !episodes.add(episode)) continue
                taken += slot
                add += rule to airing
            }
        }
        val drop = existing.filter {
            it.ruleId != null && it.state == Wire.RecordingState.SCHEDULED &&
                it.recordFrom > now && (it.channelId to it.startsAt) !in wanted
        }
        return Plan(add, drop)
    }

    /**
     * The scheduled and running recordings that cannot all get one of [capacity] playback slots: a recording conflicts
     * when, at some moment in its window, as many others that outrank it are running too. A one-off outranks every
     * rule, a higher rule priority outranks a lower one, and the earlier-made recording wins a tie.
     */
    fun conflicts(recordings: List<StoredRecording>, priorities: Map<String, Int>, capacity: Int): Set<String> {
        val active = recordings.filter {
            it.state == Wire.RecordingState.SCHEDULED || it.state == Wire.RecordingState.RECORDING
        }
        fun rank(r: StoredRecording) = if (r.ruleId == null) Int.MAX_VALUE else priorities[r.ruleId] ?: 0
        val ordered = active.sortedWith(
            compareByDescending<StoredRecording> {
                if (it.state == Wire.RecordingState.RECORDING) {
                    1
                } else {
                    0
                }
            }.thenByDescending(::rank).thenBy { it.createdAt },
        )
        val placed = mutableListOf<StoredRecording>()
        val conflicting = HashSet<String>()
        for (recording in ordered) {
            val overlapping = placed.filter {
                it.recordFrom < recording.recordUntil && recording.recordFrom < it.recordUntil
            }
            // The most that run at once within this window is the peak among the overlapping windows' starts.
            val peak =
                (overlapping.map { it.recordFrom } + recording.recordFrom).filter {
                    it >= recording.recordFrom && it < recording.recordUntil
                }
                    .maxOfOrNull { moment -> overlapping.count { it.recordFrom <= moment && moment < it.recordUntil } }
                    ?: 0
            if (peak >= capacity) conflicting += recording.id else placed += recording
        }
        return conflicting
    }

    /**
     * The finished recordings [retention] and the rules' keep counts no longer keep, each with its reason: `KEEP` for
     * a rule's oldest beyond its count (protected ones count but stay) and `AGE` for any older than the keep days.
     */
    fun cull(
        recordings: List<StoredRecording>,
        rules: Map<String, StoredRule>,
        retention: Retention,
        now: Instant,
    ): List<Pair<StoredRecording, String>> {
        val completed = recordings.filter { it.state == Wire.RecordingState.COMPLETED }
        val out = LinkedHashMap<String, Pair<StoredRecording, String>>()
        for ((ruleId, held) in completed.filter { it.ruleId != null }.groupBy { it.ruleId!! }) {
            val keep = rules[ruleId]?.keep ?: 0
            if (keep > 0) {
                held.sortedByDescending { it.startsAt }.drop(keep).filterNot { it.protected }.forEach {
                    out[it.id] =
                        it to "KEEP"
                }
            }
        }
        if (retention.keepDays > 0) {
            val cutoff = now.minus(Duration.ofDays(retention.keepDays.toLong()))
            completed.filter {
                !it.protected && it.endsAt < cutoff && it.id !in out
            }.forEach { out[it.id] = it to "AGE" }
        }
        return out.values.toList()
    }

    /**
     * The order the culler deletes in when space runs short: watched before unwatched, then
     * oldest first; never a protected one.
     */
    fun byCullOrder(recordings: List<StoredRecording>): List<StoredRecording> =
        recordings.filter { it.state == Wire.RecordingState.COMPLETED && !it.protected }
            .sortedWith(compareByDescending<StoredRecording> { it.watched }.thenBy { it.startsAt })
}
