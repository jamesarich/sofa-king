package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashScrubTest {
    private val context = CrashContext("0.30.0-12", "android-tv", "Android 14 and API 34", "Chromecast")
    private val tokenShaped = Regex("[0-9a-fA-F]{16,}|[A-Za-z0-9_+/=-]{24,}")

    @Test
    fun `a url is replaced and no scheme separator survives`() {
        val text = CrashScrub.text("Failed GET http://192.168.1.20:9193/api/v1/x?token=abc and ftp://nas/a", 300)
        assertFalse(text.contains("://"), text)
        assertFalse(text.contains("192.168"), text)
        assertTrue(text.contains("[url]"), text)
    }

    @Test
    fun `a stray scheme separator is replaced too`() {
        assertFalse(CrashScrub.text("lost :// somewhere", 100).contains("://"))
    }

    @Test
    fun `a bearer token and a long hex run are replaced`() {
        val text = CrashScrub.text(
            "auth Bearer sk_0123456789abcdef0123456789abcdef then 0123456789abcdef0123456789abcdef",
            300,
        )
        assertFalse(tokenShaped.containsMatchIn(text), text)
        assertEquals("auth [token] then [token]", text)
    }

    @Test
    fun `a long mixed run is a token but a long name of letters is not`() {
        assertEquals("key [token]", CrashScrub.text("key Zm9vYmFyMTIzNDU2Nzg5MGFiY2RlZg", 100))
        assertEquals("moveFocusToNextAvailableRow", CrashScrub.text("moveFocusToNextAvailableRow", 100))
    }

    @Test
    fun `an address an e-mail and quoted text are replaced`() {
        val text = CrashScrub.text("""Unable to resolve host "nas.lan" for me@example.com at 10.0.0.5:80""", 300)
        assertEquals("""Unable to resolve host "…" for [email] at [address]""", text)
    }

    @Test
    fun `text is cut to its limit`() {
        assertEquals(10, CrashScrub.text("x".repeat(500), 10).length)
    }

    @Test
    fun `a network failure keeps no message`() {
        assertEquals("", CrashScrub.message("java.net.UnknownHostException", "nas.lan"))
        assertEquals("", CrashScrub.message("okhttp3.internal.http2.StreamResetException", "stream was reset"))
    }

    @Test
    fun `another failure keeps its scrubbed message`() {
        val message = CrashScrub.message("java.lang.IndexOutOfBoundsException", "Index 5 out of bounds")
        assertEquals("Index 5 out of bounds", message)
        assertEquals("", CrashScrub.message("java.lang.IllegalStateException", null))
    }

    @Test
    fun `frames keep the at lines and a cause's class only`() {
        val trace = """
            java.lang.IllegalStateException: Channel Evening News on http://nas/x is gone
            	at dev.jamesrich.sofaking.ui.guide.GuideGrid.focusRow(GuideGrid.kt:212)
            	at androidx.compose.ui.focus.FocusOwnerImpl.moveFocus(FocusOwnerImpl.kt:98)
            Caused by: java.io.IOException: GET http://nas/stream failed for Evening News
            	at okhttp3.internal.connection.RealCall.execute(RealCall.kt:154)
            	... 12 more
        """.trimIndent()
        assertEquals(
            listOf(
                "dev.jamesrich.sofaking.ui.guide.GuideGrid.focusRow(GuideGrid.kt:212)",
                "androidx.compose.ui.focus.FocusOwnerImpl.moveFocus(FocusOwnerImpl.kt:98)",
                "Caused by: java.io.IOException",
                "okhttp3.internal.connection.RealCall.execute(RealCall.kt:154)",
            ),
            CrashScrub.frames(trace),
        )
    }

    @Test
    fun `frames are limited in number and length`() {
        val trace = (1..200).joinToString("\n") { "\tat a.B.c$it(" + "G".repeat(400) + ".kt:$it)" }
        val frames = CrashScrub.frames(trace)
        assertEquals(CrashLimits.MAX_FRAMES, frames.size)
        assertTrue(frames.all { it.length <= CrashLimits.MAX_FRAME })
    }

    @Test
    fun `a report holds no address no token and no title`() {
        val trace = """
            java.lang.IllegalStateException: Evening News at http://192.168.1.20:9193
            	at dev.jamesrich.sofaking.A.b(A.kt:1)
        """.trimIndent()
        val report = CrashScrub.report(
            "java.lang.IllegalStateException",
            "Evening News at http://192.168.1.20:9193 with Bearer abcdefghijklmnopqrstuvwxyz123456",
            trace,
            context,
            "id-1",
            "2026-10-28T21:14:05Z",
        )
        val json = crashJson.encodeToString(report)
        assertFalse(json.contains("://"), json)
        assertFalse(json.contains("192.168"), json)
        assertFalse(tokenShaped.containsMatchIn(json.replace(Regex("\"id\":\"[^\"]*\""), "")), json)
        assertEquals("android-tv", report.platform)
        assertEquals("0.30.0-12", report.appVersion)
        assertEquals("Chromecast", report.device)
    }

    @Test
    fun `a report is cut to fit by dropping its trailing frames`() {
        val frames = List(100) { "a.B.c$it(" + "G".repeat(400) + ".kt:$it)" }
        val report = CrashScrub.fit(CrashScrub.report("X", "m", "", context, "id", "t").copy(frames = frames))
        assertTrue(crashJson.encodeToString(report).encodeToByteArray().size <= CrashLimits.MAX_BYTES)
        assertTrue(report.frames.isNotEmpty())
        assertEquals(frames.take(report.frames.size), report.frames)
    }
}
