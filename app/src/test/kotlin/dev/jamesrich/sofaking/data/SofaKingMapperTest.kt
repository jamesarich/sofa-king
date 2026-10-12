package dev.jamesrich.sofaking.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SofaKingMapperTest {
    private fun channel(
        id: String,
        number: String,
        name: String = "Channel $id",
        logo: String? = null,
        tags: List<String> = emptyList(),
        eligible: Boolean = true,
    ) = CatalogChannelDto(id, number, name, logo, tags, eligible)

    @Test
    fun `playable channels map to the app model sorted by number`() {
        val catalog = CatalogDto(
            "r1",
            listOf(
                channel("b", "12", "Twelve", tags = listOf("News", "HD")),
                channel("a", " 5.1 ", "Five", logo = "http://logo/5.png"),
            ),
        )

        val channels = SofaKingMapper.channels(catalog)

        assertEquals(
            listOf(
                Channel("a", 5.1, "Five", "http://logo/5.png", listOf("Other")),
                Channel("b", 12.0, "Twelve", null, listOf("News", "HD")),
            ),
            channels,
        )
    }

    @Test
    fun `every tag is a group, trimmed and once each, in tag order`() {
        val channels = SofaKingMapper.channels(
            CatalogDto("r1", listOf(channel("a", "1", tags = listOf(" Local ", "", "News", "Local")))),
        )

        assertEquals(listOf("Local", "News"), channels.single().groups)
    }

    @Test
    fun `channels the server cannot play or number are left out`() {
        val catalog = CatalogDto(
            "r1",
            listOf(
                channel("x", "7", eligible = false),
                channel("y", "abc"),
                channel("z", "", logo = ""),
                channel("ok", "8", logo = ""),
            ),
        )

        val channels = SofaKingMapper.channels(catalog)

        assertEquals(listOf("ok"), channels.map { it.guideId })
        assertNull("an empty logo reads as none", channels.single().logo)
    }

    @Test
    fun `blank tags are skipped and a channel left with none is filed under Other`() {
        val channels = SofaKingMapper.channels(
            CatalogDto("r1", listOf(channel("a", "1", tags = listOf(" ", "Sports")), channel("b", "2", tags = listOf(" ")))),
        )

        assertEquals(listOf(listOf("Sports"), listOf("Other")), channels.map { it.groups })
    }

    @Test
    fun `programmes are grouped by channel sorted by start and read their description`() {
        val window = GuideWindowDto(
            "r1",
            listOf(
                ChannelGuideDto(
                    "a",
                    listOf(
                        ProgrammeDto("a", "2026-10-10T11:00:00Z", "2026-10-10T12:00:00Z", "Later", description = "Second"),
                        ProgrammeDto("a", "2026-10-10T10:00:00Z", "2026-10-10T11:00:00Z", "Earlier", description = " "),
                    ),
                ),
                ChannelGuideDto("b", emptyList()),
            ),
        )

        val guide = SofaKingMapper.programmes(window)

        assertEquals(setOf("a"), guide.keys)
        assertEquals(
            listOf(
                Programme("a", Instant.parse("2026-10-10T10:00:00Z"), Instant.parse("2026-10-10T11:00:00Z"), "Earlier", null, null),
                Programme("a", Instant.parse("2026-10-10T11:00:00Z"), Instant.parse("2026-10-10T12:00:00Z"), "Later", null, "Second"),
            ),
            guide["a"],
        )
    }

    @Test
    fun `programmes with unreadable times or no duration are dropped`() {
        val window = GuideWindowDto(
            "r1",
            listOf(
                ChannelGuideDto(
                    "a",
                    listOf(
                        ProgrammeDto("a", "not a time", "2026-10-10T12:00:00Z", "Bad start"),
                        ProgrammeDto("a", "2026-10-10T11:00:00Z", "2026-10-10T11:00:00Z", "Zero length"),
                        ProgrammeDto("a", "2026-10-10T11:00:00Z", "2026-10-10T10:00:00Z", "Backwards"),
                        ProgrammeDto("a", "2026-10-10T11:00:00Z", "2026-10-10T11:30:00Z", "Kept"),
                    ),
                ),
                ChannelGuideDto("b", listOf(ProgrammeDto("b", "2026-10-10T11:00:00Z", "2026-10-10T11:00:00Z", "Only bad"))),
            ),
        )

        val guide = SofaKingMapper.programmes(window)

        assertEquals(listOf("Kept"), guide["a"]?.map { it.title })
        assertNull("a channel with nothing valid has no entry", guide["b"])
    }
}
