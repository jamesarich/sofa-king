// The numbers here are the definitions of ns-eel's functions and limits, which a name per literal would only hide.
@file:Suppress("MagicNumber")

package dev.jamesrich.sofaking.visualizer.eel

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** How close to 0 counts as false, and how close two numbers are to be equal. */
internal const val EPSILON = 1e-5

/** The most times `loop` and `while` run their body. */
internal const val MAX_LOOPS = 8_192

internal fun truthy(x: Double) = abs(x) > EPSILON

internal fun flag(b: Boolean) = if (b) 1.0 else 0.0

internal fun compileNode(node: Node, scope: EelScope): Expr = when (node) {
    is Node.Num -> Expr { node.value }

    is Node.Var -> scope.slot(node.name).let { slot -> Expr { scope.values[slot] } }

    is Node.Index -> {
        val buffer = bufferOf(node.buffer, scope)
        val index = compileNode(node.index, scope)
        Expr { buffer.getOrElse(index.eval().toInt()) { 0.0 } }
    }

    is Node.Seq -> node.items.map { compileNode(it, scope) }.let { items ->
        Expr {
            var last = 0.0
            for (item in items) last = item.eval()
            last
        }
    }

    is Node.Unary -> unary(node.op, compileNode(node.operand, scope))

    is Node.Binary -> binary(node.op, compileNode(node.left, scope), compileNode(node.right, scope))

    is Node.Assign -> assignment(node, scope)

    is Node.Call -> call(node, scope)
}

internal fun bufferOf(name: String, scope: EelScope): DoubleArray = when (name) {
    "megabuf" -> scope.megabuf
    "gmegabuf" -> scope.gmegabuf
    else -> throw EelException("$name is not a buffer")
}

private fun unary(op: String, x: Expr): Expr = when (op) {
    "-" -> Expr { -x.eval() }
    "+" -> x
    "!" -> Expr { flag(!truthy(x.eval())) }
    else -> Expr { x.eval().toLong().inv().toDouble() }
}

private fun binary(op: String, l: Expr, r: Expr): Expr = when (op) {
    "+" -> Expr { l.eval() + r.eval() }
    "-" -> Expr { l.eval() - r.eval() }
    "*" -> Expr { l.eval() * r.eval() }
    "/" -> Expr { r.eval().let { d -> if (d == 0.0) 0.0 else l.eval() / d } }
    "%" -> Expr { r.eval().toLong().let { d -> if (d == 0L) 0.0 else (l.eval().toLong() % d).toDouble() } }
    "^" -> Expr { l.eval().pow(r.eval()).let { if (it.isNaN()) 0.0 else it } }
    "&" -> Expr { (l.eval().toLong() and r.eval().toLong()).toDouble() }
    "|" -> Expr { (l.eval().toLong() or r.eval().toLong()).toDouble() }
    "&&" -> Expr { flag(truthy(l.eval()) && truthy(r.eval())) }
    "||" -> Expr { flag(truthy(l.eval()) || truthy(r.eval())) }
    "==" -> Expr { flag(abs(l.eval() - r.eval()) < EPSILON) }
    "!=" -> Expr { flag(abs(l.eval() - r.eval()) >= EPSILON) }
    "<" -> Expr { flag(l.eval() < r.eval()) }
    ">" -> Expr { flag(l.eval() > r.eval()) }
    "<=" -> Expr { flag(l.eval() <= r.eval()) }
    else -> Expr { flag(l.eval() >= r.eval()) }
}

private fun assignment(node: Node.Assign, scope: EelScope): Expr {
    val value = compileNode(node.value, scope)
    val combine: Expr = when (node.op) {
        "=" -> value

        else -> {
            val current = compileNode(node.target, scope)
            binary(node.op.dropLast(1), current, value)
        }
    }
    return when (val target = node.target) {
        is Node.Var -> scope.slot(target.name).let { slot ->
            Expr { combine.eval().also { scope.values[slot] = it } }
        }

        is Node.Index -> {
            val buffer = bufferOf(target.buffer, scope)
            val index = compileNode(target.index, scope)
            Expr {
                val at = index.eval().toInt()
                combine.eval().also { if (at in buffer.indices) buffer[at] = it }
            }
        }

        else -> throw EelException("cannot assign to that")
    }
}

private fun call(node: Node.Call, scope: EelScope): Expr {
    val args = node.args.map { compileNode(it, scope) }
    fun arity(n: Int) {
        if (args.size != n) throw EelException("${node.name} takes $n arguments")
    }
    SPECIAL_ARITY[node.name]?.let(::arity)
    val compiled = special(node.name, args, scope) ?: plain(node.name, args, ::arity)
    return compiled ?: throw EelException("no function ${node.name}")
}
