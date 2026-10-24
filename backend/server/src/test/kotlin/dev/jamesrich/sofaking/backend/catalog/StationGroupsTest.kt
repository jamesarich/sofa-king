package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.GroupPattern
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyGroups
import dev.jamesrich.sofaking.backend.api.PolicyHealth
import dev.jamesrich.sofaking.backend.api.PolicyNames
import dev.jamesrich.sofaking.backend.api.PolicyProvider
import dev.jamesrich.sofaking.backend.api.PolicyUnmappedCategory
import dev.jamesrich.sofaking.backend.api.StationOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How a station gets its group, and which raw categories nothing maps. */
class StationGroupsTest : StationBuilderCase() {
    @Test fun `a category the policy leaves unmapped takes the shipped map, unless groups builtIn is off`() {
        val catalog =
            listOf(
                candidate("whale", "w9", "Crime Scene", "9", group = "Crime TV"),
                candidate("whale", "w8", "Odd One", "8", group = "Some Odd Bucket"),
            )
        val on = build(policy.copy(groups = policy.groups.copy(builtIn = true)), catalog)
        assertEquals("Crime", on.station("crime scene").group, "the shipped map names Crime TV")
        assertEquals(
            listOf("whale" to "some odd bucket"),
            on.unmappedCategories.map {
                it.provider to it.category
            },
            "only a category neither map knows is reported",
        )
        val off = build(policy, catalog)
        assertEquals("Entertainment", off.station("crime scene").group, "without the shipped map the default applies")
        assertEquals(setOf("crime tv", "some odd bucket"), off.unmappedCategories.mapTo(HashSet()) { it.category })
    }

    private val unmappedPolicy = LineupPolicy(
        names = PolicyNames(version = 1),
        health = PolicyHealth(requireVerified = false),
        providers = listOf(PolicyProvider("whale"), PolicyProvider("samsung")),
        groups = PolicyGroups(
            map = mapOf("news" to "Headlines"),
            patterns = listOf(GroupPattern(name = "tennis", group = "Sports")),
            default = "Entertainment",
            builtIn = false,
        ),
    )

    @Test fun `a raw category nothing maps is counted per provider, and mapped or pattern-matched ones are not`() {
        val result = build(
            unmappedPolicy,
            listOf(
                candidate("whale", "u1", "Alpha One", "1", group = "Lifestyle"),
                candidate("whale", "u2", "Bravo", "2", group = "Lifestyle"),
                candidate("samsung", "u3", "Charlie", "3", group = "Arts"),
                candidate("whale", "u4", "Delta News", "4", group = "News"),
                candidate("whale", "u5", "Tennis Live", "5", group = "Hobbies"),
            ),
        )
        assertEquals(
            listOf(
                PolicyUnmappedCategory("whale", "lifestyle", 2, "Alpha One"),
                PolicyUnmappedCategory("samsung", "arts", 1, "Charlie"),
            ),
            result.unmappedCategories,
        )
        assertTrue("groups: 2 categories have no mapping (3 stations); see unmappedCategories" in result.findings)
    }

    @Test fun `a station with a group override is not counted as unmapped`() {
        val result = build(
            unmappedPolicy.copy(stations = mapOf("charlie" to StationOverride(group = "Arts"))),
            listOf(candidate("samsung", "u3", "Charlie", "3", group = "Arts")),
        )
        assertEquals(emptyList(), result.unmappedCategories)
        assertTrue(result.findings.none { it.startsWith("groups:") }, "no summary finding when nothing is unmapped")
    }

    private val votingPolicy = LineupPolicy(
        names = PolicyNames(version = 2),
        health = PolicyHealth(requireVerified = false),
        providers = listOf(
            PolicyProvider("whale"),
            PolicyProvider("samsung"),
            PolicyProvider("vizio"),
            PolicyProvider("lg"),
        ),
        groups = PolicyGroups(
            map = mapOf(
                "movies and series" to "Movies", "reality" to "Lifestyle", "daytime" to "Lifestyle",
                "comedy" to "Comedy",
                "tv" to "Entertainment", "news" to "News", "latino" to "En Español", "vizio:sitcoms" to "Comedy",
                "sitcoms" to "Entertainment",
            ),
            weak = listOf("movies and series", "tv", "latino"),
            patterns = listOf(
                GroupPattern(name = "\\bnoticias\\b", group = "News"),
                GroupPattern(rawGroup = "^game", group = "Game Shows"),
            ),
            default = "Entertainment",
        ),
    )

    @Test fun `the group is the one most of a station's providers vote for, not the leading channel's`() {
        val result = build(
            votingPolicy,
            listOf(
                candidate("whale", "w1", "Nosey", "1", group = "Movies and Series"),
                candidate("samsung", "s1", "Nosey", "2", group = "Reality"),
                candidate("vizio", "v1", "Nosey", "3", group = "Daytime"),
                candidate("lg", "l1", "Nosey", "4", group = "Game Time"),
            ),
        )
        assertEquals(
            "Lifestyle",
            result.station("nosey").group,
            "two Lifestyle votes beat one Game Shows vote, and the weak lead abstains",
        )
    }

