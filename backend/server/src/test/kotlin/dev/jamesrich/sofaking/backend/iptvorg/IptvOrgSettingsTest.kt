package dev.jamesrich.sofaking.backend.iptvorg

import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.settings.RuntimeValues
import dev.jamesrich.sofaking.backend.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class IptvOrgSettingsTest {
    @Test fun `both parts are off by default and the region is unset`() {
        val settings = ServerConfig.fromEnvironment(emptyMap()).iptvOrg
        assertEquals(IptvOrgSettings(metadata = false, streams = false, region = ""), settings)
        assertFalse(settings.enabled)
        assertEquals(IptvOrgSettings(), RuntimeValues.DEFAULT.iptvOrg)
    }

    @Test fun `the environment turns each part on on its own`() {
        val metadata = ServerConfig.fromEnvironment(mapOf("SOFA_IPTV_ORG" to "on")).iptvOrg
        assertEquals(IptvOrgSettings(metadata = true), metadata)
        val streams = ServerConfig.fromEnvironment(
            mapOf("SOFA_IPTV_ORG_STREAMS" to "ON", "SOFA_IPTV_ORG_REGION" to "gb,ie"),
        ).iptvOrg
        assertEquals(IptvOrgSettings(streams = true, region = "GB,IE"), streams)
    }

    @Test fun `a region that is not country codes stops the start naming the variable`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(mapOf("SOFA_IPTV_ORG_REGION" to "UK1"))
        }
        assertEquals(Settings.IPTV_ORG_REGION.failure, failure.message)
    }

    @Test fun `the portal's rules accept a saved region and refuse a bad one`() {
        val values = RuntimeValues.from(RuntimeValues.DEFAULT, mapOf(Settings.IPTV_ORG_REGION.name to "de, at"))
        assertEquals("DE,AT", values.iptvOrg.region)
        val refused = assertFailsWith<ApiFailure> {
            RuntimeValues.from(RuntimeValues.DEFAULT, mapOf(Settings.IPTV_ORG_REGION.name to "Germany"))
        }
        assertEquals(Settings.IPTV_ORG_REGION.failure, refused.message)
    }
}
