package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.cant_play
import dev.jamesrich.sofaking.resources.cant_play_retry
import dev.jamesrich.sofaking.resources.cant_reach_stream
import dev.jamesrich.sofaking.resources.no_working_source
import dev.jamesrich.sofaking.resources.no_working_source_hint
import dev.jamesrich.sofaking.resources.player_channel_gone
import dev.jamesrich.sofaking.resources.player_channel_gone_hint
import dev.jamesrich.sofaking.resources.player_no_answer
import dev.jamesrich.sofaking.resources.player_no_answer_hint
import dev.jamesrich.sofaking.resources.player_server_busy
import dev.jamesrich.sofaking.resources.player_server_busy_hint
import dev.jamesrich.sofaking.resources.player_signed_out
import dev.jamesrich.sofaking.resources.player_signed_out_hint
import dev.jamesrich.sofaking.resources.player_slot_taken
import dev.jamesrich.sofaking.resources.stream_refused
import dev.jamesrich.sofaking.resources.stream_unsupported
import dev.jamesrich.sofaking.resources.vod_cant_play
import dev.jamesrich.sofaking.resources.vod_cant_play_retry
import org.jetbrains.compose.resources.StringResource

/** The server's answer when every source of a channel is down or quarantined. */
internal const val SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE"

/** The [failureCode] of a failure that came with no server error code, such as a dropped network at session open. */
internal const val SESSION_UNAVAILABLE = "SESSION_UNAVAILABLE"

/** What a failed playback says: what happened ([reason]) and what to do about it ([hint]). */
internal class FailureCopy(val reason: StringResource, val hint: StringResource)

/**
 * The plain sentences for a playback error: [code] is the ExoPlayer error name or a session code, and [live] tells a
 * channel from a library item or recording, which has no reconnect.
 */
internal fun failureCopy(code: String, live: Boolean = true): FailureCopy {
    val known = when {
        code == Wire.ErrorCode.AUTH_REQUIRED || code == "HTTP_401" || code == "HTTP_403" ->
            FailureCopy(Res.string.player_signed_out, Res.string.player_signed_out_hint)

        code == Wire.ErrorCode.CAPACITY_EXHAUSTED ->
            FailureCopy(Res.string.player_server_busy, Res.string.player_server_busy_hint)

        code == Wire.ErrorCode.SESSION_PREEMPTED ->
            FailureCopy(Res.string.player_slot_taken, Res.string.player_server_busy_hint)

        code == Wire.ErrorCode.CHANNEL_NOT_FOUND ->
            FailureCopy(Res.string.player_channel_gone, Res.string.player_channel_gone_hint)

        code == SESSION_UNAVAILABLE -> FailureCopy(Res.string.player_no_answer, Res.string.player_no_answer_hint)

        SOURCE_UNAVAILABLE in code -> FailureCopy(Res.string.no_working_source, Res.string.no_working_source_hint)

        "NETWORK_CONNECTION" in code -> FailureCopy(Res.string.cant_reach_stream, Res.string.cant_play_retry)

        "BAD_HTTP_STATUS" in code -> FailureCopy(Res.string.stream_refused, Res.string.cant_play_retry)

        "PARSING" in code || "UNSUPPORTED" in code ->
            FailureCopy(Res.string.stream_unsupported, Res.string.cant_play_retry)

        else -> null
    }
    if (live) return known ?: FailureCopy(Res.string.cant_play, Res.string.cant_play_retry)
    // Those two codes are about a channel's session; an item's failure says only that it cannot play.
    val channelOnly = code == SESSION_UNAVAILABLE || code == Wire.ErrorCode.CHANNEL_NOT_FOUND
    return FailureCopy(
        known?.reason.takeUnless {
            channelOnly
        } ?: Res.string.vod_cant_play,
        Res.string.vod_cant_play_retry,
    )
}
