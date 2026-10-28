package dev.jamesrich.sofaking.visualizer.eel

/** One piece of an equation's text. */
internal sealed interface Token {
    data class Num(val value: Double) : Token

    data class Name(val text: String) : Token

    data class Sym(val text: String) : Token

    data object End : Token
}

/** The text of an equation does not parse. */
class EelException(message: String) : IllegalArgumentException(message)

private val TWO_CHAR_SYMBOLS = setOf("==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=", "|=", "&=")
private const val ONE_CHAR_SYMBOLS = "+-*/%^()[],;=<>!&|~"
private val CONSTANTS = mapOf("pi" to kotlin.math.PI, "e" to kotlin.math.E, "phi" to 1.618033988749895)

/** Splits [source] into tokens; `//` and slash-star comments are dropped, `$pi`, `$e` and `$phi` become numbers. */
internal fun tokenize(source: String): List<Token> {
    val tokens = ArrayList<Token>()
    var i = 0
    while (i < source.length) {
        val c = source[i]
        i = when {
            c.isWhitespace() -> i + 1
            source.startsWith("//", i) -> source.indexOf('\n', i).let { if (it < 0) source.length else it }
            source.startsWith("/*", i) -> source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
            c.isDigit() || (c == '.' && source.getOrNull(i + 1)?.isDigit() == true) -> number(source, i, tokens)
            c.isLetter() || c == '_' || c == '$' -> name(source, i, tokens)
            else -> symbol(source, i, tokens)
        }
    }
    tokens += Token.End
    return tokens
}

private fun number(source: String, start: Int, tokens: MutableList<Token>): Int {
    if (source.startsWith("0x", start, ignoreCase = true)) {
        var end = start + 2
        while (end < source.length && source[end].isHexDigit()) end++
        tokens += Token.Num(source.substring(start + 2, end).toLong(radix = 16).toDouble())
        return end
    }
    var end = start
    while (end < source.length && (source[end].isDigit() || source[end] == '.')) end++
    end = exponentEnd(source, end)
    val text = source.substring(start, end)
    tokens += Token.Num(text.toDoubleOrNull() ?: throw EelException("bad number $text"))
    return end
}

/** Where a number ending at [end] really ends: after an `e5` or `E-3` that follows it, if there is one. */
private fun exponentEnd(source: String, end: Int): Int {
    if (source.getOrNull(end)?.lowercaseChar() != 'e') return end
    var at = end + 1
    if (source.getOrNull(at) == '+' || source.getOrNull(at) == '-') at++
    if (source.getOrNull(at)?.isDigit() != true) return end
    while (source.getOrNull(at)?.isDigit() == true) at++
    return at
}

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun name(source: String, start: Int, tokens: MutableList<Token>): Int {
    var end = start + 1
    while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '_')) end++
    val text = source.substring(start, end)
    val constant = if (text.startsWith("$")) CONSTANTS[text.substring(1).lowercase()] else null
    tokens += if (constant != null) Token.Num(constant) else Token.Name(text.lowercase())
    return end
}

private fun symbol(source: String, start: Int, tokens: MutableList<Token>): Int {
    val two = source.substring(start, minOf(start + 2, source.length))
    if (two in TWO_CHAR_SYMBOLS) {
        tokens += Token.Sym(two)
        return start + 2
    }
    val one = source[start]
    if (one !in ONE_CHAR_SYMBOLS) throw EelException("unexpected '$one'")
    tokens += Token.Sym(one.toString())
    return start + 1
}
