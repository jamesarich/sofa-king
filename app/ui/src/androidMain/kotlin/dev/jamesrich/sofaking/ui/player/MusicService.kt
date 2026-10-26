package dev.jamesrich.sofaking.ui.player

import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps the music playing while the app is in the background: Media3's media playback service, holding the music's
 * media session, puts its notification up while a song plays and keeps the process in front of the system's
 * memory reclaim. The player stays the app's own; the service only carries its session. Swiping the app away from
 * Recents pauses the music and ends the service.
 */
class MusicService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        held?.let(::addSession)
    }

    /** A start for a later song's session, while the service still runs for an earlier one, adds it too. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        held?.let { if (!isSessionAdded(it)) addSession(it) }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = held

    override fun onTaskRemoved(rootIntent: Intent?) {
        pauseAllPlayersAndStopSelf()
    }

    companion object {
        @Volatile private var held: MediaSession? = null

        /** Starts the service for [session], from the app while it is in front. */
        internal fun start(context: Context, session: MediaSession) {
            held = session
            context.startService(Intent(context, MusicService::class.java))
        }

        /** Ends the service once the music is over. */
        internal fun stop(context: Context) {
            held = null
            context.stopService(Intent(context, MusicService::class.java))
        }
    }
}
