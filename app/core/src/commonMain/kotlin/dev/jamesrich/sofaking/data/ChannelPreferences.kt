package dev.jamesrich.sofaking.data

/** [channels] without the ones in [hidden]; the lineup's order is kept. */
fun visibleChannels(channels: List<Channel>, hidden: Set<String>): List<Channel> =
    if (hidden.isEmpty()) channels else channels.filterNot { it.guideId in hidden }

/**
 * [lineup] as this device shows it in the guide, the channel lists and multiview: without the channels in [hidden],
 * and in [kids] mode only the kids' channels. The lineup itself when nothing is left out.
 */
fun visibleLineup(lineup: Lineup, hidden: Set<String>, kids: Boolean): Lineup {
    if (hidden.isEmpty() && !kids) return lineup
    val shown = visibleChannels(lineup.channels, hidden)
    return lineup.copy(channels = if (kids) KidsMode.channels(shown) else shown)
}

/**
 * What the player zaps through when it plays [guideId], or null when it must not play it: hidden channels drop out
 * of [lineup] only when [AppSettings.zapSkipsHidden] asks, and never under the channel being watched; kids mode zaps
 * through its own channels ([visible]) and plays no other, however it was asked for.
 */
fun playerLineup(lineup: Lineup, visible: Lineup, guideId: String, settings: AppSettings?): Lineup? = when {
    settings?.kidsMode == true -> visible.takeIf { shown -> shown.channels.any { it.guideId == guideId } }
    settings?.zapSkipsHidden == true && guideId !in settings.hiddenChannels -> visible
    else -> lineup
}

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
    return current.toMutableList().apply {
        removeAt(from)
        add(to, guideId)
    }
}

/** One guide group as the channel manager lists it: its channels in lineup order and how many of them are shown. */
data class ChannelGroup(val name: String, val channels: List<Channel>, val shown: Int) {
    val total: Int get() = channels.size

    /** True while at least one channel of the group is shown; the group's switch reads this. */
    val anyShown: Boolean get() = shown > 0
}

/**
 * The lineup's groups in the order the guide's chips list them (first appearance), each with its channels and how
 * many of them [hidden] leaves shown. A channel filed under several groups is in each.
 */
fun channelGroups(channels: List<Channel>, hidden: Set<String>): List<ChannelGroup> {
    val byGroup = LinkedHashMap<String, MutableList<Channel>>()
    for (channel in channels) for (group in channel.groups) byGroup.getOrPut(group) { mutableListOf() }.add(channel)
    return byGroup.map { (name, members) -> ChannelGroup(name, members, members.count { it.guideId !in hidden }) }
}
