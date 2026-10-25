package dev.jamesrich.sofaking.data

/** `Bears at Packers`, `Packers vs. Lions`, `Cubs @ Cardinals`: two sides around one separator word. */
private val SIDES = Regex("""^(.+?)\s+(?:at|vs\.?|v\.?|@)\s+(.+)$""", RegexOption.IGNORE_CASE)

/** The longest a side may be, in characters and in words, to read as a team rather than a sentence. */
private const val SIDE_LENGTH = 32
private const val SIDE_WORDS = 5

/**
 * The two teams a sports program names, from its episode title or else its title (after any `League:` heading), as
 * in `NFL Football` with `Bears at Packers`; empty for a program that is not sports or names no two sides.
 */
fun teamSides(program: Program): List<String> {
    if (ProgramKind.SPORTS !in program.kinds) return emptyList()
    for (text in listOfNotNull(program.subTitle, program.title)) {
        val match = SIDES.matchEntire(text.substringAfterLast(':').trim()) ?: continue
        val sides = match.groupValues.drop(1).map { it.trim().trimEnd('.', '!') }
        val short = sides.all { it.isNotEmpty() && it.length <= SIDE_LENGTH && it.split(' ').size <= SIDE_WORDS }
        if (short && !sides[0].equals(sides[1], ignoreCase = true)) {
            return sides
        }
    }
    return emptyList()
}
