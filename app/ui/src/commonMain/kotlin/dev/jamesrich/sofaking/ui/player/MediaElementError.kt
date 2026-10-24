package dev.jamesrich.sofaking.ui.player

/** `MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED`: the element could not use its source. */
internal const val MEDIA_ERR_SRC_NOT_SUPPORTED = 4

/**
 * The failure a browser video element's [code] means. The element answers a source it cannot use with
 * [MEDIA_ERR_SRC_NOT_SUPPORTED] whether the format is unplayable or the source failed (a relay 410, a playlist that
 * ended empty), so only a type the browser cannot play, [playable] false, is a format error. A playable source that
 * failed before its metadata [loaded] is gone: the screens re-read the session at once
 * instead of waiting out the backoff.
 */
internal fun mediaElementError(code: Int?, playable: Boolean, loaded: Boolean): PlayerError = when {
    code == MEDIA_ERR_SRC_NOT_SUPPORTED && !playable -> PlayerError(PlayerErrorCodes.PARSING_CONTAINER_UNSUPPORTED)
    code == MEDIA_ERR_SRC_NOT_SUPPORTED -> PlayerError(PlayerErrorCodes.NETWORK_CONNECTION_FAILED, streamGone = !loaded)
    else -> PlayerError(PlayerErrorCodes.NETWORK_CONNECTION_FAILED)
}
