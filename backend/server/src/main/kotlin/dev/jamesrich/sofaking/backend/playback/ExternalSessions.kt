package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.PlaybackSession
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.UUID

/** Watch sessions for players that send no API calls, and for recordings; they live while media requests arrive. */
class ExternalSessions internal constructor(
    private val table: SessionTable,
    private val clock: Clock,
    private val sources: SourceChoice,
) {
    /** A new session on [channelId] for the player at [client]; [shareable] false keeps [joinable] from sharing it. */
    suspend fun open(channelId: String, client: String? = null, shareable: Boolean = true): PlaybackSession =
        table.mutex.withLock {
            table.reap()
            val offering = sources.select(channelId)
            table.makeRoom()
            val id = UUID.randomUUID().toString()
            val now = clock.instant()
            val session =
                LiveSession(
                    id,
                    OpenPlayback(channelId, Wire.Intent.WATCH, "external:$id"),
                    offering,
                    now.plus(SessionManager.EXTERNAL_IDLE),
                    external = true,
                    shareable = shareable,
                    startedAt = now,
                    client = client,
                )
            table.add(session)
            session.descriptor(now)
        }

    /**
     * A shareable HLS session already playing [channelId], for a second player to share. A TS root replaces its
     * reader, so it is never shared.
     */
    suspend fun joinable(channelId: String): PlaybackSession? = table.mutex.withLock {
        table.reap()
        table.sessions.values
            .firstOrNull { it.shareable && it.request.channelId == channelId && it.offering.protocol == "hls" }
            ?.also { it.expires = clock.instant().plus(SessionManager.EXTERNAL_IDLE) }
            ?.descriptor(clock.instant())
    }
}
