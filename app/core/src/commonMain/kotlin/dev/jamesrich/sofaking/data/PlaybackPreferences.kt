package dev.jamesrich.sofaking.data

/**
 * The playback knobs the Android player exposes; the other engines ignore them. [matchFrameRate] switches the
 * display to the stream's frame rate, which some sets answer with a moment of black, so it starts off.
 */
data class PlaybackPreferences(
    val matchFrameRate: Boolean = false,
    val decoder: DecoderPreference = DecoderPreference.AUTO,
    /** Per-channel overrides of [decoder], keyed by [Channel.guideId], for the odd stream a hardware decoder cannot play. */
    val decoderByChannel: Map<String, DecoderPreference> = emptyMap(),
    val buffer: BufferPreference = BufferPreference.STANDARD,
) {
    fun decoderFor(guideId: String): DecoderPreference = decoderByChannel[guideId] ?: decoder
}

/** AUTO takes the platform's preferred (hardware) decoder; SOFTWARE puts the software decoders first. */
enum class DecoderPreference { AUTO, SOFTWARE }

/**
 * How much media the player downloads before it starts: [startMs] on a fresh start and [rebufferMs] after a
 * stall. STANDARD is the engine's own; FAST_START trades a stall or two for a quicker picture, SMOOTH the reverse.
 */
enum class BufferPreference(val startMs: Int, val rebufferMs: Int) {
    FAST_START(500, 1_000),
    STANDARD(1_000, 2_000),
    SMOOTH(4_000, 6_000),
}
