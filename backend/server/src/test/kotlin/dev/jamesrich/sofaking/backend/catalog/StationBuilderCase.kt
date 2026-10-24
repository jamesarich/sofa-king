package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.GroupPattern
import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyGroups
import dev.jamesrich.sofaking.backend.api.PolicyHealth
import dev.jamesrich.sofaking.backend.api.PolicyNames
import dev.jamesrich.sofaking.backend.api.PolicyNumbering
import dev.jamesrich.sofaking.backend.api.PolicyProvider
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.api.SourcePin
import dev.jamesrich.sofaking.backend.api.StationOverride
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.contract.Channel
import java.time.Instant

/** The inputs the station builder tests share: a clock, candidates, a catalog of them and a policy over it. */
abstract class StationBuilderCase {
    protected val now = Instant.parse("2026-10-11T12:00:00Z")

    protected fun candidate(
        provider: String,
        id: String,
        name: String,
        number: String = "1",
        tvg: String? = null,
        group: String? = null,
        logo: String? = null,
        offerings: Int = 1,
    ) = FeedCandidate(
        Channel(id, number, name, logo, listOfNotNull(group), tvg),
        provider,
        if (offerings == 0) {
            emptyList()
        } else {
            (1..offerings).map { Offering("$id-o$it", id, provider, "https://example.test/$id/$it", "hls") }
        },
    )

    protected fun build(
        policy: LineupPolicy,
        candidates: List<FeedCandidate>,
        health: Map<String, HealthState>? = null,
        previous: Map<String, StationAvailability> = emptyMap(),
        listing: (String) -> List<String> = { emptyList() },
        providers: Set<String> = candidates.mapTo(HashSet()) { it.providerId },
        seed: Set<String> = emptySet(),
        covered: (GuideMapping) -> Boolean = { true },
    ): BuildResult = StationBuilder.build(
        BuildInput(
            policy, candidates, listing, providers,
            health ?: candidates.flatMap {
                it.offerings
            }.associate { it.id to HealthState(HealthStatus.HEALTHY) },
            previous, now, seed, covered,
        ),
    )

    protected fun BuildResult.station(key: String): PolicyStation = stations.single { it.key == key }

    protected val catalog = listOf(
        candidate(
            "whale",
            "w1",
            "Bob Ross Channel",
            "15",
            tvg = "bob",
            group = "Lifestyle",
            logo = "https://logo.test/w1.png",
        ),
        candidate("whale", "w2", "BBC News HD", "2", tvg = "bbc", group = "News"),
        candidate("samsung", "s1", "The Bob Ross Channel", "1001", tvg = "s-bob", group = "Arts"),
        candidate("samsung", "s2", "BBC News", "1002", tvg = "s-bbc", offerings = 2),
        candidate("samsung", "s3", "Tennis Channel", "1003"),
        candidate("samsung", "s4", "Tennis TV", "1004"),
        candidate("vizio", "v1", "BBC News", "20", tvg = "v-bbc"),
        candidate("vizio", "v2", "Ghost Channel", "21", offerings = 0),
        candidate("masq", "m1", "NFL Net Feed 3", "900", tvg = "NFLNetwork.us"),
        candidate("masq", "m2", "Some Other Thing", "901", tvg = "other"),
    )

    protected val policy = LineupPolicy(
        names = PolicyNames(version = 1),
        health = PolicyHealth(requireVerified = false),
        providers = listOf(
            PolicyProvider("whale"),
            PolicyProvider("samsung"),
            PolicyProvider("vizio", enabled = false),
        ),
        groups = PolicyGroups(
            map = mapOf("news" to "Headlines"),
            patterns = listOf(GroupPattern(name = "tennis", group = "Sports")),
            default = "Entertainment",
            builtIn = false,
        ),
        numbering = PolicyNumbering(floor = 1000),
        stations = mapOf(
            "nfl network" to
                StationOverride(
                    number = "245",
                    group = "Sports",
                    required = true,
                    sources = listOf(SourcePin("masq", "NFLNetwork.us", "NFL Network")),
                ),
        ),
    )
}
