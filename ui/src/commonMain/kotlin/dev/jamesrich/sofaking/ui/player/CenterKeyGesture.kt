package dev.jamesrich.sofaking.ui.player

/** What an OK press means once its timing is known. */
internal enum class CenterPress { NONE, SHORT, LONG }

/**
 * Splits OK into short and long presses. The long press fires once, on the first key-down that reports
 * a long press or a repeat; the key-up that ends it reports nothing.
 */
internal class CenterKeyGesture {
    private var held = false
    private var longFired = false

    fun onKeyDown(isLongPress: Boolean, repeatCount: Int): CenterPress {
        held = true
        if (longFired || !(isLongPress || repeatCount > 0)) return CenterPress.NONE
        longFired = true
        return CenterPress.LONG
    }

    fun onKeyUp(): CenterPress {
        if (!held) return CenterPress.NONE
        val press = if (longFired) CenterPress.NONE else CenterPress.SHORT
        held = false
        longFired = false
        return press
    }
}
