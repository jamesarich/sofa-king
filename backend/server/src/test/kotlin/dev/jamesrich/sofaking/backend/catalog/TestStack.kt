package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.api.AdoptProvider
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.HttpClient
import java.time.Clock

class Stack(val feeds: FeedService, val guides: GuideService, val lineup: LineupService, val catalog: CatalogService)

/** Guides, lineup and catalog over [feeds]; every provider's channels are adopted so the app sees them. */
suspend fun lineupStack(
    feeds: FeedService,
    store: BackendStore,
    clock: Clock,
    http: HttpClient,
    guides: List<GuideConfig> = emptyList(),
    adopt: Boolean = true,
): Stack {
    lateinit var lineup: LineupService
    val guideService =
        GuideService(guides, store, http, clock, UpstreamPolicy(allowLoopback = true)) { lineup.wanted() }
    guideService.initialize()
    lineup = LineupService(store, clock, feeds, guideService)
    lineup.initialize()
    val catalog = CatalogService(feeds, guideService, lineup, store, clock)
    for (guide in guides) guideService.refresh(guide)
    if (adopt) for (config in feeds.configs) lineup.adopt(AdoptProvider(config.id))
    for (guide in guides) guideService.refresh(guide)
    return Stack(feeds, guideService, lineup, catalog)
}

/** Adopts every provider into the lineup and imports the guides for the mapped channels. */
suspend fun LiveRuntime.adoptAll() {
    for (guide in guides.configs) guides.refresh(guide)
    for (config in feeds.configs) lineup.adopt(AdoptProvider(config.id))
    for (guide in guides.configs) guides.refresh(guide)
}
