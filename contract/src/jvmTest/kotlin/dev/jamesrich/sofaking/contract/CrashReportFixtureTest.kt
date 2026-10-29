package dev.jamesrich.sofaking.contract

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CrashReportFixtureTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val fixture = checkNotNull(javaClass.getResource("/wire/v1/crash-report.json")).readText()

    @Test
    fun `the crash report fixture decodes with every field`() {
        val report = json.decodeFromString<CrashReport>(fixture)
        assertEquals("java.lang.IllegalStateException", report.exception)
        assertEquals(2, report.frames.size)
        assertEquals("android-tv", report.platform)
        assertEquals("Chromecast", report.device)
        assertEquals("2026-10-28T21:14:05Z", report.occurredAt)
    }

    @Test
    fun `a crash report with only its exception still decodes`() {
        assertEquals("X", json.decodeFromString<CrashReport>("""{"exception":"X"}""").exception)
    }

    @Test
    fun `a crash report round trips through its fixture`() {
        val report = json.decodeFromString<CrashReport>(fixture)
        val again = json.decodeFromString<CrashReport>(json.encodeToString(CrashReport.serializer(), report))
        assertEquals(report, again)
    }
}
