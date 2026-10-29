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
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.spi.ContextAwareBase
import ch.qos.logback.core.spi.FilterReply
import ch.qos.logback.core.util.FileSize
import dev.jamesrich.sofaking.backend.EnvironmentReader
import dev.jamesrich.sofaking.backend.settings.SettingType
import dev.jamesrich.sofaking.backend.settings.Settings
import java.io.OutputStream
import java.nio.file.Path

/** `SOFA_LOG_LEVEL`, `SOFA_LOG_FORMAT` and `SOFA_LOG_DAYS`, validated. */
data class LogSettings(val level: String = "info", val format: String = "text", val days: Int = DEFAULT_LOG_DAYS) {
    companion object {
        val LEVELS = (Settings.LOG_LEVEL.type as SettingType.Choice).values
        val FORMATS = (Settings.LOG_FORMAT.type as SettingType.Choice).values
        val DAYS = Settings.LOG_DAYS.range

        /** The settings [environment] names; an unknown value is an [IllegalArgumentException] naming the variable. */
        fun from(environment: Map<String, String>): LogSettings {
            val env = EnvironmentReader(environment)
            return LogSettings(
                env.choice(Settings.LOG_LEVEL),
                env.choice(Settings.LOG_FORMAT),
                env.number(Settings.LOG_DAYS),
            )
        }
    }
}

/**
 * One appender on stderr at the root logger, as [settings] describe: text lines with the MDC (the request id) at the
 * end, or one JSON object per line from logback's own encoder; the same lines in a daily file under [directory], kept
 * [LogSettings.days] days, so the log outlives the container; and the in-memory [ring] the portal's Events page reads.
 */
fun configureLogging(
    context: LoggerContext,
    settings: LogSettings,
    stream: OutputStream? = null,
    ring: EventRing? = EventRing.shared,
    directory: Path? = null,
) {
    context.reset()
    // A console appender leaves System.err open when the context stops; a plain stream appender would close it.
    val level = Level.toLevel(settings.level, Level.INFO)
    val threshold = Threshold(level).apply {
        this.context = context
        start()
    }
    context.putObject(THRESHOLD, threshold)
    val appender = (
        if (stream == null) {
            ConsoleAppender<ILoggingEvent>().apply { target = "System.err" }
        } else {
            OutputStreamAppender<ILoggingEvent>().apply {
                outputStream =
                    stream
            }
        }
        ).apply {
        this.context = context
        name = settings.format
        this.encoder = encoder(context, settings.format)
        addFilter(threshold)
        start()
    }
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    // The level filters stderr; the root stays at INFO or finer, so the portal's Events page, which reads the same
    // lines from memory, keeps INFO and above whatever the level.
    root.level = rootLevel(level)
    root.addAppender(appender)
    if (directory != null && settings.days > 0) root.addAppender(fileAppender(context, settings, directory, threshold))
    if (ring != null) {
        root.addAppender(
            RingAppender(ring).apply {
                this.context = context
                name = "ring"
                start()
            },
        )
    }
}

/**
 * `server.log` in [directory], rolled at midnight UTC or at [LOG_FILE_SIZE] into gzipped `server.<day>.<n>.log.gz`
 * files, the oldest deleted past [LogSettings.days] days or [LOG_TOTAL_SIZE] in all.
 */
private fun fileAppender(
    context: LoggerContext,
    settings: LogSettings,
    directory: Path,
    threshold: Threshold,
): RollingFileAppender<ILoggingEvent> {
    val appender = RollingFileAppender<ILoggingEvent>()
    appender.context = context
    appender.name = "file"
    appender.file = directory.resolve(LOG_FILE).toString()
    val policy = SizeAndTimeBasedRollingPolicy<ILoggingEvent>().apply {
        this.context = context
        fileNamePattern = directory.resolve("server.%d{yyyy-MM-dd,UTC}.%i.log.gz").toString()
        setMaxFileSize(FileSize.valueOf(LOG_FILE_SIZE))
        maxHistory = settings.days
        setTotalSizeCap(FileSize.valueOf(LOG_TOTAL_SIZE))
        setParent(appender)
        start()
    }
    appender.rollingPolicy = policy
    appender.encoder = encoder(context, settings.format)
    appender.addFilter(threshold)
    appender.start()
    return appender
}

/** The text pattern or logback's JSON encoder, started. */
private fun encoder(context: LoggerContext, format: String): Encoder<ILoggingEvent> {
    val encoder: Encoder<ILoggingEvent> =
        if (format == "json") {
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
            PatternLayoutEncoder().apply {
                pattern =
                    "%d{yyyy-MM-dd'T'HH:mm:ss.SSS'Z',UTC} %-5level [%thread] %logger{36} - %msg%replace( %mdc){'^ " +
                    "\$', ''}%n"
            }
        }
    encoder.context = context
    encoder.start()
    return encoder
}

private const val THRESHOLD = "sofa.logThreshold"

/** The folder in the data directory that holds the log files, and the current file's name in it. */
const val LOG_FOLDER = "logs"
const val LOG_FILE = "server.log"

/** The log file the server writes now under [dataDirectory]. */
fun logFile(dataDirectory: Path): Path = dataDirectory.resolve(LOG_FOLDER).resolve(LOG_FILE)

/** How many days of log files are kept by default, and at most. */
const val DEFAULT_LOG_DAYS = 14

/** The size at which the day's log file rolls early, and the most all of them may take. */
private const val LOG_FILE_SIZE = "20MB"
private const val LOG_TOTAL_SIZE = "250MB"

private fun rootLevel(level: Level): Level = if (level.isGreaterOrEqual(Level.INFO)) Level.INFO else level

/** Passes the events at [level] and above to stderr and the file; the operator's level changes it at run time. */
private class Threshold(@Volatile var level: Level) : Filter<ILoggingEvent>() {
    override fun decide(event: ILoggingEvent): FilterReply =
        if (event.level.isGreaterOrEqual(level)) FilterReply.NEUTRAL else FilterReply.DENY
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
class EnvironmentLogConfigurator :
    ContextAwareBase(),
    Configurator {
    override fun configure(context: LoggerContext): Configurator.ExecutionStatus {
        val settings = try {
            LogSettings.from(System.getenv())
        } catch (_: IllegalArgumentException) {
            LogSettings()
        }
        val data = Path.of(System.getenv("SOFA_DATA_DIR")?.takeIf { it.isNotBlank() } ?: "data")
        configureLogging(context, settings, directory = data.resolve(LOG_FOLDER))
        return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY
    }
}
