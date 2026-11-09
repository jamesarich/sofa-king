package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.cant_play
import dev.jamesrich.sofaking.resources.player_no_answer
import dev.jamesrich.sofaking.resources.player_server_busy
import dev.jamesrich.sofaking.resources.player_signed_out
import dev.jamesrich.sofaking.resources.player_slot_taken
import dev.jamesrich.sofaking.resources.vod_cant_play
import kotlin.test.Test
import kotlin.test.assertEquals

class FailureCopyTest {
    @Test
    fun `a full server says it is busy`() {
        assertEquals(Res.string.player_server_busy.key, failureCopy("CAPACITY_EXHAUSTED").reason.key)
    }

    @Test
    fun `a session a recording took says so instead of expired`() {
        assertEquals(Res.string.player_slot_taken.key, failureCopy("SESSION_PREEMPTED").reason.key)
    }

    @Test
    fun `a rejected token says the device is signed out`() {
        assertEquals(Res.string.player_signed_out.key, failureCopy("AUTH_REQUIRED").reason.key)
        assertEquals(Res.string.player_signed_out.key, failureCopy("HTTP_401").reason.key)
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
