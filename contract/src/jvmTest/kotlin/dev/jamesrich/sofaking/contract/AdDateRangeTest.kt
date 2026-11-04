package dev.jamesrich.sofaking.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdDateRangeTest {
    private val start = "2026-11-04T12:00:00.000Z"

    @Test fun `a range reads back from its tag`() {
        val tag = AdDateRange.tag("sofa-ad-12", start, durationMs = 144_611)
        assertEquals(
            "#EXT-X-DATERANGE:ID=\"sofa-ad-12\",CLASS=\"dev.jamesrich.sofaking.ad\"," +
                "START-DATE=\"2026-11-04T12:00:00.000Z\",DURATION=144.611",
            tag,
        )
        assertEquals(AdDateRange("sofa-ad-12", start, durationMs = 144_611), AdDateRange.parse(tag))
        val planned = AdDateRange.tag("sofa-ad-7", start, plannedDurationMs = 30_000)
        assertEquals(AdDateRange("sofa-ad-7", start, plannedDurationMs = 30_000), AdDateRange.parse(planned))
        assertEquals(AdDateRange("sofa-ad-9", start), AdDateRange.parse(AdDateRange.tag("sofa-ad-9", start)))
    }

    @Test fun `seconds keep up to three decimals`() {
        assertTrue(AdDateRange.tag("a", start, plannedDurationMs = 50).endsWith("PLANNED-DURATION=0.05"))
        assertTrue(AdDateRange.tag("a", start, durationMs = 30_000).endsWith("DURATION=30"))
    }

    @Test fun `bare attributes in any order read`() {
        val line = "#EXT-X-DATERANGE:START-DATE=\"$start\",DURATION=30.5,CLASS=dev.jamesrich.sofaking.ad,ID=x"
        assertEquals(AdDateRange("x", start, durationMs = 30_500), AdDateRange.parse(line))
    }

    @Test fun `other tags, other classes and broken ranges are not ranges`() {
        assertNull(AdDateRange.parse("#EXT-X-SOFA-AD:FIRST=1,LAST=2"))
        assertNull(AdDateRange.parse("#EXT-X-DATERANGE:ID=\"a\",CLASS=\"com.example.other\",START-DATE=\"$start\""))
        assertNull(AdDateRange.parse("#EXT-X-DATERANGE:ID=\"a\",START-DATE=\"$start\""))
        assertNull(AdDateRange.parse("#EXT-X-DATERANGE:CLASS=\"dev.jamesrich.sofaking.ad\",START-DATE=\"$start\""))
        assertNull(AdDateRange.parse("#EXT-X-DATERANGE:ID=\"a\",CLASS=\"dev.jamesrich.sofaking.ad\""))
    }
}
