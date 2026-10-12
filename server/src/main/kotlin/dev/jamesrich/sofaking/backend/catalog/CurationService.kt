package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.adoptedChannels
import dev.jamesrich.sofaking.backend.store.curationChanges
import dev.jamesrich.sofaking.backend.store.curationRules
import dev.jamesrich.sofaking.backend.store.curationSettings
import dev.jamesrich.sofaking.backend.store.deleteCurationRule
import dev.jamesrich.sofaking.backend.store.markAdopted
import dev.jamesrich.sofaking.backend.store.recordCurationChanges
import dev.jamesrich.sofaking.backend.store.saveCurationRule
import dev.jamesrich.sofaking.backend.store.saveCurationSettings
import dev.jamesrich.sofaking.backend.store.saveUnavailableSince
import dev.jamesrich.sofaking.backend.store.unavailableSince
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as Trigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

val RULE_KINDS: Set<String> = setOf("group", "correction", "alias", "equivalent", "adopt")

/** A rule with its patterns compiled once. */
internal class CompiledRule(val rule: CurationRule) {
    val id: String get() = rule.id
    val kind: String get() = rule.kind
    private val groupPattern = rule.match.groupPattern?.let { Regex(it, RegexOption.IGNORE_CASE) }
    private val namePattern = rule.match.namePattern?.let { Regex(it, RegexOption.IGNORE_CASE) }

    /** Every match field the rule gives must hold; `entryId` is checked by the caller. */
    fun matches(providerId: String?, group: String?, name: String, tvgId: String?): Boolean {
        val m = rule.match
        val wantedGroup = m.group
        val wantedName = m.name
        if (m.providerId != null && m.providerId != providerId) return false
        if (wantedGroup != null && (group == null || !group.trim().equals(wantedGroup.trim(), ignoreCase = true))) return false
        if (groupPattern != null && (group == null || !groupPattern.containsMatchIn(group))) return false
        if (wantedName != null && !name.trim().equals(wantedName.trim(), ignoreCase = true)) return false
        if (namePattern != null && !namePattern.containsMatchIn(name)) return false
        if (m.tvgId != null && m.tvgId != tvgId) return false
        return true
    }
}

/** One planned change; [applyTo] re-checks the entry as it stands and returns null when the change no longer holds. */
internal sealed class Step(val entryId: String, val action: CurationAction) {
    abstract fun applyTo(current: LineupEntryRecord, now: Instant, feeds: FeedService): LineupEntryRecord?

    class SetField(entryId: String, val field: String, val before: String?, val after: String?, action: CurationAction) : Step(entryId, action) {
        override fun applyTo(current: LineupEntryRecord, now: Instant, feeds: FeedService): LineupEntryRecord? {
            if (current.pinned(field)) return null
            val value = when (field) { "name" -> current.name; "group" -> current.group; "logo" -> current.logo; else -> return null }
            if (value != before) return null
            return when (field) {
                "name" -> current.copy(name = after ?: return null)
                "group" -> current.copy(group = after)
                else -> current.copy(logo = after)
            }
        }
    }

    class Attach(entryId: String, val offeringIds: List<String>, action: CurationAction) : Step(entryId, action) {
        override fun applyTo(current: LineupEntryRecord, now: Instant, feeds: FeedService): LineupEntryRecord? {
            if (current.pinned("offerings")) return null
            val add = offeringIds.filter { it !in current.offeringIds && feeds.offering(it) != null }
            if (add.isEmpty() || current.offeringIds.size + add.size > MAX_OFFERINGS) return null
            return current.copy(offeringIds = current.offeringIds + add)
        }
    }

    class SetGuide(entryId: String, val before: GuideMapping?, val after: GuideMapping, action: CurationAction) : Step(entryId, action) {
        override fun applyTo(current: LineupEntryRecord, now: Instant, feeds: FeedService): LineupEntryRecord? {
            if (current.pinned("guide") || current.guide != before) return null
            return current.copy(guide = after)
        }
    }

    class Disable(entryId: String, val reason: String, action: CurationAction) : Step(entryId, action) {
        override fun applyTo(current: LineupEntryRecord, now: Instant, feeds: FeedService): LineupEntryRecord? {
            if (current.pinned("enabled") || !current.enabled) return null
            return current.copy(enabled = false, autoDisabledAt = now, autoDisabledReason = reason)
        }
    }

    class Enable(entryId: String, action: CurationAction) : Step(entryId, action) {
        override fun applyTo(current: LineupEntryRecord, now: Instant, feeds: FeedService): LineupEntryRecord? {
            if (current.pinned("enabled") || current.enabled || current.autoDisabledAt == null) return null
            return current.copy(enabled = true, autoDisabledAt = null, autoDisabledReason = null)
        }
    }

