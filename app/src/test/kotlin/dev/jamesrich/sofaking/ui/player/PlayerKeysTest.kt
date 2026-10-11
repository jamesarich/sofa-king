package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerKeysTest {
    @Test
    fun `Guide and Menu leave the player for the guide at Now`() {
        assertEquals(PlayerExit.GUIDE_AT_NOW, Key.Guide.playerExit())
        assertEquals(PlayerExit.GUIDE_AT_NOW, Key.Menu.playerExit())
    }

    @Test
    fun `other keys stay in the player`() {
        assertNull(Key.Back.playerExit())
        assertNull(Key.DirectionCenter.playerExit())
        assertNull(Key.Info.playerExit())
        assertNull(Key.One.playerExit())
    }

    @Test
    fun `digits map from both key rows`() {
        assertEquals(7, Key.Seven.digitValue())
        assertEquals(7, Key.NumPad7.digitValue())
        assertNull(Key.Guide.digitValue())
    }
}
