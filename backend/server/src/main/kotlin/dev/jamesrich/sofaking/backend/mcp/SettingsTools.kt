package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.settings.SettingType
import dev.jamesrich.sofaking.backend.settings.Settings
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.Serializable

// The runtime settings, listed only to the admin token: what the portal's Settings page shows and saves. No setting
// listed here is a secret; tokens and keys have their own tools and are never shown.

private const val NAME = "name"
private const val VALUE = "value"
private const val RESET = "reset"

/** One runtime setting: its [value] now, where that comes from, and what the environment would give. */
@Serializable internal class RuntimeSettingView(
    val name: String,
    val label: String,
    val value: String,
    /** `saved` when the portal or `set_runtime_setting` saved it, `environment` when the variable decides. */
    val source: String,
    val environmentValue: String,
    val choices: List<String>? = null,
    val help: String,
)

internal fun Server.settingsTools(context: McpContext) {
    val settings = context.runtime.settings
    tool(
        "runtime_settings",
        "The settings that change without a restart, as the portal's Settings page lists them: each one's name, " +
            "current value, whether that value is saved or comes from the environment, and what the environment " +
            "gives. set_runtime_setting changes one. Includes the iptv-org switches and region.",
        annotations = READ_ONLY,
    ) {
        val current = settings.current.byName()
        val environment = settings.environment.byName()
        Settings.runtime.map { def ->
            RuntimeSettingView(
                def.name,
                def.label,
                current.getValue(def.name),
                if (def.name in settings.saved) "saved" else "environment",
                environment.getValue(def.name),
                (def.type as? SettingType.Choice)?.values,
                def.help,
            )
        }
    }
    tool(
        "set_runtime_setting",
        "Save one runtime setting and apply it at once, as the portal's Settings page does, checked by the same " +
            "rules; runtime_settings lists the names. With reset true the setting follows the environment again. " +
            "An empty value is not accepted: reset clears one. Turn iptv-org on with SOFA_IPTV_ORG and " +
            "SOFA_IPTV_ORG_STREAMS set to on, and pick countries with SOFA_IPTV_ORG_REGION, such as GB,IE.",
        schema(
            NAME to string("The setting's variable name, from runtime_settings"),
            VALUE to string("The value to save"),
            RESET to boolean("true puts the setting back to the environment's value; leave value out"),
            required = listOf(NAME),
        ),
        UPKEEP,
    ) { arguments ->
        val name = arguments.string(NAME) ?: invalid("name is required; call runtime_settings for the names")
        if (Settings.runtime.none { it.name == name }) {
            invalid("$name is not a runtime setting; call runtime_settings for the names")
        }
        val kept = settings.current.byName().filterKeys { it in settings.saved }
        val next = if (arguments.boolean(RESET) == true) {
            kept - name
        } else {
            kept + (name to (arguments.string(VALUE) ?: invalid("value is required, or reset true")))
        }
        val values = settings.update(next).byName()
        ActionResult(true, "$name is now ${values.getValue(name).ifEmpty { "empty" }}")
    }
}
