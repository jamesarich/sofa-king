package dev.jamesrich.sofaking.backend.iptvorg

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IptvOrgRegionTest {
    private val zones = mapOf("Europe/London" to "GB", "America/New_York" to "US")

    @Test fun `the setting wins over the time zone`() {
        val region = effectiveRegion("de, at", ZoneId.of("Europe/London"), zones)
        assertEquals(listOf("DE", "AT"), region.countries)
        assertEquals("setting", region.source)
    }

    @Test fun `an unset region comes from the first country of the time zone`() {
        val region = effectiveRegion("", ZoneId.of("Europe/London"), zones)
        assertEquals(listOf("GB"), region.countries)
        assertEquals("time-zone", region.source)
        assertEquals("from the server's time zone Europe/London", region.detail)
    }

    @Test fun `a zone the list does not know falls back to the US`() {
        val region = effectiveRegion("", ZoneId.of("Asia/Tokyo"), zones)
        assertEquals(listOf("US"), region.countries)
        assertEquals("default", region.source)
        assertEquals(listOf("US"), effectiveRegion("", ZoneId.of("Europe/London"), emptyMap()).countries)
    }

    @Test fun `a region is kept as upper case two letter codes`() {
        assertEquals("GB,IE", IptvOrgSettings.cleanRegion(" gb , ie,GB"))
        assertEquals("", IptvOrgSettings.cleanRegion(""))
        assertNull(IptvOrgSettings.cleanRegion("GBR"))
        assertNull(IptvOrgSettings.cleanRegion("G1"))
    }
}
