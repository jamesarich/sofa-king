package dev.jamesrich.sofaking.visualizer.eel

/** The shape of an equation. */
internal sealed interface Node {
    data class Num(val value: Double) : Node

    data class Var(val name: String) : Node

    /** `megabuf[index]` or `gmegabuf[index]`. */
    data class Index(val buffer: String, val index: Node) : Node

    data class Call(val name: String, val args: List<Node>) : Node

    data class Unary(val op: String, val operand: Node) : Node

    data class Binary(val op: String, val left: Node, val right: Node) : Node

    /** [op] is `=` or a compound such as `+=`. */
    data class Assign(val target: Node, val op: String, val value: Node) : Node

    data class Seq(val items: List<Node>) : Node
}

/**
 * Parses the equations of a MilkDrop preset (ns-eel and the common part of EEL2): statements ended by `;`, the
 * operators `+ - * / % ^ & | ! == != < > <= >= && ||` and the assignments `= += -= *= /= %= |= &=`, function calls, and
 * `buffer[index]`.
 */
internal class Parser(private val tokens: List<Token>) {
    private var at = 0

    private val peek: Token get() = tokens[at]

    private fun isSym(text: String) = (peek as? Token.Sym)?.text == text

    private fun accept(text: String): Boolean = isSym(text).also { if (it) at++ }

    private fun expect(text: String) {
        if (!accept(text)) throw EelException("expected '$text' but found $peek")
    }

    /** A whole program: statements to the end of the text. */
    fun program(): Node {
        val node = sequence()
        if (peek != Token.End) throw EelException("unexpected $peek")
        return node
    }

    /** Statements separated by `;`, up to a `,`, `)`, `]` or the end. */
    private fun sequence(): Node {
        val items = ArrayList<Node>()
        while (!atSequenceEnd) {
            if (accept(";")) continue
            items += assignment()
            if (!accept(";") && !atSequenceEnd) throw EelException("expected ';' but found $peek")
        }
        return if (items.size == 1) items[0] else Node.Seq(items)
    }

    private val atSequenceEnd: Boolean get() = peek == Token.End || isSym(",") || isSym(")") || isSym("]")

    private fun assignment(): Node {
        val left = binary(0)
        val op = (peek as? Token.Sym)?.text
        if (op != null && op in ASSIGNMENTS) {
            if (left !is Node.Var && left !is Node.Index) throw EelException("cannot assign to that")
            at++
            return Node.Assign(left, op, assignment())
        }
        return left
    }

    /** One level of the left-associative binary operators, loosest first; below the last comes [unary]. */
    private fun binary(level: Int): Node {
        if (level == LEVELS.size) return unary()
        var left = binary(level + 1)
        while (true) {
            val op = (peek as? Token.Sym)?.text?.takeIf { it in LEVELS[level] } ?: return left
            at++
            left = Node.Binary(op, left, binary(level + 1))
        }
    }

    private fun unary(): Node {
        val op = (peek as? Token.Sym)?.text
        if (op != null && op in PREFIXES) {
            at++
            return Node.Unary(op, unary())
        }
        val base = primary()
        return if (accept("^")) Node.Binary("^", base, unary()) else base
    }

    private fun primary(): Node = when (val token = tokens[at++]) {
        is Token.Num -> Node.Num(token.value)

        is Token.Sym -> {
            if (token.text != "(") throw EelException("unexpected '${token.text}'")
            sequence().also { expect(")") }
        }

        is Token.Name -> named(token.text)

        Token.End -> throw EelException("the equation ends too soon")
    }

    private fun named(name: String): Node {
        if (accept("(")) {
            val args = ArrayList<Node>()
            if (!isSym(")")) {
                do {
                    args += sequence()
                } while (accept(","))
            }
            expect(")")
            return Node.Call(name, args)
        }
        if (accept("[")) {
            val index = sequence()
            expect("]")
            return Node.Index(name, index)
        }
        return Node.Var(name)
    }

    private companion object {
        val ASSIGNMENTS = setOf("=", "+=", "-=", "*=", "/=", "%=", "|=", "&=")
        val PREFIXES = setOf("-", "+", "!", "~")
        val LEVELS = listOf(
            setOf("||"),
            setOf("&&"),
            setOf("|"),
            setOf("&"),
            setOf("==", "!="),
            setOf("<", ">", "<=", ">="),
            setOf("+", "-"),
            setOf("*", "/", "%"),
        )
    }
}

internal fun parse(source: String): Node = Parser(tokenize(source)).program()
