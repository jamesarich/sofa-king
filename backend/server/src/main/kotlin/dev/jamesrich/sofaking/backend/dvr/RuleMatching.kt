package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.contract.RuleMatch
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode

/** The most terms or categories one rule holds, and the longest each may be. */
private const val TERMS_LIMIT = 20
private const val TERM_LENGTH = 100

/** The category a team's rule needs when it names none. */
private const val SPORTS = "Sports"

/**
 * Whether one rule takes a program: a title rule by its title, ignoring case and outer spaces; a team or keyword
 * rule by its terms as whole words, in the program's categories. Built once per rule, as it compiles the terms.
 */
internal class RuleMatcher(rule: StoredRule) {
    private val kind = rule.match.kind
    private val title = rule.title.trim()
    private val terms = rule.match.terms.map(::wordPattern)
    private val categories = rule.match.categories.map(::wordPattern)

    fun takes(program: GuideProgram): Boolean = when (kind) {
        Wire.RuleMatchKind.TITLE -> program.title.trim().equals(title, ignoreCase = true)

        Wire.RuleMatchKind.TEAM -> inCategories(program) && named(program.title, program.subTitle)

        Wire.RuleMatchKind.KEYWORD -> inCategories(program) &&
            named(program.title, program.subTitle, program.description)

        else -> false
    }

    private fun named(vararg texts: String?): Boolean =
        terms.any { term -> texts.any { it != null && term.containsMatchIn(it) } }

    /** No categories takes every program; otherwise one of the program's categories holds one as a word. */
    private fun inCategories(program: GuideProgram): Boolean =
        categories.isEmpty() || categories.any { category -> program.categories.any { category.containsMatchIn(it) } }
}

/** [text] as a whole word or phrase, ignoring case: not inside a longer word. */
private fun wordPattern(text: String): Regex =
    Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(text.trim()) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

/**
 * The match a new rule titled [title] stores for [requested]: none or `title` matches the title; a team's rule
 * without terms takes [title] as its team and `Sports` as its category; a keyword rule needs terms. Terms and
 * categories are trimmed and listed once each.
 */
internal fun ruleMatch(requested: RuleMatch?, title: String): RuleMatch {
    val kind = requested?.kind ?: Wire.RuleMatchKind.TITLE
    val terms = cleaned(requested?.terms.orEmpty(), "terms")
    val categories = cleaned(requested?.categories.orEmpty(), "categories")
    return when (kind) {
        Wire.RuleMatchKind.TITLE -> RuleMatch()

        Wire.RuleMatchKind.TEAM -> RuleMatch(
            kind,
            terms.ifEmpty {
                listOf(title)
            },
            categories.ifEmpty { listOf(SPORTS) },
        )

        Wire.RuleMatchKind.KEYWORD -> if (terms.isEmpty()) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "A keyword rule needs at least one term")
        } else {
            RuleMatch(kind, terms, categories)
        }

        else -> throw ApiFailure(
            HttpStatusCode.BadRequest,
            "INVALID_REQUEST",
            "match.kind must be title, team or keyword",
        )
    }
}

private fun cleaned(values: List<String>, field: String): List<String> {
    val kept = values.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    if (kept.size > TERMS_LIMIT || kept.any { it.length > TERM_LENGTH }) {
        throw ApiFailure(
            HttpStatusCode.BadRequest,
            "INVALID_REQUEST",
            "match.$field takes up to $TERMS_LIMIT entries of up to $TERM_LENGTH characters",
        )
    }
    return kept
}
