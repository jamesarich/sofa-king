package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.CandidateFilter
import dev.jamesrich.sofaking.backend.api.GroupPattern
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyGroups
import dev.jamesrich.sofaking.backend.api.PolicyNames
import dev.jamesrich.sofaking.backend.api.PolicyProvider
import dev.jamesrich.sofaking.backend.api.SourcePin
import dev.jamesrich.sofaking.backend.api.StationOverride
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineupPolicyTest {
    private val providers = setOf("localnow", "whale", "samsung", "vizio", "masqueradarr")

    private fun read(json: String): LoadedPolicy {
        val file = Files.createTempFile("policy-", ".json")
        try {
            Files.writeString(file, json)
            return readPolicy(file)
        } finally {
            Files.delete(file)
        }
    }

    @Test fun `the documented shape parses, and its revision is the file's SHA-256`() {
        val json = """
            {
              "version": 1,
              "names": { "version": 1, "aliases": { "grio": "thegrio", "espn 8": "espn8" } },
              "providers": [
                { "id": "localnow", "include": { "groups": ["My City"] } },
                { "id": "localnow", "include": { "groups": ["More Cities"] }, "enabled": false },
                { "id": "whale" }, { "id": "samsung" },
                { "id": "vizio", "exclude": { "groups": ["Local channels"] } }
              ],
              "groups": { "map": { "crime tv": "Crime" }, "patterns": [ { "name": "sport|espn", "group": "Sports" } ], "default": "Entertainment" },
              "numbering": { "floor": 1000, "keepProviderNumbers": true },
              "health": { "disableAfterHours": 24, "enableOn": "healthy", "keepMissingDays": 7 },
              "maxSourcesPerStation": 16,
              "stations": {
                "nfl network": {
                  "number": "245", "group": "Sports", "required": true,
                  "sources": [ { "provider": "masqueradarr", "tvgId": "NFLNetwork.us", "name": "NFL Network" } ]
                },
                "qvc": { "enabled": false }
              }
            }
        """.trimIndent()
        val loaded = read(json)
        assertEquals(5, loaded.policy.providers.size)
        assertFalse(loaded.policy.providers[1].enabled)
        assertEquals(
            listOf(SourcePin("masqueradarr", "NFLNetwork.us", "NFL Network")),
            loaded.policy.stations.getValue("nfl network").sources,
        )
        assertEquals(hash(json), loaded.revision)
        assertEquals(emptyList(), validatePolicy(loaded.policy, providers))
    }

    @Test fun `unknown keys are rejected`() {
        assertFailsWith<IllegalArgumentException> { read("""{"providers":[{"id":"whale","priority":1}]}""") }
        assertFailsWith<IllegalArgumentException> { read("""{"stations":{"qvc":{"enable":false}}}""") }
    }

    @Test fun `validation reports every problem at once`() {
        val policy = LineupPolicy(
            names = PolicyNames(aliases = mapOf("a" to "b", "b" to "c", "x" to "x", "the grio" to "thegrio")),
            providers = listOf(
                PolicyProvider("whale", include = CandidateFilter(namePatterns = listOf("(unclosed"))),
                PolicyProvider("nowhere"),
            ),
            groups = PolicyGroups(
                patterns = listOf(GroupPattern(group = "Sports"), GroupPattern(rawGroup = "[", group = "News")),
            ),
            maxSourcesPerStation = 65,
            stations = mapOf(
                "one" to
                    StationOverride(
                        number = "5",
                        id = "8a0f6f43-2e3e-4c2e-9c4f-111111111111",
                        sources = listOf(SourcePin("whale", "w1")),
                    ),
                "two" to
                    StationOverride(
                        number = "005",
                        id = "8a0f6f43-2e3e-4c2e-9c4f-111111111111",
                        sources = listOf(SourcePin("whale", "w1")),
                    ),
                "three" to
                    StationOverride(
                        id = "not-a-uuid",
                        gracenoteId = "WABC",
                        sources = listOf(SourcePin("ghost-provider", name = "x"), SourcePin("whale")),
                    ),
                "b" to StationOverride(),
            ),
        )
        val errors = validatePolicy(policy, providers)
        fun has(fragment: String) = assertTrue(errors.any { fragment in it }, "missing \"$fragment\" in $errors")
        has("maxSourcesPerStation must be between 1 and 64")
        has("providers[0].include.namePatterns: invalid pattern")
        has("providers[1]: unknown provider nowhere")
        has("groups.patterns[0]: needs name or rawGroup")
        has("groups.patterns[1].rawGroup: invalid pattern")
        has("\"a\" -> \"b\" chains through another alias")
        has("\"x\" aliases itself")
        has("\"the grio\" is not a normalized name (it normalizes to \"grio\")")
        has("stations[\"b\"]: key is an alias of \"c\"")
        has("number 5 is claimed by one, two")
        has("id 8a0f6f43-2e3e-4c2e-9c4f-111111111111 is claimed by one, two")
        has("stations[\"three\"]: id must be a UUID")
        has("stations[\"three\"]: gracenoteId must be 1 to 10 digits")
        has("pin names unknown provider ghost-provider")
        has("a pin needs tvgId or name")
        has("pin whale/tvg-id:w1 is claimed by one, two")
        assertTrue(
            validatePolicy(policy.copy(maxSourcesPerStation = 0), providers).any {
                "maxSourcesPerStation" in it
            },
        )
    }

    @Test fun `aliases are normalized under the policy's names version, and version 2 is the default`() {
        assertEquals(2, LineupPolicy().names.version)
        val v2 = LineupPolicy(names = PolicyNames(aliases = mapOf("espn 8" to "espn8x")))
        assertTrue(
            validatePolicy(v2, providers).any {
                "\"espn 8\" is not a normalized name (it normalizes to \"espn8\")" in
                    it
            },
        )
        assertEquals(
            emptyList(),
            validatePolicy(v2.copy(names = PolicyNames(version = 1, aliases = mapOf("espn 8" to "espn8x"))), providers),
        )
        assertTrue(
            validatePolicy(LineupPolicy(names = PolicyNames(version = 3)), providers).any {
                "names.version must be one of [1, 2]" in
                    it
            },
        )
    }

    @Test fun `an override id may not take another station's generated id`() {
        val policy =
            LineupPolicy(stations = mapOf("a" to StationOverride(id = stationId("b")), "b" to StationOverride()))
        assertEquals(listOf("id ${stationId("b")} is the generated id of \"b\""), validatePolicy(policy, providers))
    }
}
