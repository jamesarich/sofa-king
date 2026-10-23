package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.contract.AdRun
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The playlists here are ones Pluto, Tubi, Xumo, Plex, Roku, Whale and Stirr served on 2026-10-22 and 23, with their paths shortened and their tokens removed. */
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

    @Test fun `a creative straight from Pluto's ad store between bumpers keeps the break whole`() {
        val pod = """#EXTM3U
#EXT-X-MEDIA-SEQUENCE:172
#EXTINF:5.013,
https://siloh-ns1.plutotv.net/v1/mp4/c(ts)/h(default)/max(3)/rev(1)/p(2_ad%2Fcreative%2F6ac32127_ad%2F720p%2Fvideo_600.mp4)/2.ts
#EXT-X-DISCONTINUITY
#EXTINF:5,
https://siloh-ns1.plutotv.net/2_ad/creative/6883a82f_ad/720p/20250725_155215/hls/hls_1600_00002.ts
#EXTINF:5,
https://siloh-ns1.plutotv.net/2_ad/creative/6883a82f_ad/720p/20250725_155215/hls/hls_1600_00003.ts
#EXT-X-DISCONTINUITY
#EXTINF:5,
https://siloh-ns1.plutotv.net/160_Pluto_TV_OandO/clip/58e5397b_ptv_7424adbumperanimationdotsinverted30_30fps/1080pDRM/hls/0.ts
#EXT-X-DISCONTINUITY
#EXTINF:5,
https://siloh-ns1.plutotv.net/406_CBS_TV/clip/6126d984_CSI_S2E18/720p/20230303_180600/hls/hls_1600-00078.ts
"""
        assertEquals(listOf(true, true, true, true, false), ads(pod, plutoBase))
        assertEquals(listOf(AdRun(172, 175)), AdBreaks.runs(pod, plutoBase))
    }

    @Test fun `the muxer path means nothing on another host`() {
        assertEquals(
            listOf(false, false, false, false),
            ads(pluto.replace("siloh-ns1.plutotv.net", "cdn.example.test"), plutoBase),
        )
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
        val back =
            tubi + "#EXT-X-CUE-IN\n#EXTINF:6.006,\nhttps://aegis-cloudfront-1.tubi.video/b18a776e/master_5_5535914.ts\n"
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
        val episodes =
            pluto.substringBefore("#EXT-X-KEY:METHOD=NONE\n") +
                "#EXTINF:5,\nhttps://siloh-ns1.plutotv.net/967_pluto/clip/6a95facf/720p/hls/0.ts\n"
        assertEquals(listOf(false, false, false), ads(episodes, plutoBase))
    }

    @Test fun `MediaTailor's stitched segments are ads whether a CDN or MediaTailor serves them and the show is not`() {
        val xumo = """#EXTM3U
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:3504693
#EXTINF:6.0,
https://xumo-xumoent-ch827-uj0ch.fast.nbcuni.com/live/master_6/00350/master_6_04693.ts
#EXTINF:4.06667,
https://xumo-xumoent-ch827-uj0ch.fast.nbcuni.com/live/master_6/00350/master_6_04694.ts
#EXT-X-DISCONTINUITY
#EXT-X-ASSET:CAID=0x544D4E6F74696369617341686F72615F
#EXTINF:2.0,
../../../../segment/0123456789abcdef0123456789abcdef01234567/prod_default_xumo-nbcu-linear/00000000-0000-4000-8000-000000000001/5/3504695
#EXTINF:2.0,
../../../../segment/0123456789abcdef0123456789abcdef01234567/prod_default_xumo-nbcu-linear/00000000-0000-4000-8000-000000000001/5/3504696
"""
        val xumoBase =
            URI(
                "https://d1example.cloudfront.net/v1/manifest/0123456789abcdef0123456789abcdef01234567/prod_default_xumo-nbcu-linear/00000000-0000-4000-8000-000000000001/5.m3u8",
            )
        assertEquals(listOf(false, false, true, true), ads(xumo, xumoBase))
        val plex = """#EXTM3U
#EXT-X-MEDIA-SEQUENCE:419891
#EXTINF:5.27193,
https://linear-850.frequency.stream/mt/plex/850/hls/master/playlist_384x216_154127__419891.ts
#EXTINF:2.002,
https://epg.provider.plex.tv/library/parts/6a1610be-66635fc0/segment?x-plex-client-identifier=x
#EXT-X-DISCONTINUITY
#EXTINF:2.002,
https://0123456789abcdef0123456789abcdef.mediatailor.us-west-2.amazonaws.com/v1/segment/0123456789abcdef0123456789abcdef01234567/LINEAR-850-CWFOREVER-PLEX_US/00000000-0000-4000-8000-000000000002/4/419893
#EXTINF:2.002,
https://epg.provider.plex.tv/library/parts/6a1610be-66635fc0/segment?x-plex-client-identifier=x
#EXTINF:2.002,
https://0123456789abcdef0123456789abcdef.mediatailor.us-west-2.amazonaws.com/v1/segment/0123456789abcdef0123456789abcdef01234567/LINEAR-850-CWFOREVER-PLEX_US/00000000-0000-4000-8000-000000000002/4/419895
"""
        // Plex's own part segment between two stitched ones is part of the break.
        assertEquals(
            listOf(false, false, true, true, true),
            ads(plex, URI("https://epg.provider.plex.tv/library/parts/6a1610be-66635fc0/index.m3u8")),
        )
    }

    @Test fun `Roku's segments from its ad servers are ads and its live origin is not`() {
        val roku = """#EXTM3U
#EXT-X-VERSION:6
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:68200
#EXT-X-KEY:METHOD=AES-128,URI="https://gcp-live-5014-013.delivery.roku.com/00000000-0000-4000-8000-000000000003/key"
#EXTINF:2.0,
https://gcp-live-5014-013.delivery.roku.com/00000000-0000-4000-8000-000000000003/t2-origin/out/v1/live_216_low/1/live_216_low_68200.ts
#EXT-X-DISCONTINUITY
#EXT-X-KEY:METHOD=NONE
#EXTINF:2.0,
https://osm-ads-gcp.delivery.roku.com/00000000-0000-4000-8000-000000000004/hls/transcode_OVP_SSAI_MANAGER_20251030T070143_segment_4_0004.ts
#EXTINF:2.0,
https://osm-ads.delivery.roku.com/00000000-0000-4000-8000-000000000005/hls/transcode_OVP_SSAI_MANAGER_20251030T070143_segment_4_0001.ts
#EXTINF:2.0,
https://osm-use2.sr.roku.com/osbt/v1/bas/use2/1/2/3/4
#EXT-X-DISCONTINUITY
#EXTINF:2.0,
https://gcp-live-5014-013.delivery.roku.com/00000000-0000-4000-8000-000000000003/t2-origin/out/v1/live_216_low/1/live_216_low_68204.ts
#EXTINF:2.0,
https://example.com/osbt/v1/bas/use2/1/2/3/5
"""
        val rokuBase =
            URI(
                "https://gcp-live-5014-013.delivery.roku.com/00000000-0000-4000-8000-000000000003/t2-origin/out/v1/live_216_low.m3u8",
            )
        assertEquals(listOf(false, true, true, true, false, false), ads(roku, rokuBase))
    }

    @Test fun `Amagi's beacon segments marked M are ads and those marked C are not`() {
        fun beacon(type: String, n: Int) =
            "https://amg00000-example-plex-us-0000.playouts.now.amagi.tv/ts-us-w1-n2/beacon/amg00000-example/0123456789abcdef$n?bcn=0&dur=6.000000&media_type=$type&seen-ad=1&seg_id=$n"
        val whale =
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:40\n" +
                listOf("C", "M", "M", "C").withIndex().joinToString("") { (n, type) ->
                    "#EXTINF:6.0,\n${beacon(type, n)}\n"
                }
        val base =
            URI(
                "https://amg00000-example-plex-us-0000.playouts.now.amagi.tv/ts-us-w1-n2/playlist/amg00000-example/playlist.m3u8",
            )
        assertEquals(listOf(false, true, true, false), ads(whale, base))
        // Plex puts one of its own parts between Amagi's segments; one between two ads belongs to the break.
        val part = "https://epg.provider.plex.tv/library/parts/0123-4567/segment"
        val plex =
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n" +
                listOf(beacon("C", 1), part, beacon("M", 2), part, beacon("M", 3), part, beacon("C", 4))
                    .joinToString("") { "#EXTINF:2.0,\n$it\n" }
        assertEquals(
            listOf(false, false, true, true, true, false, false),
            ads(plex, URI("https://epg.provider.plex.tv/library/parts/0123-4567/index.m3u8")),
        )
        // Once segments are fetched, a break keeps a beacon on every third segment and serves the rest plainly from the
        // playout; a plain segment is what the beacon before it was, and one before any beacon is not marked.
        fun plain(n: Int) = "https://amg00000-example-plex-us-0000.playouts.now.amagi.tv/playlist$n" + "_1.ts"
        val fetched =
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:50\n" +
                listOf(
                    plain(
                        1,
                    ),
                    beacon(
                        "C",
                        2,
                    ),
                    plain(
                        3,
                    ),
                    beacon("M", 4), plain(5), plain(6), beacon("M", 7), plain(8), plain(9), beacon("C", 10), plain(11),
                )
                    .joinToString("") { "#EXTINF:6.0,\n$it\n" }
        assertEquals(listOf(false, false, false, true, true, true, true, true, true, false, false), ads(fetched, base))
        // The same query on another host means nothing.
        assertEquals(listOf(false), ads("#EXTM3U\n#EXTINF:6.0,\nhttps://example.com/beacon/x?media_type=M\n", base))
    }

    @Test fun `fuelmedia segments whose token flags an ad break are ads`() {
        fun token(type: String, adBreak: Boolean): String {
            val json = """[{"SessionId":"x","SegTag":{"ContentType":"$type","IsAd":false,"IsAdFiller":$adBreak,""" +
                """"IsAdBreak":$adBreak}}]"""
            val gzip = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(json.toByteArray()) }
            }.toByteArray()
            return Base64.getUrlEncoder().withoutPadding().encodeToString(gzip)
        }
        val host = "https://streamingengineservice-prod01-v1.fuelmedia.io/play/seg/6S412K288AWS"
        val stirr = """#EXTM3U
#EXT-X-MEDIA-SEQUENCE:252570
#EXTINF:6.0,
https://kotv.fuelmedia.io/sef/c_00000000-0000-4000-8000-000000000006_v_6S412K288AWS_s_00019.ts
#EXTINF:6.0,
$host/${token("live", false)}.ts
#EXT-X-DISCONTINUITY
#EXTINF:6.0,
$host/${token("adFiller", true)}.ts
#EXTINF:6.0,
$host/${token("adFiller", true)}.ts
#EXTINF:6.0,
$host/not-a-token.ts
"""
        assertEquals(
            listOf(false, false, true, true, false),
            ads(stirr, URI("https://kotv.fuelmedia.io/play/6S412K288AWS.m3u8")),
        )
    }

    @Test fun `Ottera's ad host is an ad and a Plex part between two of its segments joins the break`() {
        fun ad(n: Int) =
            "https://stream.ads.ottera.tv/mf2/640x360_800000?u=https%3A%2F%2Fexample.cloudfront.net%2Fcreative%2Fprog_index_11-$n.ts&a=0&s=$n&d=120"
        val own = "https://bas-example.otteravision.com/bas/example/example_360/00043/example_360_08615.ts"
        val part = "https://epg.provider.plex.tv/library/parts/0123-4567/segment"
        val plex = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:9\n" + listOf(part, own, part, ad(1), part, ad(2), part, own)
            .joinToString("") { "#EXTINF:2.0,\n$it\n" }
        assertEquals(
            listOf(false, false, false, true, true, true, false, false),
            ads(plex, URI("https://epg.provider.plex.tv/library/parts/0123-4567/index.m3u8")),
        )
    }

    @Test fun `Google's ad pods are ads and the publisher's segments are not`() {
        val dai = """#EXTM3U
#EXT-X-MEDIA-SEQUENCE:500
#EXTINF:6.0,
https://example.airspace-cdn.cbsivideo.com/live/seg_100.ts
#EXT-X-DISCONTINUITY
#EXTINF:6.0,
https://dai.google.com/linear/pods/v1/p/abcdef/stream0:MRN2/12345/slate/0/0123abcd/1.ts
#EXTINF:6.0,
https://dai.google.com/linear/pods/v1/p/abcdef/stream0:MRN2/12345/slate/0/0123abcd/2.ts
#EXT-X-DISCONTINUITY
#EXTINF:6.0,
https://example.airspace-cdn.cbsivideo.com/live/seg_103.ts
#EXTINF:6.0,
https://dai.google.com/linear/hls/event/abcdef/master.m3u8
"""
        assertEquals(
            listOf(false, true, true, false, false),
            ads(dai, URI("https://dai.google.com/linear/hls/pa/event/abcdef/stream/x/hls/0.m3u8")),
        )
    }

    @Test fun `Wurl's segments titled tag=ad are ads and its overlay cue is not a break`() {
        val wurl = """#EXTM3U
#EXT-X-MEDIA-SEQUENCE:100
#EXTINF:6.006,epid=1:epdur=1800100:ct=C:tag=content:aid=53768162
https://connection3-ent-us.plex.wurl.tv/102/412715/a.ts
#EXT-X-TRANSMIT-CUE-OUT:AdFormat=pip,MaxDuration=53.00,offset=0.41
#EXTINF:6.006,epid=1:epdur=1800100:ct=C:tag=content:aid=53768162
https://connection3-ent-us.plex.wurl.tv/102/412715/b.ts
#EXT-X-DISCONTINUITY
#EXTINF:6.006,epid=1:epdur=1800100:ct=M:tag=ad:aid=163876571:td=49632
https://connection3-ent-us.plex.wurl.tv/163876571/hls-v2/c.ts
#EXTINF:6.006,epid=1:epdur=1800100:ct=M:tag=ad:aid=163876571:td=49632
https://connection3-ent-us.plex.wurl.tv/163876571/hls-v2/d.ts
#EXTINF:6.006,epid=1:ct=C:tag=adventure-promo
https://connection3-ent-us.plex.wurl.tv/102/412715/e.ts
"""
        assertEquals(
            listOf(false, false, true, true, false),
            ads(wurl, URI("https://connection3-ent-us.plex.wurl.tv/102/playlist.m3u8")),
        )
    }

    @Test fun `a cue-in is noted on the segment after it`() {
        val text = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:6,\na.ts\n#EXT-X-CUE-IN\n#EXTINF:6,\nb.ts\n"
        val parsed = assertNotNull(AdBreaks.parse(text, tubiBase))
        assertEquals(listOf(false, true), parsed.segments.map { it.cueIn })
        assertFalse(parsed.cueOpenAtEnd)
    }
}
