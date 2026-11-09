package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.cant_play
import dev.jamesrich.sofaking.resources.loopback_only
import dev.jamesrich.sofaking.resources.player_no_answer
import dev.jamesrich.sofaking.resources.player_server_busy
import dev.jamesrich.sofaking.resources.player_signed_out
import dev.jamesrich.sofaking.resources.vod_cant_play
import kotlin.test.Test
import kotlin.test.assertEquals

class FailureCopyTest {
    @Test
    fun `a full server says it is busy`() {
        assertEquals(Res.string.player_server_busy.key, failureCopy("CAPACITY_EXHAUSTED").reason.key)
    }

    @Test
    fun `a rejected token says the device is signed out`() {
        assertEquals(Res.string.player_signed_out.key, failureCopy("AUTH_REQUIRED").reason.key)
        assertEquals(Res.string.player_signed_out.key, failureCopy("HTTP_401").reason.key)
    }

    @Test
    fun `a token-less server that answers only its own machine is not signed out`() {
        assertEquals(Res.string.loopback_only.key, failureCopy("LOOPBACK_ONLY").reason.key)
        assertEquals(Res.string.player_signed_out.key, failureCopy("HTTP_403").reason.key)
    }

    @Test
    fun `a media request answered 401 or 403 says the device is signed out`() {
        val bad = "ERROR_CODE_IO_BAD_HTTP_STATUS"

        assertEquals(Res.string.player_signed_out.key, failureCopy(playerFailureCode(bad, 401)).reason.key)
        assertEquals(Res.string.player_signed_out.key, failureCopy(playerFailureCode(bad, 403)).reason.key)
        assertEquals(bad, playerFailureCode(bad, 404))
        assertEquals(bad, playerFailureCode(bad, null))
    }

    @Test
    fun `no answer at session open says the server did not start the channel`() {
        assertEquals(Res.string.player_no_answer.key, failureCopy("SESSION_UNAVAILABLE").reason.key)
    }

    @Test
    fun `an unknown code falls back to the generic headline`() {
        assertEquals(Res.string.cant_play.key, failureCopy("SOMETHING_NEW").reason.key)
        assertEquals(Res.string.vod_cant_play.key, failureCopy("SOMETHING_NEW", live = false).reason.key)
    }

    @Test
    fun `an item never blames a channel`() {
        assertEquals(Res.string.vod_cant_play.key, failureCopy("SESSION_UNAVAILABLE", live = false).reason.key)
    }
}
