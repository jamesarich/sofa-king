package dev.jamesrich.sofaking.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdRunTest {
    @Test fun `a run reads back from its tag`() {
        assertEquals("#EXT-X-SOFA-AD:FIRST=12,LAST=15,ENDS=144.611", AdRun(12, 15, 144_611).tag())
        assertEquals(AdRun(12, 15, 144_611), AdRun.parse(AdRun(12, 15, 144_611).tag()))
        assertEquals(AdRun(7, 7), AdRun.parse(AdRun(7, 7).tag()))
        assertEquals("#EXT-X-SOFA-AD:FIRST=7,LAST=7,ENDS=0.050", AdRun(7, 7, 50).tag())
    }

    @Test fun `other tags and broken runs are not runs`() {
        assertNull(AdRun.parse("#EXT-X-CUE-OUT:120"))
        assertNull(AdRun.parse("#EXT-X-SOFA-AD:FIRST=9,LAST=3"))
        assertNull(AdRun.parse("#EXT-X-SOFA-AD:LAST=3"))
        assertEquals(AdRun(1, 2), AdRun.parse("#EXT-X-SOFA-AD:FIRST=1,LAST=2,ENDS=soon,NEW=1"))
    }
}
