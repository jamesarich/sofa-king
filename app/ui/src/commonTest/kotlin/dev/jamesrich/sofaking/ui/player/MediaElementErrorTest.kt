package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaElementErrorTest {
    @Test
    fun `a source the browser cannot play is a format error`() {
        assertEquals(
            PlayerError(PlayerErrorCodes.PARSING_CONTAINER_UNSUPPORTED),
            mediaElementError(MEDIA_ERR_SRC_NOT_SUPPORTED, playable = false, loaded = false),
        )
    }

    @Test
    fun `a playable stream that fails before loading is gone`() {
        val error = mediaElementError(MEDIA_ERR_SRC_NOT_SUPPORTED, playable = true, loaded = false)

        assertEquals(PlayerError(PlayerErrorCodes.NETWORK_CONNECTION_FAILED, streamGone = true), error)
    }

    @Test
    fun `a playable stream that fails after loading is a network failure`() {
        val error = mediaElementError(MEDIA_ERR_SRC_NOT_SUPPORTED, playable = true, loaded = true)

        assertEquals(PlayerError(PlayerErrorCodes.NETWORK_CONNECTION_FAILED), error)
    }

    @Test
    fun `any other element error is a network failure`() {
        assertEquals(
            PlayerError(PlayerErrorCodes.NETWORK_CONNECTION_FAILED),
            mediaElementError(2, playable = true, loaded = true),
        )
    }
}
