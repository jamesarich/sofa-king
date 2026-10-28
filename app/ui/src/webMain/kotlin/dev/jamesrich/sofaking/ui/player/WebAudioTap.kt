package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.visualizer.AudioAnalyzer
import org.khronos.webgl.Float32Array
import org.khronos.webgl.get
import org.w3c.dom.HTMLVideoElement

/** A Web Audio analyser node, and the context it belongs to. */
private external interface AnalyserTap : JsAny {
    val sampleRate: Double

    fun isSuspended(): Boolean

    fun getFloatTimeDomainData(into: Float32Array)

    fun resume()
}

/**
 * Hands what the `<video>` element plays to an [AudioAnalyzer]. The element goes through a Web Audio graph (source,
 * analyser, speakers) built on first use, so a page that never shows the visualizer is left as it was; [isAllowed]
 * keeps a cross-origin source without CORS out of it, since the graph would play that as silence. The analyser holds
 * the last 2048 samples as one mixed-down channel, and each frame takes the newest ones that played since the last.
 */
internal class WebAudioTap(private val video: HTMLVideoElement, private val isAllowed: () -> Boolean) {
    val analyzer = AudioAnalyzer().also { it.beforeFrame = ::pull }

    private var tap: AnalyserTap? = null
    private var failed = false
    private val latest = Float32Array(WINDOW)
    private val samples = FloatArray(WINDOW)

    private fun pull(dtSeconds: Double) {
        if (failed || !isAllowed()) return
        val node = tap ?: openTap() ?: return
        if (node.isSuspended()) node.resume()
        analyzer.sampleRate = node.sampleRate.toInt()
        node.getFloatTimeDomainData(latest)
        val count = (dtSeconds * node.sampleRate).toInt().coerceIn(1, WINDOW)
        for (i in 0 until count) samples[i] = latest[WINDOW - count + i]
        analyzer.push(samples, count, 1)
    }

    private fun openTap(): AnalyserTap? {
        val opened = runCatchingCancellable { buildTap(video) }.getOrNull()
        failed = opened == null
        tap = opened
        return opened
    }

    private companion object {
        const val WINDOW = 2048
    }
}

// detekt sees no use of the parameter: the JavaScript in js() reads it by name.
@Suppress("UnusedParameter")
private fun buildTap(video: HTMLVideoElement): AnalyserTap = js(
    """(function () {
        var Context = window.AudioContext || window.webkitAudioContext;
        var context = new Context();
        var source = context.createMediaElementSource(video);
        var analyser = context.createAnalyser();
        analyser.fftSize = 2048;
        source.connect(analyser);
        analyser.connect(context.destination);
        return {
            sampleRate: context.sampleRate,
            isSuspended: function () { return context.state === 'suspended'; },
            getFloatTimeDomainData: function (into) { analyser.getFloatTimeDomainData(into); },
            resume: function () { context.resume(); }
        };
    })()""",
)
