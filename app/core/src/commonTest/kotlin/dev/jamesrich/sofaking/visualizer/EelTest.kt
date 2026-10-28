package dev.jamesrich.sofaking.visualizer

import dev.jamesrich.sofaking.visualizer.eel.EelException
import dev.jamesrich.sofaking.visualizer.eel.EelScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EelTest {
    private fun run(source: String, scope: EelScope = EelScope()): Double = scope.compile(source).eval()

    @Test
    fun `arithmetic follows the usual precedence`() {
        assertEquals(7.0, run("1 + 2 * 3"))
        assertEquals(9.0, run("(1 + 2) * 3"))
        assertEquals(8.0, run("2 ^ 3"))
        assertEquals(-4.0, run("-2 ^ 2 * 1"), "a minus binds looser than a power")
        assertEquals(1.0, run("7 % 3"))
    }

    @Test
    fun `division by zero is zero as in ns-eel`() {
        assertEquals(0.0, run("5 / 0"))
        assertEquals(0.0, run("5 % 0"))
    }

    @Test
    fun `variables keep their values between programs and names ignore case`() {
        val scope = EelScope()
        run("Zoom = 1.5; q1 = zoom * 2", scope)
        assertEquals(1.5, scope["zoom"])
        assertEquals(3.0, scope["Q1"])
        run("zoom += 0.25; q1 *= 2", scope)
        assertEquals(1.75, scope["zoom"])
        assertEquals(6.0, scope["q1"])
    }

    @Test
    fun `comparisons and logic give 1 or 0`() {
        assertEquals(1.0, run("3 > 2 && 2 >= 2"))
        assertEquals(0.0, run("3 < 2 || 1 == 2"))
        assertEquals(1.0, run("!0"))
        assertEquals(1.0, run("above(2, 1) * below(1, 2) * equal(3, 3)"))
    }

    @Test
    fun `if runs only the branch it takes`() {
        val scope = EelScope()
        run("if(1, a = 5, b = 7)", scope)
        assertEquals(5.0, scope["a"])
        assertEquals(0.0, scope["b"])
        assertEquals(2.0, run("if(0, 1, 2)"))
    }

    @Test
    fun `loop and while repeat and buffers hold numbers`() {
        val scope = EelScope()
        run("i = 0; loop(10, megabuf[i] = i * i; i += 1)", scope)
        assertEquals(81.0, run("megabuf[9]", scope))
        assertEquals(10.0, scope["i"])
        run("n = 0; while(n += 1; n < 5)", scope)
        assertEquals(5.0, scope["n"])
    }

    @Test
    fun `functions work`() {
        assertEquals(0.0, run("sin(0)"))
        assertEquals(1.0, run("cos(0)"))
        assertEquals(9.0, run("sqr(3)"))
        assertEquals(3.0, run("int(3.9)"))
        assertEquals(-3.0, run("int(-3.9)"))
        assertEquals(5.0, run("max(2, 5)"))
        assertEquals(0.5, run("sigmoid(0, 1)"))
        assertEquals(kotlin.math.PI, run("\$pi"))
    }

    @Test
    fun `comments are skipped and a bad program is refused when it is compiled`() {
        assertEquals(3.0, run("a = 1; // one\n b = 2; /* two */ a + b"))
        assertFailsWith<EelException> { run("1 +") }
        assertFailsWith<EelException> { run("nosuch(1)") }
        assertFailsWith<EelException> { run("sin(1, 2)") }
        assertFailsWith<EelException> { run("1 = 2") }
    }
}
