package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.ui.text.AnnotatedString
import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.DISABLED
import org.w3c.dom.HIDDEN
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.SHOWING
import org.w3c.dom.TextTrack
import org.w3c.dom.TextTrackCue
import org.w3c.dom.TextTrackMode
import org.w3c.dom.events.Event
import org.w3c.dom.get

/**
 * The element's text tracks as captions: the first is on while captions are, its cues going to [cues] while the
 * picture is drawn in the canvas ([drawsInCanvas]) and drawn by the browser over the element view otherwise.
 */
internal class HtmlTracks(private val video: HTMLVideoElement, private val drawsInCanvas: StateFlow<Boolean>) :
    PlayerTracks {
    private var captionsOn = false
    private var cueTrack: TextTrack? = null
    private val onCueChange = { _: Event -> cueTrack?.let(::publishCues) ?: Unit }
    private val _cues = MutableStateFlow<List<CaptionCue>>(emptyList())
    override val cues: StateFlow<List<CaptionCue>> = _cues.asStateFlow()

    /** Browsers expose a stream's audio tracks only behind flags, so the card never offers a choice here. */
    override val audioTracks: StateFlow<List<AudioTrack>> = MutableStateFlow(emptyList<AudioTrack>()).asStateFlow()

    override val hasTextTrack: Boolean get() = video.textTracks.length > 0

    override fun setCaptionsEnabled(enabled: Boolean) {
        captionsOn = enabled
        apply()
    }

    override fun selectAudioTrack(id: String) = Unit

    /** The element reports only the picture size. */
    override fun streamInfo(): StreamInfo = StreamInfo(
        width = video.videoWidth.takeIf { it > 0 },
        height = video.videoHeight.takeIf { it > 0 },
    )

    /** The stream is gone: no track to follow and no cues. */
    fun clear() {
        cueTrack?.removeEventListener("cuechange", onCueChange)
        cueTrack = null
        _cues.value = emptyList()
    }

    /**
     * The first text track is on while captions are. Drawn in the canvas, it stays HIDDEN and its active cues go to
     * [cues] for the shared caption renderer; the element view lets the browser draw it.
     */
    fun apply() {
        val tracks = video.textTracks
        for (i in 0 until tracks.length) {
            val track = tracks[i] ?: continue
            val on = captionsOn && i == 0
            track.mode = when {
                !on -> TextTrackMode.DISABLED
                drawsInCanvas.value -> TextTrackMode.HIDDEN
                else -> TextTrackMode.SHOWING
            }
            if (on && track !== cueTrack) {
                cueTrack?.removeEventListener("cuechange", onCueChange)
                cueTrack = track
                track.addEventListener("cuechange", onCueChange)
            }
        }
        if (!captionsOn || !drawsInCanvas.value) _cues.value = emptyList()
    }

    private fun publishCues(track: TextTrack) {
        if (!captionsOn || !drawsInCanvas.value || track.mode != TextTrackMode.HIDDEN) return
        val active = track.activeCues ?: return run { _cues.value = emptyList() }
        _cues.value = (0 until active.length).mapNotNull { i ->
            active[i]?.let { cue -> cueText(cue).takeIf { it.isNotBlank() }?.let { CaptionCue(AnnotatedString(it)) } }
        }
    }
}

/**
 * Starting the element with sound. Browsers refuse it until the page has had a user gesture, so playback starts
 * muted then, and the first key press or pointer press anywhere on the page brings the sound back.
 */
internal class HtmlAutoplay(private val video: HTMLVideoElement) {
    /** True while playback runs muted until the page's first gesture. */
    var awaitingGesture = false
        private set

    private val onGesture = { _: Event -> restoreSound() }

    init {
        // Capture phase, so the press counts even when the app consumes it.
        document.addEventListener("keydown", onGesture, true)
        document.addEventListener("pointerdown", onGesture, true)
    }

    /**
     * Plays the element. Browsers refuse autoplay with sound until the page has had a user gesture; a refused
     * play() is retried muted, which they allow, and the sound returns on the first key press or pointer press.
     */
    fun start() {
        video.play().unsafeCast<PlayPromise>().catch { error ->
            if (error.unsafeCast<DomError>().name == "NotAllowedError" && !video.muted) {
                awaitingGesture = true
                video.muted = true
                video.play()
            }
            null
        }
    }

    private fun restoreSound() {
        if (!awaitingGesture) return
        awaitingGesture = false
        video.muted = video.volume == 0.0
    }

    fun release() {
        document.removeEventListener("keydown", onGesture, true)
        document.removeEventListener("pointerdown", onGesture, true)
    }
}

/** The promise play() returns; the bindings type it differently on js and wasm. */
private external interface PlayPromise : JsAny {
    fun catch(onRejected: (JsAny) -> JsAny?): JsAny
}

private external interface DomError : JsAny {
    val name: String
}

/** A VTT cue's text with its markup tags removed; the bindings have no VTTCue. */
private fun cueText(cue: TextTrackCue): String = cue.unsafeCast<TextCue>().text.orEmpty().replace(cueMarkup, "")

private val cueMarkup = Regex("<[^>]*>")

private external interface TextCue : JsAny {
    val text: String?
}
