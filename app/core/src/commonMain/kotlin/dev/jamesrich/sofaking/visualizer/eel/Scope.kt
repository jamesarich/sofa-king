package dev.jamesrich.sofaking.visualizer.eel

import kotlin.random.Random

/** A compiled equation. */
fun interface Expr {
    fun eval(): Double
}

/**
 * The variables the equations of one preset share, by name (case does not matter), with the two buffers `megabuf` and
 * `gmegabuf` and the random numbers `rand` draws. A variable nothing has set reads 0.
 */
class EelScope(private val random: Random = Random(1)) {
    private val slots = HashMap<String, Int>()
    internal var values = DoubleArray(INITIAL_SLOTS)
        private set

    internal val megabuf = DoubleArray(BUFFER_SIZE)
    internal val gmegabuf = DoubleArray(BUFFER_SIZE)

    internal fun slot(name: String): Int {
        val key = name.lowercase()
        return slots.getOrPut(key) {
            slots.size.also { if (it >= values.size) values = values.copyOf(values.size * 2) }
        }
    }

    operator fun get(name: String): Double = values[slot(name)]

    operator fun set(name: String, value: Double) {
        values[slot(name)] = value
    }

    /** A whole number from 0 up to but not including [n], as a double. */
    internal fun rand(n: Double): Double = random.nextInt(n.toInt().coerceAtLeast(1)).toDouble()

    /** Compiles [source]; the text is checked now, not when it runs. Throws [EelException] when it does not parse. */
    fun compile(source: String): Expr = compileNode(parse(source), this)

    private companion object {
        const val INITIAL_SLOTS = 128
        const val BUFFER_SIZE = 65_536
    }
}
