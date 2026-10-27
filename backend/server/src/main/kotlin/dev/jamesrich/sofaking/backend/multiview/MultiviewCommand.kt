package dev.jamesrich.sofaking.backend.multiview

import dev.jamesrich.sofaking.backend.library.VideoEncoder
import dev.jamesrich.sofaking.backend.upstream.FfmpegSource
import dev.jamesrich.sofaking.backend.upstream.sourceOptions
import java.nio.file.Path

/** Seconds per segment of a multiview stream; a key frame starts each. */
internal const val MULTIVIEW_SEGMENT_SECONDS = 4

/** The playlist each multiview FFmpeg writes. */
internal const val MULTIVIEW_PLAYLIST = "index.m3u8"

/**
 * The FFmpeg command that draws [tiles] as one live HLS stream into [dir]: each tile letterboxed into a quarter of
 * [canvas], two side by side in the middle, three or four in a grid, with a border around tile [audio], whose sound
 * is the stream's. A tile without a source is black. A restart in the same [dir] carries the playlist on after a
 * discontinuity.
 */
internal fun multiviewCommand(
    ffmpeg: String,
    tiles: List<FfmpegSource?>,
    audio: Int,
    canvas: MultiviewCanvas,
    encoder: VideoEncoder,
    dir: Path,
): List<String> = buildList {
    addAll(listOf(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error"))
    addAll(encoder.inputArgs())
    val size = "${canvas.tileWidth}x${canvas.tileHeight}"
    for (tile in tiles) {
        if (tile == null) {
            addAll(listOf("-f", "lavfi", "-i", "color=c=black:s=$size:r=${VideoEncoder.CHANNEL_FPS}"))
        } else {
            addAll(sourceOptions(tile) + listOf("-i", tile.url))
        }
    }
    addAll(listOf("-filter_complex", graph(tiles.size, audio, canvas, encoder), "-map", "[v]"))
    addAll(listOf("-map", "$audio:a:0?", "-sn", "-dn"))
    addAll(encoder.codecArgs(MULTIVIEW_SEGMENT_SECONDS))
    addAll(listOf("-c:a", "aac", "-ac", "2", "-ar", "48000", "-b:a", "160k"))
    addAll(
        listOf(
            "-f", "hls", "-hls_time", "$MULTIVIEW_SEGMENT_SECONDS", "-hls_list_size", "6",
            "-hls_flags", "delete_segments+append_list+temp_file+independent_segments",
            "-hls_segment_type", "mpegts", "-hls_segment_filename", dir.resolve("seg%d.ts").toString(),
            dir.resolve(MULTIVIEW_PLAYLIST).toString(),
        ),
    )
}

/** The filter graph: every tile to its size, the border on [audio]'s, then the tiles stacked on the canvas. */
private fun graph(count: Int, audio: Int, canvas: MultiviewCanvas, encoder: VideoEncoder): String {
    val width = canvas.tileWidth
    val height = canvas.tileHeight
    val tiles = (0 until count).map { index ->
        val border = if (index == audio) ",drawbox=x=0:y=0:w=iw:h=ih:color=white@0.9:t=$BORDER" else ""
        "[$index:v:0]scale=$width:$height:force_original_aspect_ratio=decrease," +
            "pad=$width:$height:-1:-1:color=black,setsar=1,fps=${VideoEncoder.CHANNEL_FPS}$border[t$index]"
    }
    val inputs = (0 until count).joinToString("") { "[t$it]" }
    val stack = if (count == 2) {
        "${inputs}hstack=inputs=2,pad=${canvas.width}:${canvas.height}:0:(oh-ih)/2:color=black"
    } else {
        "${inputs}xstack=inputs=$count:layout=${LAYOUT.take(count).joinToString("|")}:fill=black"
    }
    val format = encoder.formatFilters().joinToString(",")
    return (tiles + "$stack,$format[v]").joinToString(";")
}

private val LAYOUT = listOf("0_0", "w0_0", "0_h0", "w0_h0")
private const val BORDER = 6
