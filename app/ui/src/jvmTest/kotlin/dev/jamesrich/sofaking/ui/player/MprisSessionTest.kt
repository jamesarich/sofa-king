package dev.jamesrich.sofaking.ui.player

import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The MPRIS session on a private bus from `dbus-daemon`, which the Linux runners and desktops have. */
class MprisSessionTest {
    private val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1").start()
    private val address = daemon.inputReader().readLine()

    @AfterTest
    fun stopDaemon() {
        daemon.destroy()
    }

    @Test
    fun `media controls change the channel and read what is playing`() {
        val steps = LinkedBlockingQueue<Int>()
        val session = assertNotNull(
            MprisSession.open(UnavailableLivePlayer(CaptionSync(), "NONE"), steps::put, "Sofa King", address),
        )
        DBusConnectionBuilder.forAddress(address).withShared(false).build().use { client ->
            val player = client.getRemoteObject(
                MprisSession.BUS_NAME,
                MprisSession.OBJECT_PATH,
                MprisPlayer::class.java,
            )
            player.next()
            player.previous()
            assertEquals(1, steps.poll(5, TimeUnit.SECONDS))
            assertEquals(-1, steps.poll(5, TimeUnit.SECONDS))

            val properties =
                client.getRemoteObject(MprisSession.BUS_NAME, MprisSession.OBJECT_PATH, Properties::class.java)
            assertEquals("Sofa King", properties.Get<String>("org.mpris.MediaPlayer2", "Identity"))
            assertEquals("Playing", properties.Get<String>("org.mpris.MediaPlayer2.Player", "PlaybackStatus"))

            session.publish(NowPlaying("Channel 5", "The News", "http://server/logo.png"))
            // The remote side reads the dictionary's variants back as their values.
            val metadata = properties.Get<Map<String, Any>>("org.mpris.MediaPlayer2.Player", "Metadata")
            assertEquals("Channel 5", metadata["xesam:title"].unwrapped())
            assertEquals(listOf("The News"), (metadata["xesam:artist"].unwrapped() as List<*>).toList())
            assertEquals("http://server/logo.png", metadata["mpris:artUrl"].unwrapped())
        }
        session.close()
    }

    @Test
    fun `no bus means no session`() {
        val player = UnavailableLivePlayer(CaptionSync(), "NONE")
        assertNull(MprisSession.open(player, onZapStep = {}, "Sofa King", "unix:path=/nowhere"))
    }
}

private fun Any?.unwrapped(): Any? = if (this is Variant<*>) value else this
