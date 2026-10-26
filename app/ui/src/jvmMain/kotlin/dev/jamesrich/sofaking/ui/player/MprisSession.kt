package dev.jamesrich.sofaking.ui.player

import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.annotations.DBusProperty.Access
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.awt.EventQueue

private val log = Logger.withTag("SofaKing")

/** The MPRIS root interface: what the desktop's media controls call the player. */
@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MprisRoot : DBusInterface {
    @DBusMemberName("Raise")
    fun raise()

    @DBusMemberName("Quit")
    fun quit()

    @DBusBoundProperty(name = "CanQuit", access = Access.READ)
    fun canQuit(): Boolean

    @DBusBoundProperty(name = "CanRaise", access = Access.READ)
    fun canRaise(): Boolean

    @DBusBoundProperty(name = "HasTrackList", access = Access.READ)
    fun hasTrackList(): Boolean

    @DBusBoundProperty(name = "Identity", access = Access.READ)
    fun identity(): String

    @DBusBoundProperty(name = "SupportedUriSchemes", access = Access.READ)
    fun supportedUriSchemes(): List<String>

    @DBusBoundProperty(name = "SupportedMimeTypes", access = Access.READ)
    fun supportedMimeTypes(): List<String>
}

/** The MPRIS player interface: play, pause, next and previous, and what is playing. */
// The MPRIS specification fixes the interface's methods and properties, and every one must be declared.
@Suppress("TooManyFunctions")
@DBusInterfaceName(MPRIS_PLAYER)
interface MprisPlayer : DBusInterface {
    @DBusMemberName("Next")
    fun next()

    @DBusMemberName("Previous")
    fun previous()

    @DBusMemberName("Pause")
    fun pause()

    @DBusMemberName("PlayPause")
    fun playPause()

    @DBusMemberName("Stop")
    fun stop()

    @DBusMemberName("Play")
    fun play()

    @DBusMemberName("Seek")
    fun seek(offset: Long)

    @DBusMemberName("SetPosition")
    fun setPosition(trackId: DBusPath, position: Long)

    @DBusMemberName("OpenUri")
    fun openUri(uri: String)

    @DBusBoundProperty(name = "PlaybackStatus", access = Access.READ)
    fun playbackStatus(): String

    @DBusBoundProperty(name = "Rate", access = Access.READ)
    fun rate(): Double

    @DBusBoundProperty(name = "Metadata", access = Access.READ)
    fun metadata(): Map<String, Variant<*>>

    @DBusBoundProperty(name = "Position", access = Access.READ)
    fun position(): Long

    @DBusBoundProperty(name = "MinimumRate", access = Access.READ)
    fun minimumRate(): Double

    @DBusBoundProperty(name = "MaximumRate", access = Access.READ)
    fun maximumRate(): Double

    @DBusBoundProperty(name = "CanGoNext", access = Access.READ)
    fun canGoNext(): Boolean

    @DBusBoundProperty(name = "CanGoPrevious", access = Access.READ)
    fun canGoPrevious(): Boolean

    @DBusBoundProperty(name = "CanPlay", access = Access.READ)
    fun canPlay(): Boolean

    @DBusBoundProperty(name = "CanPause", access = Access.READ)
    fun canPause(): Boolean

    @DBusBoundProperty(name = "CanSeek", access = Access.READ)
    fun canSeek(): Boolean

    @DBusBoundProperty(name = "CanControl", access = Access.READ)
    fun canControl(): Boolean
}

/**
 * The desktop's media session on Linux: the player on the session bus under MPRIS, so the desktop's media keys, its
 * sound menu and KDE Connect or GSConnect show what plays and play, pause and change the channel. Calls arrive on
 * D-Bus threads and run on the UI thread, as the browser's media session's handlers do.
 */
