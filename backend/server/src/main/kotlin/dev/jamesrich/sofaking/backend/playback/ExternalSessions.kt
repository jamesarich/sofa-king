package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.CatalogService
import dev.jamesrich.sofaking.backend.runCatchingCancellable
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
    private val catalog: CatalogService,
) {
    /**
     * A new session on [channelId] for the player at [client]; [shareable] false keeps [joinable] from sharing it.
     * With [preempt], set for a recording, a full pool gives up the least recently active app session that is
     * watching. With [hold], the session counts as busy until [SessionMedia.attach] registers its read, so no other
     * open can take its slot in between.
     */
    suspend fun open(
        channelId: String,
        client: String? = null,
        shareable: Boolean = true,
        preempt: Boolean = false,
        hold: Boolean = false,
    ): PlaybackSession {
        val recording = if (preempt) channelName(channelId) else null
        return table.mutex.withLock {
            table.reap()
            val offering = sources.select(channelId)
            table.makeRoom(channelId.takeIf { preempt }, recording)
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
            session.held = hold
            table.add(session)
            session.descriptor(now)
        }
    }

    private suspend fun channelName(channelId: String): String? =
        runCatchingCancellable { catalog.catalog().channels.firstOrNull { it.id == channelId }?.name }.getOrNull()

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
