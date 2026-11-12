package dev.jamesrich.sofaking

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WebProfileTest {
    /** A document whose `<video>` answers [answer] for the types holding [codec] and nothing for the rest. */
    @Suppress("UNUSED_PARAMETER") // The JavaScript reads them.
    private fun document(codec: String, answer: String) {
        js(
            """globalThis.document = { asked: [], createElement: function () {
                return { canPlayType: function (type) {
                    globalThis.document.asked.push(type);
                    return type.indexOf(codec) >= 0 ? answer : '';
                } };
            } }""",
        )
    }

    private fun asked(): Int = js("globalThis.document.asked.length")

    @AfterTest
    fun tearDown() {
        js("delete globalThis.document")
    }

    @Test
    fun `with no document to ask the profile is the one every browser plays`() {
        assertEquals(emptyList(), browserDecoded())
        assertEquals(webBaseProfile, webProfile)
        assertEquals(listOf("h264", "vp8", "vp9"), webProfile.videoCodecs)
    }

    @Test
    fun `a browser that probably plays AV1 in MP4 and WebM gets it`() {
        document("av01", "probably")
        assertEquals(listOf("av1"), browserDecoded())
        assertEquals(3, asked())
        assertEquals(listOf("h264", "vp8", "vp9", "av1"), webBaseProfile.alsoDecoding(browserDecoded()).videoCodecs)
    }

    @Test
    fun `a maybe, or a yes for only some of the types, is a no`() {
        document("av01", "maybe")
        assertEquals(emptyList(), browserDecoded())
        document("video/mp4", "probably")
        assertEquals(emptyList(), browserDecoded())
    }

    @Test
    fun `a browser that throws when asked plays what every browser plays`() {
        js("globalThis.document = { createElement: function () { throw new Error('blocked'); } }")
        assertEquals(emptyList(), browserDecoded())
    }
}