// One object serves both MPRIS interfaces at the path the specification names, so it implements all their members.
@Suppress("TooManyFunctions")
internal class MprisSession private constructor(
    private val connection: DBusConnection,
    private val player: LivePlayer,
    private val onZapStep: (Int) -> Unit,
    private val identity: String,
) : MediaSessionHandle,
    MprisRoot,
    MprisPlayer {
    @Volatile private var nowPlaying: NowPlaying? = null
    private var track = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Play and pause from the app's own keys reach the desktop's controls too.
        scope.launch {
            player.status.map { it.playWhenReady }.distinctUntilChanged().drop(1).collect {
                changed(mapOf("PlaybackStatus" to Variant(playbackStatus())))
            }
        }
    }

    override fun publish(nowPlaying: NowPlaying) {
        this.nowPlaying = nowPlaying
        track++
        changed(mapOf("Metadata" to Variant(metadata(), "a{sv}")))
    }

    override fun close() {
        scope.cancel()
        val _ = runCatchingCancellable {
            connection.unExportObject(OBJECT_PATH)
            connection.releaseBusName(BUS_NAME)
            connection.close()
        }
    }

    override fun getObjectPath(): String = OBJECT_PATH

    override fun raise() = Unit

    override fun quit() = Unit

    override fun canQuit(): Boolean = false

    override fun canRaise(): Boolean = false

    override fun hasTrackList(): Boolean = false

    override fun identity(): String = identity

    override fun supportedUriSchemes(): List<String> = emptyList()

    override fun supportedMimeTypes(): List<String> = emptyList()

    override fun next() = onUi { onZapStep(1) }

    override fun previous() = onUi { onZapStep(-1) }

    override fun pause() = onUi { player.pause() }

    override fun playPause() = onUi { if (player.status.value.playWhenReady) player.pause() else player.play() }

    override fun stop() = pause()

    override fun play() = onUi { player.play() }

    override fun seek(offset: Long) = Unit

    override fun setPosition(trackId: DBusPath, position: Long) = Unit

    override fun openUri(uri: String) = Unit

    override fun playbackStatus(): String = if (player.status.value.playWhenReady) "Playing" else "Paused"

    override fun rate(): Double = 1.0

    /** The title, the program or episode as the artist, and the artwork, under a track id that changes with each. */
    override fun metadata(): Map<String, Variant<*>> {
        val playing = nowPlaying ?: return mapOf("mpris:trackid" to Variant(DBusPath(NO_TRACK)))
        return buildMap {
            put("mpris:trackid", Variant(DBusPath("$OBJECT_PATH/track/$track")))
            put("xesam:title", Variant(playing.title))
            if (playing.subtitle.isNotEmpty()) put("xesam:artist", Variant(listOf(playing.subtitle), "as"))
            playing.artworkUrl?.let { put("mpris:artUrl", Variant(it)) }
        }
    }

    override fun position(): Long = 0

    override fun minimumRate(): Double = 1.0

    override fun maximumRate(): Double = 1.0

    override fun canGoNext(): Boolean = true

    override fun canGoPrevious(): Boolean = true

    override fun canPlay(): Boolean = true

    override fun canPause(): Boolean = true

    override fun canSeek(): Boolean = false

    override fun canControl(): Boolean = true

    private fun changed(properties: Map<String, Variant<*>>) {
        val _ = runCatchingCancellable {
            connection.sendMessage(Properties.PropertiesChanged(OBJECT_PATH, MPRIS_PLAYER, properties, emptyList()))
        }
    }

    private fun onUi(action: () -> Unit) = EventQueue.invokeLater(action)

    companion object {
        const val BUS_NAME = "org.mpris.MediaPlayer2.sofaking"
        const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
        private const val NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"

        /**
         * Puts [player] on the session bus at [address], the user's session bus by default; null where there is none,
         * as on Windows, macOS or a desktop without D-Bus, and the player then has no system media session.
         */
        fun open(
            player: LivePlayer,
            onZapStep: (Int) -> Unit,
            identity: String,
            address: String? = null,
        ): MprisSession? = runCatchingCancellable {
            val builder = if (address != null) {
                DBusConnectionBuilder.forAddress(address)
            } else {
                DBusConnectionBuilder.forSessionBus()
            }
            val connection = builder.withShared(false).build()
            try {
                MprisSession(connection, player, onZapStep, identity).also {
                    connection.requestBusName(BUS_NAME)
                    connection.exportObject(it)
                }
            } catch (e: org.freedesktop.dbus.exceptions.DBusException) {
                connection.close()
                throw e
            }
        }.onFailure { log.i { "no MPRIS media session: ${it.message}" } }.getOrNull()
    }
}

private const val MPRIS_PLAYER = "org.mpris.MediaPlayer2.Player"
