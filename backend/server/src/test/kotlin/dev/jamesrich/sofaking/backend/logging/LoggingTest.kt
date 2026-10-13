package dev.jamesrich.sofaking.backend.logging

import ch.qos.logback.classic.LoggerContext
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.MDC
import kotlin.test.*

class LoggingTest {
    private fun capture(settings: LogSettings, block: (LoggerContext) -> Unit): String {
        val out = ByteArrayOutputStream()
        val context = LoggerContext().apply { mdcAdapter = MDC.getMDCAdapter() }
        try {
            configureLogging(context, settings, out)
            block(context)
        } finally { context.stop() }
        return out.toString(Charsets.UTF_8)
    }

    @Test fun `the defaults are info and text, and unknown values name their variable`() {
        assertEquals(LogSettings("info", "text"), LogSettings.from(emptyMap()))
        assertEquals(LogSettings("debug", "json"), LogSettings.from(mapOf("SOFA_LOG_LEVEL" to "DEBUG", "SOFA_LOG_FORMAT" to " json ")))
        assertEquals("SOFA_LOG_LEVEL must be one of trace, debug, info, warn, error, off", assertFailsWith<IllegalArgumentException> { LogSettings.from(mapOf("SOFA_LOG_LEVEL" to "loud")) }.message)
        assertEquals("SOFA_LOG_FORMAT must be one of text, json", assertFailsWith<IllegalArgumentException> { LogSettings.from(mapOf("SOFA_LOG_FORMAT" to "xml")) }.message)
    }

    @Test fun `text lines carry the level, logger, message and request id, and the level filters`() {
        val text = capture(LogSettings("info", "text")) { context ->
            val log = context.getLogger("dev.jamesrich.sofaking.backend.Test")
            log.debug("hidden")
            MDC.put("requestId", "0123abcd")
            try { log.info("import provider={} channels={}", "fixture", 3) } finally { MDC.remove("requestId") }
            log.warn("plain")
        }
        val lines = text.lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size, text)
        assertTrue(lines[0].matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z INFO  \[[^\]]+] \S*Test - import provider=fixture channels=3 requestId=0123abcd""")), lines[0])
        assertTrue(lines[1].endsWith("Test - plain"), "no trailing MDC when it is empty: ${lines[1]}")
    }

    @Test fun `json lines are one object each with the formatted message and the MDC`() {
        val text = capture(LogSettings("info", "json")) { context ->
            MDC.put("requestId", "0123abcd")
            try { context.getLogger("dev.jamesrich.sofaking.backend.Test").info("import provider={}", "fixture") } finally { MDC.remove("requestId") }
        }
        val line = Json.parseToJsonElement(text.lines().single { it.isNotEmpty() }).jsonObject
        assertEquals("INFO", line["level"]?.jsonPrimitive?.content)
        assertEquals("dev.jamesrich.sofaking.backend.Test", line["loggerName"]?.jsonPrimitive?.content)
        assertEquals("import provider=fixture", line["formattedMessage"]?.jsonPrimitive?.content)
        assertEquals("0123abcd", line["mdc"]?.jsonObject?.get("requestId")?.jsonPrimitive?.content)
        assertNull(line["arguments"], "arguments are not repeated: $line")
    }
}
