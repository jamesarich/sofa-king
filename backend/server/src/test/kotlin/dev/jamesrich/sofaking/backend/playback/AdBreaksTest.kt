package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.contract.AdRun
import java.net.URI
import kotlin.test.*

/** The playlists here are ones Pluto and Tubi served on 2026-10-22, with their paths shortened and their tokens removed. */
class AdBreaksTest {
    private val plutoBase = URI("https://siloh-ns1.plutotv.net/v2/stitch/hls/channel/5f1ac1f1/master.m3u8")
    private val tubiBase = URI("https://aegis-cloudfront-1.tubi.video/b18a776e/master_5.m3u8")

    private fun ads(text: String, base: URI) = assertNotNull(AdBreaks.parse(text, base)).segments.map { it.ad }

    /** A show clip under its AES key, then stitched creatives from the ad muxer after a discontinuity. */
    private val pluto = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:5
#EXT-X-DISCONTINUITY-SEQUENCE:0
#EXT-X-MEDIA-SEQUENCE:121
#EXT-X-PROGRAM-DATE-TIME:2026-10-22T01:06:51.266Z
#EXT-X-KEY:METHOD=AES-128,URI="https://siloh-ns1.plutotv.net/608_pluto/clip/65ca8c70_show/720pDRM/hls/hls_300_keyfile"
#EXTINF:5,
https://siloh-ns1.plutotv.net/608_pluto/clip/65ca8c70_show/720pDRM/hls/hls_300-00121.ts
#EXTINF:3.867,
https://siloh-ns1.plutotv.net/608_pluto/clip/65ca8c70_show/720pDRM/hls/hls_300-00122.ts
#EXT-X-DISCONTINUITY
#EXT-X-PROGRAM-DATE-TIME:2026-10-22T01:07:00.133Z
#EXT-X-KEY:METHOD=NONE
#EXTINF:5,
https://siloh-ns1.plutotv.net/v1/mp4/c(ts)/h(default)/max(6)/rev(1)/p(2_ad%2Fcreative%2F6abec2e1_ad%2F720p%2Fvideo_600.mp4)/id3(p=clik)/0.ts
#EXTINF:5,
https://siloh-ns1.plutotv.net/mp4/v1/c(ts)/h(default)/max(6)/rev(1)/p(5eb%2F6ad7cef8%2F720p%2Fvideo_600.mp4)/1.ts
#PLUTO-VERSION:2.93.1-production-kvetch-hls
"""

    @Test fun `Pluto's ad muxer segments in either path form are ads and the run has no planned end`() {
        assertEquals(listOf(false, false, true, true), ads(pluto, plutoBase))
        assertEquals(listOf(AdRun(123, 124)), AdBreaks.runs(pluto, plutoBase))
    }

    @Test fun `Pluto's bumpers in both spellings are part of the break and an episode clip is not`() {
        val bumpers = """#EXTM3U
#EXT-X-MEDIA-SEQUENCE:40
#EXTINF:5,
https://siloh-ns1.plutotv.net/951_Pluto_TV_OandO/clip/56c2dcd6_ptv_7423_ad_bumper_animation_orbit_15/720p/hls/0.ts
#EXT-X-DISCONTINUITY
#EXTINF:5,
https://siloh-ns1.plutotv.net/160_Pluto_TV_OandO/clip/58e5397b_ptv_7424adbumperanimationdotsinverted30_30fps/1080pDRM/hls/0.ts
#EXT-X-DISCONTINUITY
#EXTINF:5,
https://siloh-ns1.plutotv.net/967_pluto/clip/6a95facf_ab2537c1/720p/hls/0.ts
"""
        assertEquals(listOf(true, true, false), ads(bumpers, plutoBase))
    }

    @Test fun `the muxer path means nothing on another host`() {
        assertEquals(listOf(false, false, false, false), ads(pluto.replace("siloh-ns1.plutotv.net", "cdn.example.test"), plutoBase))
    }

    @Test fun `the show coming back after Pluto ads ends the run`() {
        val back = pluto.replace("#PLUTO-VERSION:2.93.1-production-kvetch-hls\n", "") + """#EXT-X-DISCONTINUITY
#EXTINF:5,
https://siloh-ns1.plutotv.net/182_CBS_TV/clip/6126d98b_CSI_S2E15/720p/hls/hls_600-00400.ts
"""
        assertEquals(listOf(false, false, true, true, false), ads(back, plutoBase))
        assertEquals(listOf(AdRun(123, 124)), AdBreaks.runs(back, plutoBase))
    }

    /** No program dates; a `CUE-OUT` with the planned length on a splice-cut boundary, `CUE-OUT-CONT` on each ad segment. */
    private val tubi = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:5535769
#EXT-X-DISCONTINUITY-SEQUENCE:0

#EXT-X-KEY:METHOD=NONE
#EXTINF:6.006,
https://aegis-cloudfront-1.tubi.video/b18a776e/master_5_5535910.ts
#EXTINF:0.601,
https://aegis-cloudfront-1.tubi.video/b18a776e/master_5_5535911.ts
#EXT-X-CUE-OUT:120.020
#EXTINF:5.406,
https://aegis-cloudfront-1.tubi.video/b18a776e/master_5_5535912.ts
#EXT-X-CUE-OUT-CONT:ElapsedTime=5.406,Duration=120.020
#EXTINF:6.006,
https://aegis-cloudfront-1.tubi.video/b18a776e/master_5_5535913.ts
"""

