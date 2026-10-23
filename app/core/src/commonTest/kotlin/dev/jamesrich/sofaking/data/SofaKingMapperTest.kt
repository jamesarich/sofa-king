package dev.jamesrich.sofaking.data

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.time.Instant

class SofaKingMapperTest {
    private fun channel(
        id: String,
        number: String,
        name: String = "Channel $id",
        logo: String? = null,
        tags: List<String> = emptyList(),
        eligible: Boolean = true,
    ) = CatalogChannelDto(id, number, name, logo, tags, playbackEligible = eligible)

    private fun catalog(vararg channels: CatalogChannelDto) = CatalogDto(channels = channels.toList())

    private fun guide(vararg channels: ChannelGuideDto) = GuideWindowDto("r1", channels = channels.toList())

    private fun channelGuide(id: String, vararg programs: ProgramDto) = ChannelGuideDto(id, programs = programs.toList())

    private fun program(
        startsAt: String,
        endsAt: String,
        title: String,
        description: String? = null,
        subTitle: String? = null,
        episode: String? = null,
        isNew: Boolean = false,
        rating: String? = null,
        icon: String? = null,
        categories: List<String> = emptyList(),
    ) = ProgramDto(
        startsAt = startsAt, endsAt = endsAt, title = title, description = description,
        subTitle = subTitle, episode = episode, isNew = isNew, rating = rating, icon = icon, categories = categories,
    )

    @Test
    fun `playable channels map to the app model sorted by number`() {
        val catalog = catalog(
            channel("b", "12", "Twelve", tags = listOf("News", "HD")),
            channel("a", " 5.1 ", "Five", logo = "http://logo/5.png"),
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
    fun `every tag is a group trimmed and once each in tag order`() {
        val channels = SofaKingMapper.channels(
            catalog(channel("a", "1", tags = listOf(" Local ", "", "News", "Local"))),
        )

        assertEquals(listOf("Local", "News"), channels.single().groups)
    }

    @Test
    fun `channels the server cannot play or number are left out`() {
        val catalog = catalog(
            channel("x", "7", eligible = false),
            channel("y", "abc"),
            channel("z", "", logo = ""),
            channel("ok", "8", logo = ""),
        )

        val channels = SofaKingMapper.channels(catalog)

        assertEquals(listOf("ok"), channels.map { it.guideId })
        assertNull(channels.single().logo, "an empty logo reads as none")
    }

    @Test
    fun `blank tags are skipped and a channel left with none is filed under Other`() {
        val channels = SofaKingMapper.channels(
            catalog(channel("a", "1", tags = listOf(" ", "Sports")), channel("b", "2", tags = listOf(" "))),
        )

        assertEquals(listOf(listOf("Sports"), listOf("Other")), channels.map { it.groups })
    }

    @Test
    fun `programs are grouped by channel sorted by start and read their description`() {
        val window = guide(
            channelGuide(
                "a",
                program("2026-10-10T11:00:00Z", "2026-10-10T12:00:00Z", "Later", description = "Second"),
                program("2026-10-10T10:00:00Z", "2026-10-10T11:00:00Z", "Earlier", description = " "),
            ),
            channelGuide("b"),
        )

        val guide = SofaKingMapper.programs(window)

        assertEquals(setOf("a"), guide.keys)
        assertEquals(
            listOf(
                Program("a", Instant.parse("2026-10-10T10:00:00Z"), Instant.parse("2026-10-10T11:00:00Z"), "Earlier", null),
                Program("a", Instant.parse("2026-10-10T11:00:00Z"), Instant.parse("2026-10-10T12:00:00Z"), "Later", "Second"),
            ),
            guide["a"],
        )
    }

    @Test
    fun `programs with unreadable times or no duration are dropped`() {
        val window = guide(
            channelGuide(
                "a",
                program("not a time", "2026-10-10T12:00:00Z", "Bad start"),
                program("2026-10-10T11:00:00Z", "2026-10-10T11:00:00Z", "Zero length"),
                program("2026-10-10T11:00:00Z", "2026-10-10T10:00:00Z", "Backwards"),
                program("2026-10-10T11:00:00Z", "2026-10-10T11:30:00Z", "Kept"),
            ),
            channelGuide("b", program("2026-10-10T11:00:00Z", "2026-10-10T11:00:00Z", "Only bad")),
        )

        val guide = SofaKingMapper.programs(window)

        assertEquals(listOf("Kept"), guide["a"]?.map { it.title })
        assertNull(guide["b"], "a channel with nothing valid has no entry")
    }

    @Test
    fun `program metadata is read and blank values read as none`() {
        val window = guide(
            channelGuide(
                "a",
                program(
                    "2026-10-10T10:00:00Z", "2026-10-10T11:00:00Z", "Show",
                    subTitle = " The Pilot ", episode = "S1 E1", isNew = true, rating = "TV-PG", icon = "https://img/x.jpg",
                    categories = listOf("Feature Film", "Drama", "Children"),
                ),
                program("2026-10-10T11:00:00Z", "2026-10-10T12:00:00Z", "Plain", subTitle = "", episode = " ", rating = ""),
            ),
        )

        val (show, plain) = SofaKingMapper.programs(window).getValue("a")

        assertEquals(listOf("The Pilot", "S1 E1", "TV-PG", "https://img/x.jpg"), listOf(show.subTitle, show.episode, show.rating, show.icon))
        assertEquals(true, show.isNew)
        assertEquals(setOf(ProgramKind.MOVIE, ProgramKind.KIDS), show.kinds)
        assertEquals(emptySet(), plain.kinds)
        assertEquals(listOf(null, null, null, null), listOf(plain.subTitle, plain.episode, plain.rating, plain.icon))
        assertEquals(false, plain.isNew)
    }

    @Test
    fun `guide status maps from the wire and an unknown one reads as available`() {
        val statuses = listOf("unassigned", "empty", "available", "stale", "future")
        val catalog = CatalogDto(channels = statuses.mapIndexed { i, status -> channel("$i", "${i + 1}").copy(guideStatus = status) })

        assertEquals(
            listOf(
                GuideStatus.UNASSIGNED, GuideStatus.EMPTY, GuideStatus.AVAILABLE,
                GuideStatus.STALE, GuideStatus.AVAILABLE,
            ),
            SofaKingMapper.channels(catalog).map { it.guideStatus },
        )
    }

    @Test
    fun `logos go through the resolver and one it rejects reads as none`() {
        val catalog = catalog(channel("a", "1", logo = "http://x/logos/k"), channel("b", "2", logo = "bad"))

        val channels = SofaKingMapper.channels(catalog) { raw -> raw.takeIf { "logos" in it }?.replace("http://x", "https://y") }

        assertEquals(listOf("https://y/logos/k", null), channels.map { it.logo })
    }
}
