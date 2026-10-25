package dev.jamesrich.sofaking.backend.virtual

import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import dev.jamesrich.sofaking.backend.library.MediaInfo
import dev.jamesrich.sofaking.backend.library.VideoEncoder
import java.nio.file.Path
import java.util.Locale

/** Seconds per segment of a virtual channel's stream; a key frame starts each. */
internal const val CHANNEL_SEGMENT_SECONDS = 4

/** One stretch of a channel's stream that one FFmpeg writes: [file] from [offsetMs] in, or a blank picture without. */
internal class PieceInput(val file: Path?, val media: MediaInfo?, val offsetMs: Long, val durationMs: Long)

/**
 * The FFmpeg command that writes [input] as HLS into [dir] for a virtual channel: converted to one format whatever the
 * file holds (see [VideoEncoder.channelArgs]), with silence where the file has no audio, exactly [PieceInput.durationMs]
 * long, its timestamps [timestampMs] in so they carry on from the piece before. FFmpeg reads at the speed it plays,
 * after a first [burstSeconds] read at once, so the stream keeps a little ahead of the clock and no further.
 */
internal fun pieceCommand(
    ffmpeg: String,
    input: PieceInput,
    dir: Path,
    encoder: VideoEncoder,
    timestampMs: Long,
    burstSeconds: Int,
): List<String> = buildList {
    val paced = listOf("-readrate", "1", "-readrate_initial_burst", "$burstSeconds")
    addAll(listOf(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error"))
    addAll(encoder.inputArgs())
    val file = input.file
    if (file == null) {
        addAll(paced + listOf("-f", "lavfi", "-i", "color=c=black:s=$SIZE:r=${VideoEncoder.CHANNEL_FPS}"))
    } else {
        addAll(paced)
        if (input.offsetMs > 0) addAll(listOf("-ss", seconds(input.offsetMs)))
        addAll(listOf("-i", file.toString()))
    }
    val audio = file != null && input.media?.audio?.isNotEmpty() != false
    if (!audio) addAll(listOf("-f", "lavfi", "-i", "anullsrc=r=$SAMPLE_RATE:cl=stereo"))
    addAll(listOf("-map", "0:v:0", "-map", if (audio) "0:a:0" else "1:a:0", "-sn", "-dn"))
    addAll(encoder.channelArgs(hdr = input.media?.video?.transfer != null, keyFrameSeconds = CHANNEL_SEGMENT_SECONDS))
    addAll(listOf("-c:a", "aac", "-ac", "2", "-ar", "$SAMPLE_RATE", "-b:a", "160k", "-af", "apad"))
    addAll(listOf("-t", seconds(input.durationMs), "-output_ts_offset", seconds(timestampMs)))
    addAll(
        listOf(
            "-f", "hls", "-hls_time", "$CHANNEL_SEGMENT_SECONDS", "-hls_list_size", "0",
            "-hls_playlist_type", "event", "-hls_flags", "temp_file+independent_segments",
            "-hls_segment_type", "mpegts", "-hls_segment_filename", dir.resolve("seg%d.ts").toString(),
            dir.resolve(PIECE_PLAYLIST).toString(),
        ),
    )
}

/** The playlist each piece's FFmpeg writes, listing every segment it finished. */
internal const val PIECE_PLAYLIST = "piece.m3u8"

private const val SAMPLE_RATE = 48_000
private val SIZE = "${VideoEncoder.CHANNEL_WIDTH}x${VideoEncoder.CHANNEL_HEIGHT}"

private fun seconds(ms: Long): String = "%.3f".format(Locale.ROOT, ms.toDouble() / MS_PER_SECOND)
