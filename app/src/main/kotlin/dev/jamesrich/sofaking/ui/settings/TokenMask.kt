package dev.jamesrich.sofaking.ui.settings

private const val MASK = '•'

/** How many trailing characters of the API token stay readable while the field is not being edited. */
internal const val TOKEN_VISIBLE_CHARS = 4

/**
 * [token] with everything but its last [visible] characters replaced by bullets, one per character so the
 * field keeps its width. A token no longer than [visible] is masked whole, or it would show in full.
 */
internal fun maskToken(token: String, visible: Int = TOKEN_VISIBLE_CHARS): String {
    if (token.length <= visible) return MASK.toString().repeat(token.length)
    return MASK.toString().repeat(token.length - visible) + token.takeLast(visible)
}
