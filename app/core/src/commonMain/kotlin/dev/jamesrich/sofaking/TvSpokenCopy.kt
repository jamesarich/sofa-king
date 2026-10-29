package dev.jamesrich.sofaking

/** The Apple TV app's pairing question and its VoiceOver guide cells, with the arguments filled in; see [TvCopy]. */
object TvSpokenCopy {
    private val table = CopyTable()

    /** Resource name to the text this object holds for it. */
    val twins: Map<String, String> get() = table.twins

    private fun twin(key: String, text: String) = table.twin(key, text)

    private fun fill(template: String, vararg arguments: Any) = table.fill(template, *arguments)

    private val guideCellAiring = twin("guide_cell_airing", "%1\$s, %2\$s, %3\$s to %4\$s, airing now")
    private val guideCellLater = twin("guide_cell_later", "%1\$s, %2\$s, %3\$s to %4\$s")
    private val guideCellNone = twin("guide_cell_none", "%1\$s, %2\$s")

    /** The mark a guide cell adds when it records; only the Apple TV app reads it. */
    private const val RECORDING = "recording"

    /** A guide cell for VoiceOver: [channel], [title], the times, whether it is on now, and a mark when it records. */
    fun guideCell(
        channel: String,
        title: String,
        start: String,
        stop: String,
        airing: Boolean,
        recording: Boolean,
    ): String {
        val cell = fill(if (airing) guideCellAiring else guideCellLater, channel, title, start, stop)
        return if (recording) "$cell, $RECORDING" else cell
    }

    /** A guide cell with no program: [channel] and [reason]. */
    fun guideCellNone(channel: String, reason: String): String = fill(guideCellNone, channel, reason)

    private val pairingSwitchTitle = twin("pairing_switch_title", "Switch to %1\$s?")
    private val pairingSwitchMessage = twin("pairing_switch_message", "This device stops using %1\$s.")
    val pairingSwitch = twin("pairing_switch", "Switch")
    val pairingSwitchCancel = twin("pairing_switch_cancel", "Cancel")

    /** The question a pairing link for another server asks, with [server] as `host:port`. */
    fun pairingSwitchTitle(server: String): String = fill(pairingSwitchTitle, server)

    /** What switching costs: the device stops using [server], the saved one, as `host:port`. */
    fun pairingSwitchMessage(server: String): String = fill(pairingSwitchMessage, server)
}
