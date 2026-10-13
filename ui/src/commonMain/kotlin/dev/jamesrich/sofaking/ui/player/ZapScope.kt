package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.Channel

/** The guide filter a channel was picked from: the player's Up, Down and channel list stay inside it. */
data class ZapScope(val label: String, val guideIds: Set<String>)

/**
 * The channels the player cycles through: those in [scope] that the lineup still has, else the whole lineup
 * (no scope, a scope [widened] by a number typed outside it, or one the lineup no longer has any of).
 */
internal fun zapRing(lineup: List<Channel>, scope: ZapScope?, widened: Boolean): List<Channel> {
    if (scope == null || widened) return lineup
    return lineup.filter { it.guideId in scope.guideIds }.ifEmpty { lineup }
}