    @Test fun `a CUE-OUT starts a run that ends where the stream plans`() {
        assertEquals(listOf(false, false, true, true), ads(tubi, tubiBase))
        assertEquals(listOf(AdRun(5_535_771, 5_535_772, 120_020)), AdBreaks.runs(tubi, tubiBase))
    }

    @Test fun `CUE-IN ends the run at the next segment even before the planned end`() {
        val back = tubi + "#EXT-X-CUE-IN\n#EXTINF:6.006,\nhttps://aegis-cloudfront-1.tubi.video/b18a776e/master_5_5535914.ts\n"
        assertEquals(listOf(false, false, true, true, false), ads(back, tubiBase))
        assertEquals(listOf(AdRun(5_535_771, 5_535_772, 120_020)), AdBreaks.runs(back, tubiBase))
    }

    @Test fun `a window that holds only CUE-OUT-CONT plans the end from what has played`() {
        val joined = """#EXTM3U
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:1619900
#EXT-X-CUE-OUT-CONT:ElapsedTime=1.935,Duration=150.017
#EXTINF:6.006,
https://aegis-cloudfront-1.tubi.video/a3be1329/master_1619900.ts
#EXT-X-CUE-OUT-CONT:ElapsedTime=7.941,Duration=150.017
#EXTINF:6.006,
https://aegis-cloudfront-1.tubi.video/a3be1329/master_1619901.ts
"""
        assertEquals(listOf(AdRun(1_619_900, 1_619_901, 148_082)), AdBreaks.runs(joined, tubiBase))
    }

    @Test fun `a DATERANGE splice out marks the dated segments it covers`() {
        val text = """#EXTM3U
#EXT-X-MEDIA-SEQUENCE:1
#EXT-X-PROGRAM-DATE-TIME:2026-10-22T02:00:00Z
#EXT-X-DATERANGE:ID="splice-1",START-DATE="2026-10-22T02:00:04Z",PLANNED-DURATION=8,SCTE35-OUT=0xFC30
#EXT-X-DATERANGE:ID="other",START-DATE="2026-10-22T02:00:00Z",DURATION=100
#EXTINF:4,
a.ts
#EXTINF:4,
b.ts
#EXTINF:4,
c.ts
#EXTINF:4,
d.ts
"""
        assertEquals(listOf(false, true, true, false), ads(text, URI("https://cdn.example.test/live.m3u8")))
    }

    @Test fun `annotating adds one tag per run and leaves a playlist with no ads alone`() {
        val annotated = AdBreaks.annotate(tubi, tubiBase)
        assertTrue(annotated.startsWith(tubi.trimEnd()))
        assertTrue(annotated.endsWith("\n#EXT-X-SOFA-AD:FIRST=5535771,LAST=5535772,ENDS=120.020\n"))
        val show = pluto.substringBefore("#EXT-X-DISCONTINUITY\n")
        assertEquals(show, AdBreaks.annotate(show, plutoBase))
        val multivariant = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvariant.m3u8\n"
        assertNull(AdBreaks.parse(multivariant, plutoBase))
        assertEquals(multivariant, AdBreaks.annotate(multivariant, plutoBase))
    }

    @Test fun `a discontinuity alone is not a break`() {
        val episodes = pluto.substringBefore("#EXT-X-KEY:METHOD=NONE\n") + "#EXTINF:5,\nhttps://siloh-ns1.plutotv.net/967_pluto/clip/6a95facf/720p/hls/0.ts\n"
        assertEquals(listOf(false, false, false), ads(episodes, plutoBase))
    }
}
