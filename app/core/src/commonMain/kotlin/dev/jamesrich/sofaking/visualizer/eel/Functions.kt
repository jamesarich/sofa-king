// The numbers here are the definitions of ns-eel's functions, which a name per literal would only hide.
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

/** A function of one or two numbers that gives a number. */
internal fun plain(name: String, args: List<Expr>, arity: (Int) -> Unit): Expr? {
    ONE_ARGUMENT[name]?.let { f ->
        arity(1)
        val a = args[0]
        return Expr { f(a.eval()) }
    }
    return TWO_ARGUMENTS[name]?.let { f ->
        arity(2)
        val a = args[0]
        val b = args[1]
        Expr { f(a.eval(), b.eval()) }
    }
}

/** The functions that are not plain numbers in, number out: they run some arguments rarely or often, or read memory. */
internal fun special(name: String, args: List<Expr>, scope: EelScope): Expr? =
    control(name, args) ?: memory(name, args, scope) ?: logic(name, args)

private fun control(name: String, args: List<Expr>): Expr? = when (name) {
    "if" -> {
        val (c, a, b) = args
        Expr { if (truthy(c.eval())) a.eval() else b.eval() }
    }

    "exec2", "exec3" -> Expr {
        var last = 0.0
        for (arg in args) last = arg.eval()
        last
    }

    "loop" -> {
        val (count, body) = args
        Expr {
            var last = 0.0
            repeat(count.eval().toInt().coerceIn(0, MAX_LOOPS)) { last = body.eval() }
            last
        }
    }

    "while" -> {
        val body = args[0]
        Expr {
            var runs = 0
            var again = true
            while (again && runs++ < MAX_LOOPS) again = truthy(body.eval())
            0.0
        }
    }

    else -> null
}

private fun memory(name: String, args: List<Expr>, scope: EelScope): Expr? = when (name) {
    "rand" -> args.single().let { n -> Expr { scope.rand(n.eval()) } }

    "megabuf", "gmegabuf" -> {
        val buffer = bufferOf(name, scope)
        val index = args.single()
        Expr { buffer.getOrElse(index.eval().toInt()) { 0.0 } }
    }

    else -> null
}

private fun logic(name: String, args: List<Expr>): Expr? = when (name) {
    "band" -> args.let { (a, b) -> Expr { flag(truthy(a.eval()) && truthy(b.eval())) } }

    "bor" -> args.let { (a, b) -> Expr { flag(truthy(a.eval()) || truthy(b.eval())) } }

    "sigmoid" -> args.let { (x, c) ->
        Expr { (1 + exp(-x.eval() * c.eval())).let { if (it == 0.0) 0.0 else 1 / it } }
    }

    else -> null
}

internal val SPECIAL_ARITY = mapOf(
    "if" to 3,
    "loop" to 2,
    "while" to 1,
    "rand" to 1,
    "megabuf" to 1,
    "gmegabuf" to 1,
    "band" to 2,
    "bor" to 2,
    "sigmoid" to 2,
)

private val ONE_ARGUMENT: Map<String, (Double) -> Double> = mapOf(
    "sin" to ::sin,
    "cos" to ::cos,
    "tan" to ::tan,
    "asin" to { x -> asin(x.coerceIn(-1.0, 1.0)) },
    "acos" to { x -> acos(x.coerceIn(-1.0, 1.0)) },
    "atan" to ::atan,
    "sqr" to { x -> x * x },
    "sqrt" to { x -> sqrt(abs(x)) },
    "exp" to ::exp,
    "log" to { x -> if (x > 0) ln(x) else 0.0 },
    "log10" to { x -> if (x > 0) log10(x) else 0.0 },
    "abs" to ::abs,
    "sign" to { x -> sign(x) },
    "int" to { x -> if (x < 0) ceil(x) else floor(x) },
    "floor" to ::floor,
    "ceil" to ::ceil,
    "bnot" to { x -> flag(!truthy(x)) },
)

private val TWO_ARGUMENTS: Map<String, (Double, Double) -> Double> = mapOf(
    "pow" to { x, y -> x.pow(y).let { if (it.isNaN()) 0.0 else it } },
    "min" to { x, y -> min(x, y) },
    "max" to { x, y -> max(x, y) },
    "atan2" to ::atan2,
    "above" to { x, y -> flag(x > y) },
    "below" to { x, y -> flag(x < y) },
    "equal" to { x, y -> flag(abs(x - y) < EPSILON) },
)
