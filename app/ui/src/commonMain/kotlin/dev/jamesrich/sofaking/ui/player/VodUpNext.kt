package dev.jamesrich.sofaking.ui.player

/** What plays after the item on screen, offered when it ends: its title, the line under it and its picture. */
data class VodUpNext(val title: String, val subtitle: String? = null, val artworkUrl: String? = null)
