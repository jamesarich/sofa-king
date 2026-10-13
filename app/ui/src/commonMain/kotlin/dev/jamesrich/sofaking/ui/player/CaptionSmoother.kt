package dev.jamesrich.sofaking.ui.player

/** Decides whether a caption text update is shown now or held until its line is finished. */
internal object CaptionSmoother {
    /** Longest a partly typed line may be held before it is shown anyway. */
    const val HOLD_MS = 250L

    enum class Decision { EMIT, HOLD }

    /**
     * Roll-up and paint-on captions grow one character at a time. A growing line is held so it lands
     * whole, but never past [HOLD_MS]. Anything else (a cleared screen, a new line, a replaced line,
     * a finished word) is shown at once.
     */
    fun decide(shownText: String, incomingText: String, nowMs: Long, lastEmitMs: Long): Decision = when {
        shownText.isEmpty() || incomingText.isEmpty() -> Decision.EMIT
        incomingText == shownText -> Decision.EMIT
        !incomingText.startsWith(shownText) -> Decision.EMIT
        lineCount(incomingText) > lineCount(shownText) -> Decision.EMIT
        endsWord(incomingText) -> Decision.EMIT
        nowMs - lastEmitMs >= HOLD_MS -> Decision.EMIT
        else -> Decision.HOLD
    }

    private fun lineCount(text: String): Int = text.count { it == '\n' }

    /** The last character is whitespace or punctuation, so the word is complete. */
    private fun endsWord(text: String): Boolean {
        val last = text.last()
        return last.isWhitespace() || !last.isLetterOrDigit()
    }
}
