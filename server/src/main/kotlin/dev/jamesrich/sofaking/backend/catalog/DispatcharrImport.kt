package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*

/**
 * One-time migration of a Dispatcharr channel profile. Streams match offerings by source key (URL without
 * renewable query parameters), guide sources match configured guides by URL or by name, and entries upsert by UUID.
 */
class DispatcharrImporter(private val feeds: FeedService, private val guides: GuideService, private val lineup: LineupService) {
    suspend fun import(request: ImportLineup): ImportReport {
        val export = request.export
        if (export.format != FORMAT) throw ApiFailure(400, "INVALID_REQUEST", "Expected format $FORMAT")
        if (export.channels.size > 10_000) throw ApiFailure(400, "INVALID_REQUEST", "Export exceeds import limits")
        val guideByExportSource = export.epgSources.associate { source -> source.id.toString() to resolveGuide(source, request.guideMap) }
        var created = 0
        var updated = 0
        var skipped = 0
        var matched = 0
        var unmatched = 0
        val reports = ArrayList<ImportEntryReport>()
        val plan = ArrayList<Pair<LineupEntryRecord, Boolean>>()
        val seen = HashSet<String>()
        for (channel in export.channels) {
            val resolved = channel.streams.map { stream -> stream to feeds.offeringsMatching(stream.url).firstOrNull() }
            val offerings = resolved.mapNotNull { it.second?.id }.distinct()
            val missing = resolved.filter { it.second == null }.map { sourceKey(it.first.url) ?: "invalid-url" }
            matched += offerings.size
            unmatched += missing.size
            val guideSourceId = channel.epgSourceId?.toString()
            val guideId = guideSourceId?.let { guideByExportSource[it] ?: request.guideMap[it] }
            val epgChannelId = channel.epgChannelId?.trim()?.takeIf { it.isNotEmpty() }
            val mapping = if (guideId != null && epgChannelId != null) GuideMapping(guideId, epgChannelId) else null
            val guideState = when {
                mapping != null -> "mapped"
                epgChannelId == null -> "none"
                else -> "unmapped-source"
            }
            val id = channel.uuid?.let { runCatching { java.util.UUID.fromString(it).toString() }.getOrNull() ?: throw ApiFailure(400, "INVALID_REQUEST", "Channel ${channel.number} has an invalid uuid") }
                ?: stableId("dispatcharr:${export.profile}:${channel.number}:${channel.name}")
            if (!seen.add(id)) throw ApiFailure(400, "INVALID_REQUEST", "Channel ${channel.number} repeats uuid $id")
            if (offerings.isEmpty() && !request.includeUnmatched) {
                skipped++
                reports += ImportEntryReport(channel.number, channel.name, channel.uuid, "skipped", 0, missing, guideState)
                continue
            }
            val existing = lineup.entry(id)
            val record = try {
                lineup.validated(
                    LineupEntryRecord(
                        id = id,
                        number = channel.number.trim(),
                        name = channel.name.trim(),
                        group = channel.group?.trim()?.takeIf { it.isNotEmpty() },
                        logo = channel.logo?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
                        enabled = channel.enabled,
                        guide = mapping,
                        offeringIds = offerings,
                    ),
                )
            } catch (e: ApiFailure) {
                // One bad channel must not abort the other nine hundred; it is reported and skipped.
                skipped++
                reports += ImportEntryReport(channel.number, channel.name, channel.uuid, "skipped", offerings.size, missing, guideState, e.message)
                continue
            }
            if (existing == null) created++ else updated++
            plan += record to (existing != null)
            reports += ImportEntryReport(channel.number, channel.name, channel.uuid, if (existing == null) "created" else "updated", offerings.size, missing, guideState)
        }
        if (!request.dryRun) {
            lineup.mutate { entries ->
                for ((record, exists) in plan) {
                    if (exists) {
                        val index = entries.indexOfFirst { it.id == record.id }
                        entries[index] = record
                    } else {
                        entries += record
                    }
                }
            }
        }
        return ImportReport(request.dryRun, created, updated, skipped, matched, unmatched, guideByExportSource, reports)
    }

    /** Configured guide for a Dispatcharr EPG source: by URL ignoring the query, then by name, then by [guideMap]. */
    private fun resolveGuide(source: DispatcharrEpgSource, guideMap: Map<String, String>): String? {
        guideMap[source.id.toString()]?.let { mapped -> return guides.config(mapped)?.id ?: throw ApiFailure(400, "INVALID_REQUEST", "guideMap names unknown guide $mapped") }
        val key = source.url?.let(::pathKey)
        return guides.configs.firstOrNull { key != null && pathKey(it.url) == key }?.id
            ?: guides.configs.firstOrNull { it.id.equals(source.name, ignoreCase = true) }?.id
    }

    companion object {
        const val FORMAT = "sofa-dispatcharr-export/1"
    }
}
