package dev.jamesrich.sofaking.data

/** [channels] without the ones in [hidden]; the lineup's order is kept. */
fun visibleChannels(channels: List<Channel>, hidden: Set<String>): List<Channel> =
    if (hidden.isEmpty()) channels else channels.filterNot { it.guideId in hidden }

/**
 * The starred channels in the order the user put them: those in [order] first, in that order, then the rest by
 * their place in [channels]. Ids in [order] that are no longer starred or listed are skipped.
 */
fun orderedFavorites(channels: List<Channel>, favorites: Set<String>, order: List<String>): List<Channel> {
    val starred = channels.filter { it.guideId in favorites }
    if (order.isEmpty()) return starred
    val byId = starred.associateBy { it.guideId }
    val placed = order.mapNotNull { byId[it] }.distinct()
    val placedIds = placed.mapTo(HashSet()) { it.guideId }
    return placed + starred.filterNot { it.guideId in placedIds }
}

/**
 * [current] (every favorite, in order) with [guideId] moved [step] places (negative is up); the ends clamp. The
 * result is the full order to store, so a favorite that had no place gets one.
 */
fun movedFavorite(current: List<String>, guideId: String, step: Int): List<String> {
    val from = current.indexOf(guideId)
    if (from < 0 || step == 0) return current
    val to = (from + step).coerceIn(0, current.lastIndex)
    if (to == from) return current
    return current.toMutableList().apply { removeAt(from); add(to, guideId) }
}