    @Test fun `a provider votes once per group, and a tie goes to the earliest vote`() {
        val result = build(
            votingPolicy,
            listOf(
                candidate("samsung", "s1", "Conners", "1", group = "Reality"),
                candidate("samsung", "s2", "Conners", "2", group = "Reality"),
                candidate("vizio", "v1", "Conners", "3", group = "Comedy"),
            ),
        )
        assertEquals(
            "Lifestyle",
            result.station("conners").group,
            "two samsung channels are one vote, and samsung comes first",
        )
    }

    @Test fun `weak categories vote only when nothing else does`() {
        val result = build(
            votingPolicy,
            listOf(
                candidate("whale", "w1", "Drama Box", "1", group = "Movies and Series"),
                candidate("vizio", "v1", "Drama Box", "2", group = "TV"),
                candidate("lg", "l1", "Drama Box", "3", group = "Movies and Series"),
                candidate("whale", "w2", "Plain", "4", group = "Movies and Series"),
                candidate("vizio", "v2", "Plain", "5", group = "News"),
                candidate("samsung", "s1", "Nothing Known", "6", group = "Latino"),
            ),
        )
        assertEquals("Movies", result.station("dramabox").group, "only weak votes: their plurality decides")
        assertEquals("News", result.station("plain").group, "one strong vote beats a weak lead")
        assertEquals("En Español", result.station("nothingknown").group)
        assertEquals(emptyList(), result.unmappedCategories, "weak categories are mapped or abstain, never unmapped")
    }

    @Test fun `a provider-scoped category wins over the plain one`() {
        val result = build(
            votingPolicy,
            listOf(
                candidate("vizio", "v1", "Hit Sitcoms", "1", group = "Sitcoms"),
                candidate("lg", "l1", "Sitcom Hour", "2", group = "Sitcoms"),
            ),
        )
        assertEquals("Comedy", result.station("hitsitcoms").group)
        assertEquals("Entertainment", result.station("sitcomhour").group)
    }

    @Test fun `a name pattern wins over every vote, and a raw group pattern votes like a map entry`() {
        val result = build(
            votingPolicy,
            listOf(
                candidate("samsung", "s1", "Telemundo Noticias", "1", group = "Reality"),
                candidate("vizio", "v1", "Telemundo Noticias", "2", group = "Reality"),
                candidate("lg", "l1", "Quiz Night", "3", group = "Game Time"),
            ),
        )
        assertEquals("News", result.station("telemundonoticias").group)
        assertEquals("Game Shows", result.station("quiznight").group)
    }

    @Test fun `an entry's group groups the stations it leads and no other`() {
        val regional = votingPolicy.copy(
            providers = votingPolicy.providers + PolicyProvider("samsung-gb", group = "UK"),
        )
        val result = build(
            regional,
            listOf(
                candidate("samsung-gb", "g1", "Pointless", "1", group = "Entertainment"),
                candidate("samsung-gb", "g2", "Wicked Tuna", "2", group = "Reality"),
                candidate("whale", "w1", "Wicked Tuna", "3", group = "Unmapped Bucket"),
            ),
        )
        assertEquals("UK", result.station("pointless").group)
        assertEquals(
            "Lifestyle",
            result.station("wickedtuna").group,
            "whale leads, so the regional entry's group does not apply; its genre still votes",
        )
        assertEquals(
            listOf("whale" to "unmapped bucket"),
            result.unmappedCategories.map {
                it.provider to it.category
            },
            "a station its entry groups lists no unmapped category",
        )
        assertTrue(
            validatePolicy(
                regional.copy(providers = listOf(PolicyProvider("whale", group = " "))),
                setOf("whale"),
            ).contains("providers[0]: group must be 1 to 128 characters"),
        )
    }

    @Test fun `every source's unmapped category is reported, not only the leading one's`() {
        val result = build(
            votingPolicy,
            listOf(
                candidate("whale", "w1", "Bravo", "1", group = "News"),
                candidate("samsung", "s1", "Bravo", "2", group = "Arts"),
                candidate("samsung", "s2", "Charlie", "3", group = "Arts"),
                candidate("samsung", "s3", "Telemundo Noticias", "4", group = "Arts"),
            ),
        )
        assertEquals("News", result.station("bravo").group)
        assertEquals(
            listOf(PolicyUnmappedCategory("samsung", "arts", 2, "Bravo")),
            result.unmappedCategories,
            "a name pattern settles its station",
        )
        assertTrue("groups: 1 categories have no mapping (2 stations); see unmappedCategories" in result.findings)
    }

    @Test fun `weak keys must be lowercase and trimmed`() {
        val errors =
            validatePolicy(
                votingPolicy.copy(groups = PolicyGroups(weak = listOf("TV", " us"))),
                setOf("whale", "samsung", "vizio", "lg"),
            )
        assertEquals(
            listOf(
                "groups.weak: \"TV\" must be lowercase and trimmed",
                "groups.weak: \" us\" must be lowercase and trimmed",
            ),
            errors,
        )
    }
}
