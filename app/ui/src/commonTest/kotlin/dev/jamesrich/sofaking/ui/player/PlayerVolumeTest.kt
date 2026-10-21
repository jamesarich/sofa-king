package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.input.key.Key
import dev.jamesrich.sofaking.data.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerVolumeTest {
    @Test
    fun `the system volume platforms play at full and leave the keys to the player`() {
        val volume = PlayerVolume(adjustable = false)
        volume.restore(20, muted = true)

        assertEquals(1f, volume.gain)
        assertFalse(volume.press(VolumeKey.DOWN))
        assertEquals(0, volume.nudges)
    }

    @Test
    fun `steps move by five within the range and count as nudges`() {
        val volume = PlayerVolume(adjustable = true)
        volume.restore(97, muted = false)

        assertTrue(volume.press(VolumeKey.UP))
        assertEquals(AppSettings.MAX_VOLUME, volume.level)
        volume.press(VolumeKey.DOWN)
        assertEquals(95, volume.level)
        assertEquals(0.95f, volume.gain)
        assertEquals(2, volume.nudges)

        volume.restore(3, muted = false)
        volume.step(-1)
        assertEquals(0, volume.level)
    }

    @Test
    fun `mute silences without losing the level, and a step or the slider unmutes`() {
        val volume = PlayerVolume(adjustable = true)
        volume.restore(40, muted = false)

        volume.press(VolumeKey.MUTE)
        assertTrue(volume.muted)
        assertEquals(0f, volume.gain)
        assertEquals(40, volume.level)

        volume.press(VolumeKey.UP)
        assertFalse(volume.muted)
        assertEquals(45, volume.level)

        volume.toggleMute()
        volume.set(60)
        assertFalse(volume.muted)
        assertEquals(0.6f, volume.gain)
    }

    @Test
    fun `plus, equals and minus step the volume and M mutes`() {
        assertEquals(VolumeKey.UP, Key.Plus.volumeKey())
        assertEquals(VolumeKey.UP, Key.Equals.volumeKey())
        assertEquals(VolumeKey.UP, Key.NumPadAdd.volumeKey())
        assertEquals(VolumeKey.DOWN, Key.Minus.volumeKey())
        assertEquals(VolumeKey.DOWN, Key.NumPadSubtract.volumeKey())
        assertEquals(VolumeKey.MUTE, Key.M.volumeKey())
        assertNull(Key.DirectionUp.volumeKey())
        assertNull(Key.DirectionDown.volumeKey())
    }
}
