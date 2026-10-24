package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one thread every libVLC command runs on, and the player it commands. libVLC's stop and release block until
 * its input thread ends, hundreds of milliseconds on a stalled stream, and the app stops on every zap from the UI
 * thread, so each command runs on this thread, in order, while the player's status moves at once.
 */
internal class VlcCommands {
    /** Set by the first command, if libVLC loads; read on any thread, so the getters answer before it does. */
    @Volatile
    var factory: MediaPlayerFactory? = null

    @Volatile
    var player: EmbeddedMediaPlayer? = null

    /** The player, inside [command]s, which run only once it exists. */
    val vlc: EmbeddedMediaPlayer get() = checkNotNull(player)

    private val thread = Executors.newSingleThreadExecutor { task ->
        Thread(task, "libvlc-commands").apply { isDaemon = true }
    }

    /**
     * Counts the streams the player opens and stops. A queued stop runs late, and the old stream's callbacks keep
     * coming until it does; they count only while [opened], set by the command that opens a stream, is the latest.
     */
    val generation = AtomicInteger()

    @Volatile
    var opened = -1

    val isCurrent: Boolean get() = opened == generation.get()

    /**
     * Queues [task], to run if libVLC loaded. Once [shutdown] has shut the queue, a libVLC event still on its way or
     * a late call from the UI has nothing left to command, so it is dropped rather than thrown on libVLC's or the
     * UI's thread.
     */
    fun command(task: () -> Unit) = execute { if (player != null) task() }

    /** Queues [task] whether or not libVLC loaded; dropped once [shutdown] has run. */
    fun execute(task: () -> Unit) {
        try {
            thread.execute(task)
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Runs what is queued, then takes no more. */
    fun shutdown() = thread.shutdown()
}

/** The stream's captions and audio tracks, which libVLC draws and switches itself, read and set through [commands]. */
internal class VlcTracks(private val commands: VlcCommands) : PlayerTracks {
    /** libVLC draws the captions into the picture, so none are handed to the shared renderer. */
    override val cues: StateFlow<List<CaptionCue>> = MutableStateFlow(emptyList<CaptionCue>()).asStateFlow()
    private val _audioTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    override val audioTracks: StateFlow<List<AudioTrack>> = _audioTracks.asStateFlow()

    @Volatile
    private var captionsOn = false

    override val hasTextTrack: Boolean get() = (commands.player?.subpictures()?.trackCount() ?: 0) > 0

    override fun setCaptionsEnabled(enabled: Boolean) {
        captionsOn = enabled
        commands.command { apply() }
    }

    override fun selectAudioTrack(id: String) {
        val track = id.toIntOrNull() ?: return
        commands.command {
            commands.vlc.audio().setTrack(track)
            read()
        }
    }

    /** libVLC describes the tracks of the media it has opened; nothing before the first frame. */
    override fun streamInfo(): StreamInfo {
        val info = commands.player?.media()?.info() ?: return StreamInfo()
        val video = info.videoTracks().firstOrNull()
        val audio = info.audioTracks().firstOrNull()
        return StreamInfo(
            videoCodec = video?.codecName()?.takeIf { it.isNotBlank() }?.uppercase(),
            width = video?.width()?.takeIf { it > 0 },
            height = video?.height()?.takeIf { it > 0 },
            frameRate = video?.let {
                if (it.frameRateBase() > 0) {
                    it.frameRate().toFloat() / it.frameRateBase()
                } else {
                    null
                }
            },
            bitrateBps = video?.bitRate()?.takeIf { it > 0 },
            audioCodec = audio?.codecName()?.takeIf { it.isNotBlank() }?.uppercase(),
        )
    }

    /**
     * The audio tracks libVLC lists, without its "Disable" entry; on the command thread,
     * since the list is read from libVLC.
     */
    fun read() {
        if (!commands.isCurrent) return
        val current = commands.vlc.audio().track()
        val tracks = commands.vlc.audio().trackDescriptions().filter { it.id() >= 0 }
        _audioTracks.value =
            if (tracks.size > 1) {
                tracks.map { AudioTrack(it.id().toString(), it.description(), it.id() == current) }
            } else {
                emptyList()
            }
    }

    /** On the command thread, as it sets libVLC's track; the stream's playing event applies it to each new stream. */
    fun apply() {
        if (!commands.isCurrent) return
        val subs = commands.vlc.subpictures()
        if (!captionsOn) {
            subs.setTrack(-1)
            return
        }
        subs.trackDescriptions().firstOrNull { it.id() >= 0 }?.let { subs.setTrack(it.id()) }
    }

    /** The stream stopped: no audio choice until the next one lists its tracks. */
    fun clear() {
        _audioTracks.value = emptyList()
    }
}
