package dev.jamesrich.sofaking.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceQueryTest {
    @Test
    fun `the first spoken result is the query trimmed`() {
        assertEquals("local news", voiceQuery(ok = true, results = listOf(" local news ", "local noose")))
    }

    @Test
    fun `blank results are skipped`() {
        assertEquals("seven", voiceQuery(ok = true, results = listOf("", "   ", "seven")))
        assertNull(voiceQuery(ok = true, results = listOf("", " ")))
        assertNull(voiceQuery(ok = true, results = emptyList()))
        assertNull(voiceQuery(ok = true, results = null))
    }

    @Test
    fun `a dismissed recognizer gives no query`() {
        assertNull(voiceQuery(ok = false, results = listOf("ignored")))
    }
}
