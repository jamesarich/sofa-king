package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.iptvorg.IptvOrgSettings
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpSettingsApiTest {
    private val admin = MCP_ADMIN

    private fun set(name: String, value: String? = null, reset: Boolean? = null) = buildJsonObject {
        put("name", name)
        value?.let { put("value", it) }
        reset?.let { put("reset", it) }
    }

    @Test fun `a read token is listed neither runtime settings tool and cannot call them`() = mcp {
        val read = read()
        assertTrue(listOf("runtime_settings", "set_runtime_setting").none { it in tools(read) })
        assertTrue(tools(admin).containsAll(listOf("runtime_settings", "set_runtime_setting")))
        val response = post(
            read,
            "tools/call",
            buildJsonObject {
                put("name", "set_runtime_setting")
                put("arguments", set("SOFA_IPTV_ORG", "on"))
            },
        )
        val message = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val refused = message["error"] != null ||
            message["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean == true
        assertTrue(refused, "a read token changed a setting: $message")
        assertEquals(IptvOrgSettings(), runtime.settings.current.iptvOrg)
    }

    @Test fun `runtime_settings lists each setting with its value and source and no secret`() = mcp {
        val (failed, text) = call(admin, "runtime_settings")
        assertFalse(failed, text)
        val rows = Json.parseToJsonElement(text).jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        for (name in listOf("SOFA_IPTV_ORG", "SOFA_IPTV_ORG_STREAMS", "SOFA_IPTV_ORG_REGION", "SOFA_LOG_LEVEL")) {
            assertTrue(name in rows, "$name missing from ${rows.keys}")
        }
        val iptv = rows.getValue("SOFA_IPTV_ORG").jsonObject
        assertEquals("off", iptv["value"]!!.jsonPrimitive.content)
        assertEquals("environment", iptv["source"]!!.jsonPrimitive.content)
        assertTrue(rows.keys.none { "TOKEN" in it || "KEY" in it }, "no secret is a runtime setting")
    }

    @Test fun `set_runtime_setting turns iptv-org on and sets the region at once and reset puts it back`() = mcp {
        assertFalse(call(admin, "set_runtime_setting", set("SOFA_IPTV_ORG", "on")).first)
        assertFalse(call(admin, "set_runtime_setting", set("SOFA_IPTV_ORG_STREAMS", "on")).first)
        val (failed, text) = call(admin, "set_runtime_setting", set("SOFA_IPTV_ORG_REGION", "gb, ie"))
        assertFalse(failed, text)
        assertEquals(
            IptvOrgSettings(metadata = true, streams = true, region = "GB,IE"),
            runtime.settings.current.iptvOrg,
        )
        assertTrue("SOFA_IPTV_ORG_REGION" in runtime.settings.saved)

        val saved = Json.parseToJsonElement(call(admin, "runtime_settings").second).jsonArray
            .first { it.jsonObject["name"]!!.jsonPrimitive.content == "SOFA_IPTV_ORG" }.jsonObject
        assertEquals("saved", saved["source"]!!.jsonPrimitive.content)

        assertFalse(call(admin, "set_runtime_setting", set("SOFA_IPTV_ORG_REGION", reset = true)).first)
        assertEquals(IptvOrgSettings(metadata = true, streams = true), runtime.settings.current.iptvOrg)
        assertEquals(setOf("SOFA_IPTV_ORG", "SOFA_IPTV_ORG_STREAMS"), runtime.settings.saved, "reset leaves the others")
    }

    @Test fun `set_runtime_setting refuses an invalid value an unknown name and a missing value`() = mcp {
        val (badValue, value) = call(admin, "set_runtime_setting", set("SOFA_IPTV_ORG", "maybe"))
        assertTrue(badValue)
        assertTrue(value.startsWith("INVALID_REQUEST: "), value)
        val (badRegion, region) = call(admin, "set_runtime_setting", set("SOFA_IPTV_ORG_REGION", "GBR"))
        assertTrue(badRegion && region.startsWith("INVALID_REQUEST: "), region)
        val (unknown, name) = call(admin, "set_runtime_setting", set("SOFA_API_TOKEN", "x".repeat(40)))
        assertTrue(unknown && "not a runtime setting" in name, name)
        val (missing, why) = call(admin, "set_runtime_setting", set("SOFA_IPTV_ORG"))
        assertTrue(missing && "value is required" in why, why)
        assertEquals(IptvOrgSettings(), runtime.settings.current.iptvOrg)
        assertTrue(runtime.settings.saved.isEmpty())
    }
}
