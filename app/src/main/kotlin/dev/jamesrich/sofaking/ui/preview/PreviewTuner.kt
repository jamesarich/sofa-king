package dev.jamesrich.sofaking.ui.preview

/**
 * Decides whether the guide preview needs a new stream. Keyed on the stream URL, so focus moving
 * between programmes of one channel never reloads it.
 */
internal class PreviewTuner {
    private var tuned: String? = null

    /** True when [streamUrl] is a real stream that differs from the one already tuned. */
    fun needsTune(streamUrl: String?): Boolean = streamUrl != null && streamUrl != tuned

    fun markTuned(streamUrl: String) {
        tuned = streamUrl
    }
}
