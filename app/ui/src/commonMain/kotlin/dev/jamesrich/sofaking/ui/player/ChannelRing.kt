package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jamesrich.sofaking.data.Channel

/** The ring of channels Up and Down cycle through, the row on it, and the failure of its stream. */
internal interface ChannelZapping {
    val startChannel: Channel?

    /**
     * Up, Down and the channel list stay in the guide's filter until a number typed outside it, or a channel
     * being followed after a re-import, widens them to the whole lineup.
     */
    var widened: Boolean

    /** The whole lineup, as last composed. */
    var lineupChannels: List<Channel>

    /** The channels Up and Down cycle through ([zapRing]), as last composed. */
    var channels: List<Channel>

    /** The row of [channels] on screen. */
    var index: Int

    /** What load() last tuned and what was watched before it, so a refreshed lineup can be followed by identity. */
    var tuned: Channel?

    val previousChannel: Channel?

    val previousIndex: Int?

    /** A channel number typed outside the scope, tuned once the ring has widened. */
    val pendingNumber: String?

    var failure: Failure?

    /** The channel whose id the server no longer knows, while the lineup is reloaded to find it again. */
    var recovering: Channel?

    /** Seconds to the next automatic reconnect, 0 while it runs, null with none scheduled. */
    var retryIn: Int?

    val currentChannel: Channel?

    fun zapTo(target: Int)

    /**
     * Moves [step] channels through the lineup, wrapping at its ends; positive is down the guide, as
     * [PlayerAction.zapStep] gives it.
     */
    fun zapStep(step: Int)

    /** Last-channel recall; with nothing watched before, it just shows the info overlay. */
    fun recallLastChannel()

    /** Typed digits name a channel in the ring, one outside it (the ring widens first), or no channel at all. */
    fun commitEntry(pending: ChannelNumberEntry)

    /** A row of the channel list: a channel in the ring tunes at once, one outside it widens the ring first. */
    fun tuneChannel(channel: Channel)

    /**
     * The lineup refreshes every few hours and may reorder or grow; the tuned channel keeps its stream and
     * its row follows it. A channel that vanished stays playing on the nearest row. The same holds when the
     * ring widens from the guide's filter to the whole lineup; a number typed outside the filter is tuned then.
     */
    fun followRing(ring: List<Channel>)

    /** The stream's state moved on: playing clears a failure, and a live stream that ended is one. */
    fun onStatus(status: PlayerStatus)

    /** The stream failed, or its session could not be opened; the reconnect keeps counting its attempts. */
    fun fail(code: String, immediate: Boolean = false)

    /** Clears the failure, so the channel tunes again from the first attempt. */
    fun clearFailure()
}

/** [ChannelZapping] for the player opened on [startIndex] of [lineupChannels]; zaps bring up the bar in [panels]. */
internal class ChannelRing(
    lineupChannels: List<Channel>,
    startIndex: Int,
    previousGuideId: String?,
    zapScope: ZapScope?,
    private val numbers: ChannelNumberInput,
    private val panels: PanelState,
) : ChannelZapping {
    override val startChannel: Channel? = lineupChannels.getOrNull(startIndex)

    override var widened: Boolean by mutableStateOf(zapScope != null && startChannel?.guideId !in zapScope.guideIds)

    override var lineupChannels: List<Channel> by mutableStateOf(lineupChannels)

    override var channels: List<Channel> by mutableStateOf(zapRing(lineupChannels, zapScope, widened))

    override var index: Int by mutableIntStateOf(
        channels.indexOfFirst {
            it.guideId == startChannel?.guideId
        }.coerceAtLeast(0),
    )

    override var tuned: Channel? by mutableStateOf<Channel?>(null)

    override var previousChannel: Channel? by mutableStateOf(
        lineupChannels.firstOrNull {
            it.guideId == previousGuideId
        },
    )
        private set

    override var previousIndex: Int? by mutableStateOf(
        channels.indexOfFirst { it.guideId == previousGuideId }.takeIf {
            it >=
                0
        },
    )
        private set

    override var pendingNumber: String? by mutableStateOf<String?>(null)
        private set

    override var failure: Failure? by mutableStateOf<Failure?>(null)

    override var recovering: Channel? by mutableStateOf<Channel?>(null)

    override var retryIn: Int? by mutableStateOf<Int?>(null)

    override val currentChannel: Channel? get() = channels.getOrNull(index)

    override fun zapTo(target: Int) {
        numbers.clear()
        failure = null
        recovering = null
        if (target != index) {
            previousIndex = index
            previousChannel = channels.getOrNull(index)
            index = target
        }
        panels.showInfo()
        panels.miniGuideOpen = false
    }

    override fun zapStep(step: Int) {
        val size = channels.size
        if (size > 0) zapTo((index + step).mod(size))
    }

    override fun recallLastChannel() {
        val previous = previousIndex
        if (previous != null) zapTo(previous) else panels.showInfo()
    }

    override fun commitEntry(pending: ChannelNumberEntry) {
        val target = pending.indexIn(channels)
        val outside = if (target == null) pending.indexIn(lineupChannels)?.let { lineupChannels[it] } else null
        when {
            target != null -> zapTo(target)

            outside != null -> {
                pendingNumber = outside.guideId
                widened = true
            }

            else -> numbers.missed(pending.digits)
        }
    }

    override fun tuneChannel(channel: Channel) {
        val target = channels.indexOfFirst { it.guideId == channel.guideId }
        if (target >= 0) {
            zapTo(target)
        } else {
            panels.miniGuideOpen = false
            pendingNumber = channel.guideId
            widened = true
        }
    }

    override fun followRing(ring: List<Channel>) {
        if (recovering != null) return
        val current = tuned ?: startChannel
        if (current != null) {
            index = indexAfterRefresh(ring, current) ?: index.coerceIn(0, (ring.size - 1).coerceAtLeast(0))
            previousIndex = previousChannel?.let { indexAfterRefresh(ring, it) }
        }
        val number = pendingNumber ?: return
        pendingNumber = null
        ring.indexOfFirst { it.guideId == number }.takeIf { it >= 0 }?.let(::zapTo)
    }

    override fun onStatus(status: PlayerStatus) {
        if (status.isPlaying || status.state == PlaybackState.READY) failure = null
        // A live stream never ends; the server closed the connection, so treat it as a failure and reconnect.
        if (status.state == PlaybackState.ENDED) failure = Failure(STREAM_ENDED, attempt = failure?.attempt ?: 0)
    }

    override fun fail(code: String, immediate: Boolean) {
        failure = Failure(code, attempt = failure?.attempt ?: 0, immediate = immediate)
    }

    override fun clearFailure() {
        failure = null
        recovering = null
    }

    init {
        numbers.onCommit = ::commitEntry
    }
}

/** The failure code of a live stream that ended. */
private const val STREAM_ENDED = "STREAM_ENDED"
