package dev.jamesrich.sofaking.backend.library

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SidecarsTest {
    @Test fun `a subtitle file name gives its language and flags`() {
        assertEquals(SubtitleStream(1000, "subrip", "en", null, default = false, forced = false), sidecarStream(1000, "en.srt"))
        assertEquals(SubtitleStream(1001, "subrip", "eng", "SDH", default = true, forced = false), sidecarStream(1001, "English.sdh.default.srt"))
        assertEquals(SubtitleStream(1002, "ass", "pt", null, default = false, forced = true), sidecarStream(1002, "pt-BR.forced.ass"))
        assertEquals(SubtitleStream(1003, "hdmv_pgs_subtitle", null, "Commentary", default = false, forced = false), sidecarStream(1003, "Commentary.sup"))
        assertEquals("dvd_subtitle", sidecarStream(1004, "fre.IDX").codec)
        assertNull(sidecarStream(1005, "srt").language, "a file named only for the video")
    }

    @Test fun `the subtitle files beside a video are found in name order`() {
        val dir = Files.createTempDirectory("sofa-sidecars-")
        try {
            val video = dir.resolve("Talk (2021).mkv")
            for (name in listOf("Talk (2021).mkv", "Talk (2021).en.srt", "Talk (2021).de.sup", "Talk (2021).fr.idx", "Talk (2021).fr.sub", "Talk (2021).nfo", "Talk (2022).en.srt", "Other.en.srt")) {
                Files.writeString(dir.resolve(name), "")
            }
            val found = sidecarSubtitles(video)
            assertEquals(listOf("Talk (2021).de.sup", "Talk (2021).en.srt", "Talk (2021).fr.idx"), found.map { it.file.fileName.toString() })
            assertEquals(listOf(1000, 1001, 1002), found.map { it.stream.index })
            assertEquals(emptyList(), sidecarSubtitles(dir.resolve("missing/x.mkv")))
        } finally { dir.toFile().deleteRecursively() }
    }
}
