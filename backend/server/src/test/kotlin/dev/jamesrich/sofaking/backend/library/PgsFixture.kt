package dev.jamesrich.sofaking.backend.library

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a Blu-ray PGS (`.sup`) subtitle file, which FFmpeg reads but cannot encode: each cue a white box of
 * [boxWidth] by [boxHeight] near the bottom of a [width] by [height] picture.
 */
internal object PgsFixture {
    fun write(file: Path, cues: List<LongRange>, width: Int, height: Int, boxWidth: Int = 120, boxHeight: Int = 30) {
        val out = ByteArrayOutputStream()
        val x = (width - boxWidth) / 2
        val y = height - boxHeight - 10
        var composition = 0
        for (cue in cues) {
            out.write(segment(cue.first, 0x16) { short(width); short(height); byte(0x10); short(composition++); byte(0x80); byte(0); byte(0); byte(1); short(0); byte(0); byte(0); short(x); short(y) })
            out.write(segment(cue.first, 0x17) { byte(1); byte(0); short(x); short(y); short(boxWidth); short(boxHeight) })
            out.write(segment(cue.first, 0x14) { byte(0); byte(0); byte(1); byte(235); byte(128); byte(128); byte(255) })
            // Every line is one run of palette entry 1, then the end-of-line code.
            val line = byteArrayOf(0, (0xC0 or (boxWidth shr 8)).toByte(), boxWidth.toByte(), 1, 0, 0)
            val rle = ByteArray(line.size * boxHeight) { line[it % line.size] }
            out.write(segment(cue.first, 0x15) {
                short(0); byte(0); byte(0xC0)
                val length = rle.size + 4
                byte(length shr 16); short(length and 0xFFFF)
                short(boxWidth); short(boxHeight); write(rle)
            })
            out.write(segment(cue.first, 0x80) {})
            out.write(segment(cue.last, 0x16) { short(width); short(height); byte(0x10); short(composition++); byte(0); byte(0); byte(0); byte(0) })
            out.write(segment(cue.last, 0x17) { byte(1); byte(0); short(x); short(y); short(boxWidth); short(boxHeight) })
            out.write(segment(cue.last, 0x80) {})
        }
        Files.createDirectories(file.parent)
        Files.write(file, out.toByteArray())
    }

    private fun segment(ms: Long, type: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val data = ByteArrayOutputStream().also { DataOutputStream(it).body() }.toByteArray()
        return ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeBytes("PG"); writeInt((ms * 90).toInt()); writeInt(0); byte(type); short(data.size); write(data) }
        }.toByteArray()
    }

    private fun DataOutputStream.byte(value: Int) = writeByte(value)
    private fun DataOutputStream.short(value: Int) = writeShort(value)
}
