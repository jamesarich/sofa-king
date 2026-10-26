package dev.jamesrich.sofaking.ui.player

import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.ui.SurroundTestPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import java.io.File

private val log = Logger.withTag("SofaKing")

/**
 * The surround test on libVLC, from a WAV in the temporary folder. Loading libVLC can take seconds, so the work runs
 * off the UI thread, one step at a time; where libVLC is missing the test logs why and plays nothing.
 */
internal class VlcSurroundTest(private val openVlc: () -> MediaPlayerFactory = ::newVlcFactory) : SurroundTestPlayer {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var factory: MediaPlayerFactory? = null
    private var player: MediaPlayer? = null

    override fun play() {
        worker.launch {
            close()
            runCatchingCancellable {
                val file = toneFile()
                val vlc = openVlc().also { factory = it }
                player = vlc.mediaPlayers().newMediaPlayer().apply { media().play(file.absolutePath) }
            }.onFailure { log.w { "surround test: ${it.message}" } }
        }
    }

    override fun stop() {
        worker.launch { close() }
    }

    private fun close() {
        player?.release()
        player = null
        factory?.release()
        factory = null
    }

    private fun toneFile(): File {
        val file = File(System.getProperty("java.io.tmpdir"), "sofaking-surround-test.wav")
        val wav = SurroundTone.wav()
        if (file.length() != wav.size.toLong()) file.writeBytes(wav)
        return file
    }
}