    class Adopt(val channelId: String, val entry: LineupEntryRecord, action: CurationAction) : Step(entry.id, action) {
        override fun applyTo(current: LineupEntryRecord, now: Instant, feeds: FeedService): LineupEntryRecord? = null
    }

    companion object {
        const val MAX_OFFERINGS = 64
    }
}

/** A computed plan: the steps, what automation saw but will not change, and the availability rows to write (null deletes). */
internal class Plan(val generatedAt: Instant, val steps: List<Step>, val findings: List<String>, val availability: Map<String, Instant?>) {
    fun report(enabled: Boolean, actions: List<CurationAction> = steps.map { it.action }): CurationPlan =
        CurationPlan(generatedAt.toString(), enabled, actions, actions.groupingBy { it.kind }.eachCount().toSortedMap(), findings)
}

/**
 * Rule-driven curation of the lineup: groups, name and logo corrections, alias merges, equivalent-source attachment,
 * candidate adoption and health-based disabling, each a step that only touches unpinned fields. A run computes a plan
 * over the current lineup, feeds and health; with the settings enabled it applies the plan in one lineup transaction
 * and records every applied step in the change log. Availability is tracked on every run so the first enabled run
 * knows how long each entry has had no source.
 */
class CurationService(
    private val store: BackendStore,
    private val clock: Clock,
    private val feeds: FeedService,
    private val guides: GuideService,
    private val lineup: LineupService,
    /** Runs after the settings change, once they are published. */
    private val onSettingsChanged: () -> Unit = {},
    /** Quiet time after a trigger before the run, so a burst of health changes is one run. */
    private val debounceMillis: Long = 10_000,
    /** Runs after a run that applied changes to the lineup. */
    private val onLineupChanged: () -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(CurationService::class.java)
    private val mutex = Mutex()
    private val settings = AtomicReference(CurationSettings())
    private val rules = AtomicReference<List<CompiledRule>>(emptyList())
    private val unavailable = AtomicReference<Map<String, Instant>>(emptyMap())
    private val adopted = AtomicReference<Set<String>>(emptySet())
    private val trigger = Trigger<Unit>(Trigger.CONFLATED)

    suspend fun initialize() {
        settings.set(store.curationSettings() ?: CurationSettings())
        rules.set(store.curationRules().map(::CompiledRule))
        unavailable.set(store.unavailableSince())
        adopted.set(store.adoptedChannels())
    }

    fun settings(): CurationSettings = settings.get()

    fun retiredLogoHosts(): Set<String> = settings.get().retiredLogoHosts.toSet()

    fun rules(): List<CurationRule> = rules.get().map { it.rule }

    suspend fun updateSettings(request: CurationSettings): CurationSettings = mutex.withLock {
        fun invalid(message: String): Nothing = throw ApiFailure(400, "INVALID_REQUEST", message)
        if (request.intervalSeconds !in 60..86_400) invalid("intervalSeconds must be between 60 and 86400")
        if (request.disableAfterHours !in 1..720) invalid("disableAfterHours must be between 1 and 720")
        if (request.enableOn !in setOf("healthy", "eligible")) invalid("enableOn must be healthy or eligible")
        if (request.equivalentEvidence !in setOf("name-and-guide", "name")) invalid("equivalentEvidence must be name-and-guide or name")
        if (request.numberFloor !in 1..999_999) invalid("numberFloor must be between 1 and 999999")
        val hosts = request.retiredLogoHosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (hosts.any { !it.matches(Regex("[a-z0-9.\\-\\[\\]:]+")) }) invalid("retiredLogoHosts entries must be host or host:port")
        val group = request.defaultGroup?.trim()
        if (group != null && (group.isEmpty() || group.length > 128)) invalid("defaultGroup must be 1 to 128 characters")
        val retiredGuides = request.retiredGuides.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (retiredGuides.any { !it.matches(Regex("[A-Za-z0-9_.:-]{1,64}")) }) invalid("retiredGuides entries must be guide IDs")
        val next = request.copy(retiredLogoHosts = hosts, defaultGroup = group, retiredGuides = retiredGuides)
        store.saveCurationSettings(next)
        settings.set(next)
        log.info(
            "curation settings enabled={} disableAfterHours={} enableOn={} equivalentEvidence={} retiredLogoHosts={} retiredGuides={}",
            next.enabled, next.disableAfterHours, next.enableOn, next.equivalentEvidence, hosts.size, retiredGuides,
        )
        onSettingsChanged()
        requestRun()
        next
    }

    suspend fun createRule(request: CurationRule): CurationRule = mutex.withLock {
        val rule = validatedRule(request.copy(id = request.id.ifBlank { UUID.randomUUID().toString() }))
        if (rules.get().any { it.id == rule.id }) throw ApiFailure(409, "RULE_EXISTS", "Rule already exists")
        persist(listOf(rule))
        rule
    }

    suspend fun updateRule(id: String, request: CurationRule): CurationRule = mutex.withLock {
        if (rules.get().none { it.id == id }) throw ApiFailure(404, "RULE_NOT_FOUND", "Unknown rule")
        val rule = validatedRule(request.copy(id = id))
        persist(listOf(rule))
        rule
    }

    suspend fun deleteRule(id: String) = mutex.withLock {
        if (!store.deleteCurationRule(id)) throw ApiFailure(404, "RULE_NOT_FOUND", "Unknown rule")
        rules.set(rules.get().filterNot { it.id == id })
        requestRun()
    }

    suspend fun changes(limit: Int): List<CurationChange> = store.curationChanges(limit.coerceIn(1, 1000))

    /** The plan a run would apply now, without applying or recording anything. */
    suspend fun preview(): CurationPlan = plan(snapshot()).report(settings.get().enabled)

    /** One pass: track availability, then apply the plan when the settings allow it or [force] is set. */
    suspend fun run(force: Boolean = false): CurationPlan = mutex.withLock {
        val started = clock.instant()
        val context = snapshot()
        val plan = plan(context)
        if (plan.availability.isNotEmpty()) {
            store.saveUnavailableSince(plan.availability)
            unavailable.set(context.unavailable.toMutableMap().apply { for ((id, since) in plan.availability) if (since == null) remove(id) else put(id, since) })
        }
        val enabled = context.settings.enabled || force
        val applied = if (enabled) apply(plan) else emptyList()
        if (applied.isNotEmpty()) onLineupChanged()
        log.info(
            "curation run planned={} applied={} findings={} enabled={} durationMs={}",
            plan.steps.size, applied.size, plan.findings.size, enabled, Duration.between(started, clock.instant()).toMillis(),
        )
        if (enabled) plan.report(true, applied) else plan.report(false)
    }

    /** Asks the loop to run soon; requests before it runs coalesce into one. */
    fun requestRun() {
        trigger.trySend(Unit)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            select<Unit> {
                trigger.onReceive {}
                onTimeout(settings.get().intervalSeconds * 1000) {}
            }
            delay(debounceMillis)
            trigger.tryReceive()
            try { run() } catch (e: CancellationException) { throw e } catch (e: Exception) { log.warn("curation run failed class={} message=\"{}\"", e.javaClass.simpleName, redactedMessage(e)) }
        }
    }

    /** Translates the pipeline's data files into rules; see docs/curation.md for the shapes. */
    suspend fun import(request: CurationImport): CurationImportReport = mutex.withLock {
        val items = ArrayList<CurationImportItem>()
        val pending = ArrayList<CurationRule>()
        var position = (rules.get().maxOfOrNull { it.rule.position } ?: -1) + 1
        fun propose(source: String, item: String, rule: CurationRule) {
            val same = (rules.get().map { it.rule } + pending).firstOrNull { it.kind == rule.kind && it.match == rule.match && it.set == rule.set }
            if (same != null) { items += CurationImportItem(source, item, "exists", same.id); return }
            try {
                val existing = rules.get().firstOrNull { it.id == rule.id }
                val validated = validatedRule(rule.copy(position = existing?.rule?.position ?: position++))
                pending.removeIf { it.id == validated.id }
                pending += validated
                items += CurationImportItem(source, item, if (existing != null) "updated" else "created", validated.id)
            } catch (e: ApiFailure) {
                items += CurationImportItem(source, item, "skipped", reason = e.message)
            }
        }
        for ((raw, canonical) in request.groupMap) {
            propose("groupMap", raw, CurationRule("group-${hash(raw.trim().lowercase()).take(12)}", "group", match = RuleMatch(group = raw.trim()), set = RuleSet(group = canonical.trim())))
        }
        for ((alias, canonical) in request.aliases) {
            if (lineup.entry(alias) == null || lineup.entry(canonical) == null) { items += CurationImportItem("aliases", alias, "skipped", reason = "not both lineup entries"); continue }
            propose("aliases", alias, CurationRule("alias-${hash(alias).take(12)}", "alias", match = RuleMatch(entryId = alias), set = RuleSet(canonicalEntryId = canonical)))
        }
        for (record in request.equivalents) {
            val uuid = record.uuid?.trim(); val name = record.name?.trim()
            if (uuid.isNullOrEmpty() || name.isNullOrEmpty()) { items += CurationImportItem("equivalents", name ?: uuid ?: "record", "skipped", reason = "no uuid and name"); continue }
            if (lineup.entry(uuid) == null) { items += CurationImportItem("equivalents", name, "skipped", reason = "not a lineup entry"); continue }
            propose("equivalents", name, CurationRule("equivalent-${hash("$uuid:${name.lowercase()}").take(12)}", "equivalent", match = RuleMatch(entryId = uuid, name = name)))
        }
        for (record in request.sportsCorrections) {
            val before = record.beforeName?.trim(); val after = record.afterName?.trim()
            if (before.isNullOrEmpty() || after.isNullOrEmpty()) { items += CurationImportItem("sportsCorrections", record.id?.toString() ?: "record", "applied-by-import", reason = "no beforeName and afterName; the Dispatcharr import carried it"); continue }
            propose("sportsCorrections", before, CurationRule("correction-${hash(before.lowercase()).take(12)}", "correction", match = RuleMatch(name = before), set = RuleSet(name = after)))
        }
        if (request.corrections != null) items += CurationImportItem("corrections", "corrections", "applied-by-import", reason = "Dispatcharr ids; the Dispatcharr import carried their effect")
        if (!request.dryRun && pending.isNotEmpty()) persist(pending)
        CurationImportReport(
            request.dryRun, items.count { it.outcome == "created" }, items.count { it.outcome == "updated" }, items.count { it.outcome == "exists" },
            items.count { it.outcome == "skipped" }, items,
        )
    }

    private suspend fun persist(changed: List<CurationRule>) {
        for (rule in changed) store.saveCurationRule(rule)
        val ids = changed.mapTo(HashSet()) { it.id }
        rules.set((rules.get().filterNot { it.id in ids } + changed.map(::CompiledRule)).sortedWith(compareBy({ it.rule.position }, { it.id })))
        requestRun()
    }

    private fun validatedRule(rule: CurationRule): CurationRule {
        fun invalid(message: String): Nothing = throw ApiFailure(400, "INVALID_REQUEST", message)
        if (!rule.id.matches(Regex("[A-Za-z0-9_.:-]{1,64}"))) invalid("Rule ID must be 1 to 64 letters, digits, '_', '.', ':' or '-'")
        if (rule.kind !in RULE_KINDS) invalid("Rule kind must be one of ${RULE_KINDS.sorted().joinToString()}")
        val m = rule.match
        val s = rule.set
        fun text(value: String?, what: String, max: Int) { if (value != null && (value.isBlank() || value.length > max)) invalid("$what must be 1 to $max characters") }
        text(m.providerId, "match.providerId", 64); text(m.group, "match.group", 128); text(m.name, "match.name", 256); text(m.tvgId, "match.tvgId", 256)
        text(s.group, "set.group", 128); text(s.name, "set.name", 256)
        for (pattern in listOfNotNull(m.groupPattern, m.namePattern)) {
            if (pattern.isBlank() || pattern.length > 256) invalid("Patterns must be 1 to 256 characters")
            try { Regex(pattern) } catch (e: Exception) { invalid("Invalid pattern: ${e.message?.lineSequence()?.firstOrNull()}") }
        }
        if (m.providerId != null && feeds.configs.none { it.id == m.providerId }) invalid("Unknown provider ${m.providerId}")
        val logo = s.logo?.let { normalizeLogoUrl(it) ?: invalid("set.logo must be an HTTP(S) URL") }
        fun entry(id: String?, what: String) { if (id == null) invalid("$what is required"); if (lineup.entry(id) == null) invalid("$what names no lineup entry") }
        val candidateMatch = listOf(m.providerId, m.group, m.groupPattern, m.name, m.namePattern, m.tvgId).any { it != null }
        when (rule.kind) {
            "group" -> { if (!candidateMatch) invalid("A group rule needs a match field"); if (s.group == null) invalid("A group rule needs set.group") }
            "correction" -> {
                if (m.name == null && m.tvgId == null) invalid("A correction rule matches name or tvgId")
                if (s.name == null && s.group == null && logo == null) invalid("A correction rule sets name, group or logo")
            }
            "alias" -> { entry(m.entryId, "match.entryId"); entry(s.canonicalEntryId, "set.canonicalEntryId"); if (m.entryId == s.canonicalEntryId) invalid("An alias cannot be its own canonical entry") }
            "equivalent" -> { entry(m.entryId, "match.entryId"); if (m.name == null && m.tvgId == null) invalid("An equivalent rule matches name or tvgId") }
            "adopt" -> if (!candidateMatch) invalid("An adopt rule needs a match field")
        }
        return rule.copy(set = s.copy(logo = logo), note = rule.note?.trim()?.take(500))
    }

    internal class Context(
        val now: Instant, val settings: CurationSettings, val rules: List<CompiledRule>, val entries: List<LineupEntryRecord>,
        val states: Map<String, HealthState>, val unavailable: Map<String, Instant>, val adopted: Set<String>,
    )

    private suspend fun snapshot() = Context(clock.instant(), settings.get(), rules.get(), lineup.entries(), store.allHealth(), unavailable.get(), adopted.get())

    private fun candidatesOf(entry: LineupEntryRecord): List<Channel> =
        entry.offeringIds.mapNotNull(feeds::offering).mapNotNull { feeds.channel(it.channelId) }.distinctBy { it.id }

    /**
     * The first source, in the entry's order, whose `tvg-id` a live guide lists: its provider's own guide first, then a
     * guide that is not another provider's. Provider guides key channels by that provider's ids, so another
     * provider's guide listing the same id names a different channel.
     */
    private fun guideFor(candidates: List<Channel>, retired: Set<String>): GuideMapping? {
        val providerIds = feeds.configs.mapTo(HashSet()) { it.id }
        for (channel in candidates) {
            val tvg = channel.legacyGuideId ?: continue
            val provider = feeds.providerOf(channel.id)
            val listing = guides.guidesListing(tvg).filter { it !in retired }
            val guideId = listing.firstOrNull { it == provider } ?: listing.firstOrNull { it !in providerIds } ?: continue
            return GuideMapping(guideId, tvg)
        }
        return null
    }

    private fun eligibleNow(entry: LineupEntryRecord, states: Map<String, HealthState>, healthyOnly: Boolean): Boolean =
        entry.offeringIds.any { id ->
            feeds.offering(id) != null && states[id]?.let { if (healthyOnly) !it.manuallyDisabled && it.status == HealthStatus.HEALTHY else eligible(it) } == true
        }

    internal fun plan(c: Context): Plan {
        val steps = ArrayList<Step>()
        val findings = ArrayList<String>()
        val byId = c.entries.associateBy { it.id }
        val retired = c.settings.retiredLogoHosts.toSet()
        val retiredGuides = c.settings.retiredGuides.toSet()
        val aliasRules = c.rules.filter { it.kind == "alias" && it.rule.enabled }
        val aliasOf = aliasRules.associateBy { it.rule.match.entryId!! }
        val groupRules = c.rules.filter { it.kind == "group" && it.rule.enabled }
        val correctionRules = c.rules.filter { it.kind == "correction" && it.rule.enabled }
        val equivalentRules = c.rules.filter { it.kind == "equivalent" && it.rule.enabled }
        val adoptRules = c.rules.filter { it.kind == "adopt" && it.rule.enabled }

        // Availability: since when each entry has had no eligible source, then the disables and restores it earns.
        val availability = HashMap<String, Instant?>()
        val since = HashMap(c.unavailable)
        for (entry in c.entries) {
            if (eligibleNow(entry, c.states, healthyOnly = false)) {
                if (since.remove(entry.id) != null) availability[entry.id] = null
            } else if (entry.id !in since) {
                since[entry.id] = c.now
                availability[entry.id] = c.now
            }
        }
        for (id in c.unavailable.keys) if (id !in byId) { since.remove(id); availability[id] = null }
        val disableAfter = Duration.ofHours(c.settings.disableAfterHours.toLong())
        for (entry in c.entries) {
            if (entry.pinned("enabled")) continue
            val start = since[entry.id]
            if (entry.enabled) {
                if (start != null && Duration.between(start, c.now) >= disableAfter) {
                    steps += Step.Disable(entry.id, "unavailable", CurationAction("disable", entry.id, entry.name, "enabled", "true", "false", "no eligible source since $start"))
                }
            } else if (entry.autoDisabledAt != null) {
                if (entry.autoDisabledReason == "alias") {
                    if (entry.id !in aliasOf) steps += Step.Enable(entry.id, CurationAction("enable", entry.id, entry.name, "enabled", "false", "true", "alias rule removed"))
                } else {
                    val healthy = eligibleNow(entry, c.states, healthyOnly = true)
                    val restore = healthy || (c.settings.enableOn == "eligible" && start == null)
                    if (restore) steps += Step.Enable(entry.id, CurationAction("enable", entry.id, entry.name, "enabled", "false", "true", if (healthy) "a source is healthy" else "a source is eligible"))
                }
            }
        }

        // Attachments accumulate per entry so the 64-source cap is checked against what the plan already adds.
        val attachments = HashMap<String, MutableList<String>>()
        fun attach(entry: LineupEntryRecord, offeringIds: List<String>, reason: String, ruleId: String?) {
            if (entry.pinned("offerings")) return
            val pending = attachments.getOrPut(entry.id) { mutableListOf() }
            val add = offeringIds.filter { it !in entry.offeringIds && it !in pending }
            if (add.isEmpty()) return
            if (entry.offeringIds.size + pending.size + add.size > Step.MAX_OFFERINGS) { findings += "${entry.name} (${entry.id}): the ${Step.MAX_OFFERINGS}-source cap refuses ${add.size} more"; return }
            pending += add
            steps += Step.Attach(entry.id, add, CurationAction("attach", entry.id, entry.name, "offerings", entry.offeringIds.size.toString(), (entry.offeringIds.size + pending.size).toString(), reason, ruleId, add))
        }

        // Alias merges: the alias's sources become the canonical entry's backups and the alias leaves the served lineup.
        for (compiled in aliasRules) {
            val alias = byId[compiled.rule.match.entryId!!]
            val canonical = byId[compiled.rule.set.canonicalEntryId!!]
            if (alias == null || canonical == null) { findings += "alias rule ${compiled.id}: an entry it names no longer exists"; continue }
            attach(canonical, alias.offeringIds.filter { feeds.offering(it) != null }, "alias ${alias.name} (${alias.id})", compiled.id)
            if (alias.enabled && !alias.pinned("enabled")) {
                steps += Step.Disable(alias.id, "alias", CurationAction("disable", alias.id, alias.name, "enabled", "true", "false", "alias of ${canonical.name} (${canonical.id})", compiled.id))
            }
        }

        // Equivalent rules are the household's own evidence, so a candidate another entry already carries is shared, not refused.
        for (compiled in equivalentRules) {
            val target = byId[compiled.rule.match.entryId!!] ?: continue
            if (target.id in aliasOf) continue
            for (channel in feeds.channels(compiled.rule.match.providerId)) {
                val provider = feeds.providerOf(channel.id)
                if (!compiled.matches(provider, channel.tags.firstOrNull(), channel.name, channel.legacyGuideId)) continue
                attach(target, feeds.offeringsOf(channel.id).map { it.id }, "equivalent of ${channel.name} from $provider: name, by rule", compiled.id)
            }
        }

        // Equivalents: an unreferenced candidate joins the one entry it is evidently the same station as.
        val referenced = c.entries.flatMapTo(HashSet()) { it.offeringIds }
        val aliased = aliasOf.keys + c.entries.filter { !it.enabled && it.autoDisabledReason == "alias" }.map { it.id }
        val unreferenced = feeds.channels().filter { channel -> feeds.offeringsOf(channel.id).let { it.isNotEmpty() && it.none { o -> o.id in referenced } } }
        class Subject(val entry: LineupEntryRecord, val names: Set<String>, val tvgs: Set<String>)
        val subjectsByName = HashMap<String, MutableList<Subject>>()
        for (entry in c.entries) {
            if (entry.id in aliased) continue
            val candidates = candidatesOf(entry)
            val subject = Subject(
                entry,
                (candidates.map { it.name } + entry.name).mapTo(HashSet()) { it.trim().lowercase() },
                (candidates.mapNotNull { it.legacyGuideId } + listOfNotNull(entry.guide?.channelId)).toHashSet(),
            )
            for (name in subject.names) subjectsByName.getOrPut(name) { mutableListOf() } += subject
        }
        val attachedChannels = HashSet<String>()
        for (candidate in unreferenced) {
            val name = candidate.name.trim().lowercase()
            val provider = feeds.providerOf(candidate.id)
            val matching = subjectsByName[name].orEmpty().mapNotNull { subject ->
                val tvg = candidate.legacyGuideId
                val rule = equivalentRules.firstOrNull { it.rule.match.entryId == subject.entry.id && it.matches(provider, candidate.tags.firstOrNull(), candidate.name, tvg) }
                when {
                    tvg != null && tvg in subject.tvgs -> subject to ("name and tvg-id $tvg" to null)
                    rule != null -> subject to ("name, by rule" to rule.id)
                    c.settings.equivalentEvidence == "name" -> subject to ("name" to null)
                    else -> null
                }
            }
            // Several matches resolve to the one enabled entry; disabled duplicates are what the Dispatcharr import leaves.
            val chosen = matching.singleOrNull() ?: matching.filter { it.first.entry.enabled }.singleOrNull()
            when {
                matching.isEmpty() -> {}
                chosen != null -> {
                    val (subject, evidence) = chosen
                    attach(subject.entry, feeds.offeringsOf(candidate.id).map { it.id }, "equivalent of ${candidate.name} from $provider: ${evidence.first}", evidence.second)
                    attachedChannels += candidate.id
                }
                else -> findings += "candidate ${candidate.name} ($provider) matches ${matching.size} entries: ${matching.joinToString { it.first.entry.id }}"
            }
        }

        // Adoption: the first adopt rule a leftover candidate matches creates its entry, numbered by the policy. Rule
        // order is provider priority: an earlier rule's candidate becomes the entry, and a later candidate that is
        // evidently the same station joins it as a fallback source instead of becoming a duplicate.
        val numbers = c.entries.mapTo(HashSet()) { it.number }
        val adoptedByName = HashMap<String, Int>()
        val ordered = unreferenced.mapNotNull { candidate ->
            if (adoptRules.isEmpty() || candidate.id in attachedChannels || candidate.id in c.adopted) return@mapNotNull null
            val provider = feeds.providerOf(candidate.id)
            val index = adoptRules.indexOfFirst { it.matches(provider, candidate.tags.firstOrNull(), candidate.name, candidate.legacyGuideId) }
            if (index < 0) null else Triple(candidate, provider, index)
        }.sortedWith(compareBy<Triple<Channel, String?, Int>> { it.third }.thenComparator { a, b -> LineupService.channelOrder.compare(a.first, b.first) })
        for ((candidate, provider, ruleIndex) in ordered) {
            val raw = candidate.tags.firstOrNull()
            val tvg = candidate.legacyGuideId
            val rule = adoptRules[ruleIndex]
            val sameAs = adoptedByName[candidate.name.trim().lowercase()]?.let { steps[it] as Step.Adopt }
            if (sameAs != null && (c.settings.equivalentEvidence == "name" || (tvg != null && tvg == sameAs.entry.guide?.channelId))) {
                val merged = sameAs.entry.copy(offeringIds = sameAs.entry.offeringIds + feeds.offeringsOf(candidate.id).map { it.id }.filter { it !in sameAs.entry.offeringIds })
                if (merged.offeringIds.size <= Step.MAX_OFFERINGS) {
                    steps[adoptedByName.getValue(candidate.name.trim().lowercase())] = Step.Adopt(sameAs.channelId, merged, sameAs.action)
                    continue
                }
            }
            val correction = correctionRules.firstOrNull { it.matches(provider, raw, candidate.name, tvg) }
            val name = correction?.rule?.set?.name ?: candidate.name
            val group = rule.rule.set.group ?: correction?.rule?.set?.group ?: groupRules.firstOrNull { it.matches(provider, raw, name, tvg) }?.rule?.set?.group ?: raw ?: c.settings.defaultGroup
            val number = candidate.displayNumber.takeIf { it.matches(NUMBER) && it !in numbers } ?: nextFree(numbers, c.settings.numberFloor)
            val guide = tvg?.let { id -> guides.guidesListing(id).firstOrNull()?.let { GuideMapping(it, id) } }
            val entry = try {
                lineup.validated(
                    LineupEntryRecord(
                        UUID.randomUUID().toString(), number, name, group, correction?.rule?.set?.logo, rule.rule.set.enabled ?: false, guide,
                        feeds.offeringsOf(candidate.id).map { it.id }, createdBy = "rule:${rule.id}",
                    ),
                )
            } catch (e: ApiFailure) { findings += "candidate ${candidate.name} ($provider) cannot be adopted: ${e.message}"; continue }
            numbers += number
            adoptedByName.putIfAbsent(candidate.name.trim().lowercase(), steps.size)
            steps += Step.Adopt(candidate.id, entry, CurationAction("adopt", entry.id, name, "number", null, number, "adopted from $provider, ${if (entry.enabled) "enabled" else "disabled"}", rule.id))
        }

        // Display fields: corrections, then groups, then logos on retired hosts.
        for (entry in c.entries) {
            if (entry.id in aliased) continue
            val candidates = candidatesOf(entry)
            val provider = entry.offeringIds.firstNotNullOfOrNull { feeds.offering(it)?.providerId }
            val raw = candidates.firstNotNullOfOrNull { it.tags.firstOrNull() }
            val tvg = entry.guide?.channelId ?: candidates.firstNotNullOfOrNull { it.legacyGuideId }
            val correction = correctionRules.firstOrNull { rule -> candidates.any { rule.matches(feeds.providerOf(it.id), it.tags.firstOrNull(), it.name, it.legacyGuideId) } }
            if (!entry.pinned("name")) {
                val name = correction?.rule?.set?.name
                if (name != null && name != entry.name) steps += Step.SetField(entry.id, "name", entry.name, name, CurationAction("rename", entry.id, entry.name, "name", entry.name, name, "correction", correction.id))
            }
            if (!entry.pinned("group")) {
                val derived = correction?.rule?.set?.group?.let { it to correction.id }
                    ?: groupRules.firstOrNull { it.matches(provider, raw, entry.name, tvg) }?.let { it.rule.set.group!! to it.id }
                if (derived != null && derived.first != entry.group) {
                    steps += Step.SetField(entry.id, "group", entry.group, derived.first, CurationAction("regroup", entry.id, entry.name, "group", entry.group, derived.first, "raw group ${raw ?: "none"}", derived.second))
                }
            }
            if (!entry.pinned("logo")) {
                val corrected = correction?.rule?.set?.logo
                if (corrected != null && corrected != entry.logo) {
                    steps += Step.SetField(entry.id, "logo", entry.logo, corrected, CurationAction("relogo", entry.id, entry.name, "logo", entry.logo, corrected, "correction", correction.id))
                } else if (entry.logo != null && onRetiredHost(entry.logo, retired)) {
                    steps += Step.SetField(entry.id, "logo", entry.logo, null, CurationAction("clear-logo", entry.id, entry.name, "logo", entry.logo, null, "logo host retired"))
                }
            }
            val current = entry.guide
            val dangling = current != null && (guides.config(current.guideId) == null ||
                (guides.channels(current.guideId).isNotEmpty() && guides.channel(current.guideId, current.channelId) == null))
            if (entry.enabled && !entry.pinned("guide") && (current == null || current.guideId in retiredGuides || dangling)) {
                val target = guideFor(candidates, retiredGuides)
                if (target != null && target != current) {
                    val shown = { m: GuideMapping? -> m?.let { "${it.guideId}/${it.channelId}" } }
                    val reason = when {
                        current == null -> "unassigned"
                        current.guideId in retiredGuides -> "guide ${current.guideId} retired"
                        else -> "guide ${current.guideId} does not list ${current.channelId}"
                    }
                    steps += Step.SetGuide(entry.id, current, target, CurationAction("remap-guide", entry.id, entry.name, "guide", shown(current), shown(target), reason))
                }
            }
        }

        // Findings: what automation sees and leaves to the household.
        val enabledEntries = c.entries.filter { it.enabled }
        for ((number, group) in enabledEntries.groupBy { it.number }) if (group.size > 1) findings += "number $number is used by ${group.size} enabled entries: ${group.joinToString { it.id }}"
        for ((_, group) in enabledEntries.filter { it.id !in aliased }.groupBy { it.name.trim().lowercase() }) {
            if (group.size < 2) continue
            val tvgs = group.map { entry -> (candidatesOf(entry).mapNotNull { it.legacyGuideId } + listOfNotNull(entry.guide?.channelId)).toSet() }
            if (tvgs.indices.any { i -> tvgs.indices.any { j -> i < j && tvgs[i].intersect(tvgs[j]).isNotEmpty() } }) {
                findings += "${group.first().name}: ${group.size} enabled entries share the name and a guide identity without an alias rule: ${group.joinToString { it.id }}"
            }
        }
        return Plan(c.now, steps, findings, availability)
    }

    private suspend fun apply(plan: Plan): List<CurationAction> {
        if (plan.steps.isEmpty()) return emptyList()
        val now = clock.instant()
        val applied = ArrayList<CurationAction>()
        val adoptedNow = HashMap<String, String>()
        lineup.mutate { entries ->
            val referenced = entries.flatMapTo(HashSet()) { it.offeringIds }
            for (step in plan.steps) {
                if (step is Step.Adopt) {
                    if (entries.any { it.id == step.entry.id } || step.entry.offeringIds.any { it in referenced } || step.channelId in adoptedNow) continue
                    entries += step.entry
                    referenced += step.entry.offeringIds
                    adoptedNow[step.channelId] = step.entry.id
                    applied += step.action
                    continue
                }
                val index = entries.indexOfFirst { it.id == step.entryId }
                if (index < 0) continue
                val next = step.applyTo(entries[index], now, feeds) ?: continue
                val valid = try { lineup.validated(next) } catch (_: ApiFailure) { continue }
                entries[index] = valid
                referenced += valid.offeringIds
                applied += step.action
            }
        }
        if (adoptedNow.isNotEmpty()) {
            store.markAdopted(adoptedNow, now)
            adopted.set(adopted.get() + adoptedNow.keys)
        }
        store.recordCurationChanges(applied, now)
        // Logo values can be URLs; the log carries the kind, the entry and the rule only.
        for (action in applied) log.info("curation {} entry={} field={} rule={}", action.kind, action.entryId, action.field, action.ruleId)
        return applied
    }

    companion object {
        private val NUMBER = Regex("\\d{1,9}(\\.\\d{1,4})?")

        /** The lowest integer at or above [floor] no entry uses. */
        fun nextFree(used: Set<String>, floor: Int): String {
            var candidate = floor
            while (candidate.toString() in used) candidate++
            return candidate.toString()
        }
    }
}
