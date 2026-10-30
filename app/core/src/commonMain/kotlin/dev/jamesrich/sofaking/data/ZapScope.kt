package dev.jamesrich.sofaking.data

/** The guide filter a channel was picked from: the player's Up, Down and channel list stay inside it. */
data class ZapScope(val label: String, val guideIds: Set<String>)

/** The scope of a guide filter that lists [channels]. */
fun zapScopeOf(label: String, channels: List<Channel>): ZapScope =
    ZapScope(label, channels.mapTo(HashSet()) { it.guideId })

/**
 * The channels the player cycles through: those in [scope] that the lineup still has, else the whole lineup
 * (no scope, a scope [widened] by a number typed outside it, or one the lineup no longer has any of).
 */
fun zapRing(lineup: List<Channel>, scope: ZapScope?, widened: Boolean): List<Channel> {
    if (scope == null || widened) return lineup
    return lineup.filter { it.guideId in scope.guideIds }.ifEmpty { lineup }
}

/** The row [step] channels from [index] in a ring of [size], wrapping at its ends; positive is down the guide. */
fun zapIndex(index: Int, size: Int, step: Int): Int = (index + step).mod(size)

/**
 * The channel [step] rows from [from] in [ring]. With [from] not in the ring (a channel the lineup dropped), a
 * step down lands on the first channel and a step up on the last. Null for an empty ring.
 */
fun zapNeighbor(ring: List<Channel>, from: String?, step: Int): Channel? {
    if (ring.isEmpty()) return null
    val index = ring.indexOfFirst { it.guideId == from }
    if (index < 0) return if (step >= 0) ring.first() else ring.last()
    return ring[zapIndex(index, ring.size, step)]
}

/**
 * How long a zap rests before the new channel is tuned, so pressing through channels does not open a session per press.
 */
const val ZAP_SETTLE_MS = 400L

/**
 * A zap from a tuned channel waits [ZAP_SETTLE_MS]; the first tune, which may take over the preview's stream, does not.
 */
fun zapSettleMs(tuned: Channel?, next: Channel): Long =
    if (tuned == null || tuned.guideId == next.guideId) 0L else ZAP_SETTLE_MS
