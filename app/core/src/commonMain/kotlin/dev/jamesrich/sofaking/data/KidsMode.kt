package dev.jamesrich.sofaking.data

/**
 * Kids mode, this device's own: the guide and the channel list keep only the lineup's kids' channels, the library
 * only titles rated for children, and the DVR tab goes away. It filters channels by their group, not programs by
 * their rating, since guide ratings vary by source.
 */
object KidsMode {
    /** The content ratings the library shows in kids mode, as the server reports them, compared ignoring case. */
    val RATINGS: Set<String> = setOf("G", "PG", "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG")

    /** Whether [channel] is filed under a kids' group, such as Kids or Children. */
    fun allows(channel: Channel): Boolean = channel.groups.any { group ->
        group.lowercase().let { "kid" in it || "child" in it }
    }

    /** Whether [item] carries one of [RATINGS]; an item without a rating stays hidden. */
    fun allows(item: MediaItem): Boolean = item.contentRating?.trim()?.uppercase() in RATINGS

    /** [channels] as kids mode shows them, in the lineup's order. */
    fun channels(channels: List<Channel>): List<Channel> = channels.filter(::allows)

    /** [home] with every shelf down to the items kids mode shows, and the shelves left empty dropped. */
    fun home(home: MediaHome): MediaHome = home.copy(
        shelves = home.shelves.map { shelf -> shelf.copy(items = shelf.items.filter(::allows)) }
            .filter { it.items.isNotEmpty() },
    )
}
