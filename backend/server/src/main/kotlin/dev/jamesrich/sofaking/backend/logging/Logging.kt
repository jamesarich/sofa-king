package dev.jamesrich.sofaking.backend.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.Configurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.encoder.Encoder
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply
import ch.qos.logback.core.spi.ContextAwareBase
import java.io.OutputStream

/** `SOFA_LOG_LEVEL` and `SOFA_LOG_FORMAT`, validated. */
data class LogSettings(val level: String = "info", val format: String = "text") {
    companion object {
        val LEVELS = listOf("trace", "debug", "info", "warn", "error", "off")
        val FORMATS = listOf("text", "json")

        /** The settings [environment] names; an unknown value is an [IllegalArgumentException] naming the variable. */
        fun from(environment: Map<String, String>): LogSettings {
            val level = (environment["SOFA_LOG_LEVEL"] ?: "info").trim().lowercase()
            require(level in LEVELS) { "SOFA_LOG_LEVEL must be one of ${LEVELS.joinToString()}" }
            val format = (environment["SOFA_LOG_FORMAT"] ?: "text").trim().lowercase()
            require(format in FORMATS) { "SOFA_LOG_FORMAT must be one of ${FORMATS.joinToString()}" }
            return LogSettings(level, format)
        }
    }
}

/**
 * One appender on stderr at the root logger, as [settings] describe: text lines with the MDC (the request id) at the
 * end, or one JSON object per line from logback's own encoder; and the in-memory [ring] the portal's Events page reads.
 */
fun configureLogging(context: LoggerContext, settings: LogSettings, stream: OutputStream? = null, ring: EventRing? = EventRing.shared) {
    context.reset()
    val encoder: Encoder<ILoggingEvent> =
        if (settings.format == "json") {
            JsonEncoder().apply {
                setWithSequenceNumber(false)
                setWithNanoseconds(false)
                setWithContext(false)
                setWithMessage(false)
                setWithFormattedMessage(true)
                setWithArguments(false)
                setWithMarkers(false)
            }
        } else {
            PatternLayoutEncoder().apply { pattern = "%d{yyyy-MM-dd'T'HH:mm:ss.SSS'Z',UTC} %-5level [%thread] %logger{36} - %msg%replace( %mdc){'^ \$', ''}%n" }
        }
    encoder.context = context
    encoder.start()
    // A console appender leaves System.err open when the context stops; a plain stream appender would close it.
    val level = Level.toLevel(settings.level, Level.INFO)
    val threshold = Threshold(level).apply { this.context = context; start() }
    context.putObject(THRESHOLD, threshold)
    val appender = (if (stream == null) ConsoleAppender<ILoggingEvent>().apply { target = "System.err" } else OutputStreamAppender<ILoggingEvent>().apply { outputStream = stream }).apply {
        this.context = context
        name = settings.format
        this.encoder = encoder
        addFilter(threshold)
        start()
    }
    context.getLogger(Logger.ROOT_LOGGER_NAME).apply {
        // The level filters stderr; the root stays at INFO or finer, so the portal's Events page, which reads the same
        // lines from memory, keeps INFO and above whatever the level.
        this.level = rootLevel(level)
        addAppender(appender)
        if (ring != null) addAppender(RingAppender(ring).apply { this.context = context; name = "ring"; start() })
    }
}

private const val THRESHOLD = "sofa.logThreshold"

private fun rootLevel(level: Level): Level = if (level.isGreaterOrEqual(Level.INFO)) Level.INFO else level

/** Passes the events at [level] and above to stderr; the level the operator sets changes it at run time. */
private class Threshold(@Volatile var level: Level) : Filter<ILoggingEvent>() {
    override fun decide(event: ILoggingEvent): FilterReply = if (event.level.isGreaterOrEqual(level)) FilterReply.NEUTRAL else FilterReply.DENY
}

/** Sets the stderr level to [level] (one of [LogSettings.LEVELS]) on the running logback context. */
fun applyLogLevel(level: String) {
    val context = org.slf4j.LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
    val parsed = Level.toLevel(level, Level.INFO)
    (context.getObject(THRESHOLD) as? Threshold)?.level = parsed
    context.getLogger(Logger.ROOT_LOGGER_NAME).level = rootLevel(parsed)
}

/**
 * Configures logback from the environment before the first logger is used. An invalid value falls back to its
 * default here; `ServerConfig` then refuses to start with a message naming the variable.
 */
class EnvironmentLogConfigurator : ContextAwareBase(), Configurator {
    override fun configure(context: LoggerContext): Configurator.ExecutionStatus {
        val settings = try { LogSettings.from(System.getenv()) } catch (_: IllegalArgumentException) { LogSettings() }
        configureLogging(context, settings)
        return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY
    }
}
