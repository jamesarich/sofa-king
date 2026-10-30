package dev.jamesrich.sofaking.backend.store

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/** A person's decision about the iptv-org channel a station matches: `accepted` names the channel, `dismissed` none. */
data class IptvOrgDecision(val stationKey: String, val channelId: String?, val decision: String)

/** Every decision made about a station's iptv-org match. */
suspend fun BackendStore.iptvOrgDecisions(): List<IptvOrgDecision> = read {
    IptvOrgMatches.selectAll().map {
        IptvOrgDecision(it[IptvOrgMatches.stationKey], it[IptvOrgMatches.channelId], it[IptvOrgMatches.decision])
    }
}

/** Keeps [decision] for its station, replacing an earlier one. */
suspend fun BackendStore.saveIptvOrgDecision(decision: IptvOrgDecision, now: Instant) = query {
    IptvOrgMatches.upsert {
        it[stationKey] = decision.stationKey
        it[channelId] = decision.channelId
        it[IptvOrgMatches.decision] = decision.decision
        it[decidedAt] = now.toString()
    }
    Unit
}

/** Forgets the decision about [stationKey], so its match is proposed or applied afresh. */
suspend fun BackendStore.clearIptvOrgDecision(stationKey: String) = query {
    IptvOrgMatches.deleteWhere { IptvOrgMatches.stationKey eq stationKey }
    Unit
}
