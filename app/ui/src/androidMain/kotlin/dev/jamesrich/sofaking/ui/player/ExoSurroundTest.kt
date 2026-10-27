package dev.jamesrich.sofaking.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import dev.jamesrich.sofaking.ui.SurroundTestPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The surround test on its own Media3 player, with the live player's audio attributes, so the tone reaches the same
 * output a channel does. The WAV is written once to the cache and played from there; the player lives on the main
 * thread, as Media3 asks.
 */
internal class ExoSurroundTest(private val context: Context) : SurroundTestPlayer {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var starting: Job? = null
    private var exo: ExoPlayer? = null

    override fun play() {
        stop()
        starting = main.launch {
            val file = withContext(Dispatchers.IO) { toneFile() }
            exo = ExoPlayer.Builder(context)
                .setAudioAttributes(MEDIA_AUDIO, true)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    prepare()
                    play()
                }
        }
    }

    override fun stop() {
        starting?.cancel()
        starting = null
        exo?.release()
        exo = null
    }

    private fun toneFile(): File {
        val file = File(context.cacheDir, "surround-test.wav")
        val wav = SurroundTone.wav()
        if (file.length() != wav.size.toLong()) file.writeBytes(wav)
        return file
    }
}
