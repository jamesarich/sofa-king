package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.providers.BROWSER_USER_AGENT
import dev.jamesrich.sofaking.backend.providers.ISO_MILLIS
import java.time.Clock
import java.time.temporal.ChronoUnit

/** What a template can read while it renders. */
internal class TemplateScope(
    val options: Map<String, String>,
    val defaults: Map<String, String>,
    val clock: Clock,
    val deviceId: suspend () -> String,
    val page: Int? = null,
    val channelId: String? = null,
    val channelVars: Map<String, String> = emptyMap(),
    val cursor: String? = null,
    val channelCount: Int? = null,
)

/**
 * Which names a template may use where it sits; [channelVars] is null where no channel is in scope, and [cursor]
 * is true in a request paged by `cursorPath`.
 */
internal class TemplateNames(
    val options: Set<String>,
    val channelVars: Set<String>?,
    val page: Boolean,
    val cursor: Boolean = false,
)

/** `${name}` substitution; ad macros in square or curly brackets are not touched. */
internal object ManifestTemplate {
    private val token = Regex("""\$\{([^}]*)}""")
    private val option = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val offset = Regex("[+-]?\\d{1,4}h")

    fun references(template: String): List<String> = token.findAll(template).map { it.groupValues[1] }.toList()

    /** Why [name] is not usable here, or null. */
    fun problem(name: String, names: TemplateNames): String? = when {
        name == "page" -> "\${page} is only for a paged request".takeUnless { names.page }

        name == "cursor" || name == "channels.count" ->
            "\${$name} is only for a request paged by cursorPath".takeUnless { names.cursor }

        name == "channel.id" -> "\${channel.id} needs a channel in scope".takeIf { names.channelVars == null }

        name.startsWith("channel.vars.") -> {
            val key = name.removePrefix("channel.vars.")
            when {
                names.channelVars == null -> "\${$name} needs a channel in scope"
                key !in names.channelVars -> "\${$name} names a variable no channel sets"
                else -> null
            }
        }

        name == "device.id" || name == "ua.browser" || name == "time.epoch" -> null

        name.startsWith("time.bucket:") ->
            "\${$name} needs a positive number".takeUnless {
                name.removePrefix("time.bucket:").toLongOrNull()?.let {
                    it >
                        0
                } ==
                    true
            }

        name.startsWith("time.isoMillis:") ->
            "\${$name} needs an offset such as -1h".takeUnless { offset.matches(name.removePrefix("time.isoMillis:")) }

        name.startsWith("time.floorHour:") ->
            "\${$name} needs an offset such as +47h".takeUnless { offset.matches(name.removePrefix("time.floorHour:")) }

        option.matches(name) -> "\${$name} is not an option or a default".takeIf { name !in names.options }

        else -> "\${$name} is not a known name"
    }

    suspend fun render(template: String, scope: TemplateScope): String {
        val out = StringBuilder()
        var last = 0
        for (match in token.findAll(template)) {
            out.append(template, last, match.range.first)
            out.append(value(match.groupValues[1], scope))
            last = match.range.last + 1
        }
        return out.append(template, last, template.length).toString()
    }

    private suspend fun value(name: String, scope: TemplateScope): String {
        val now = scope.clock.instant()
        return when {
            name == "page" -> scope.page?.toString().orEmpty()

            name == "cursor" -> scope.cursor.orEmpty()

            name == "channels.count" -> scope.channelCount?.toString().orEmpty()

            name == "channel.id" -> scope.channelId.orEmpty()

            name.startsWith("channel.vars.") -> scope.channelVars[name.removePrefix("channel.vars.")].orEmpty()

            name == "device.id" -> scope.deviceId()

            name == "ua.browser" -> BROWSER_USER_AGENT

            name == "time.epoch" -> now.epochSecond.toString()

            name.startsWith("time.bucket:") -> (now.epochSecond / name.removePrefix("time.bucket:").toLong()).toString()

            name.startsWith("time.isoMillis:") ->
                ISO_MILLIS.format(now.plus(hours(name.removePrefix("time.isoMillis:")), ChronoUnit.HOURS))

            name.startsWith("time.floorHour:") ->
                ISO_MILLIS.format(
                    now.truncatedTo(
                        ChronoUnit.HOURS,
                    ).plus(hours(name.removePrefix("time.floorHour:")), ChronoUnit.HOURS),
                )

            else -> scope.options[name] ?: scope.defaults[name].orEmpty()
        }
    }

    private fun hours(offset: String): Long = offset.removeSuffix("h").removePrefix("+").toLong()
}
