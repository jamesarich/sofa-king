package dev.jamesrich.sofaking.data

import io.ktor.http.encodeURLPathPart
import io.ktor.http.parseUrl

/**
 * What a `sofaking://` link asks the app to open, the same on every platform: a library item, from where the household
 * stopped or from [Item.startMs], or a live channel. Pairing links are read by [parsePairingLink].
 */
sealed interface AppLink {
    /** `sofaking://vod/ID`, or `sofaking://vod/ID?t=SECONDS` to start that far in. */
    data class Item(val itemId: String, val startMs: Long? = null) : AppLink

    /** `sofaking://live/NUMBER`, or a channel's name in place of its number. */
    data class Live(val channel: String) : AppLink
}

/** The [AppLink] [link] names; null for a pairing link, another scheme or a link missing its target. */
fun parseAppLink(link: String): AppLink? {
    val url = parseUrl(link.trim()) ?: return null
    if (url.protocol.name != LINK_SCHEME) return null
    val target = url.segments.singleOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return when (url.host) {
        "vod" -> AppLink.Item(target, url.parameters["t"]?.toLongOrNull()?.takeIf { it >= 0 }?.times(MS_PER_SECOND))
        "live" -> AppLink.Live(target)
        else -> null
    }
}

/** The link that opens the channel numbered [number] live, as Home Assistant or a shortcut would send it. */
fun liveLink(number: String): String = "$LINK_SCHEME://live/" + number.encodeURLPathPart()

/**
 * The channel of [lineup] a `sofaking://live` link's [target] names: by number first, then by name, ignoring case;
 * null when none is.
 */
fun linkedChannel(lineup: Lineup, target: String): Channel? {
    val number = target.toDoubleOrNull()
    return lineup.channels.firstOrNull { number != null && it.number == number }
        ?: lineup.channels.firstOrNull { it.name.equals(target, ignoreCase = true) }
}

private const val LINK_SCHEME = "sofaking"
private const val MS_PER_SECOND = 1_000L
