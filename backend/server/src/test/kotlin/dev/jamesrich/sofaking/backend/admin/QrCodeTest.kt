package dev.jamesrich.sofaking.backend.admin

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrCodeTest {
    @Test fun `the SVG draws the modules ZXing encodes and escapes its label`() {
        val link = pairingLink("http://192.0.2.10:9193", "123456")
        assertEquals("sofaking://pair?server=http%3A%2F%2F192.0.2.10%3A9193&code=123456", link)
        val svg = qrSvg(link, "Pairing \"<code>\" & more")
        assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "))
        assertTrue(svg.contains("aria-label=\"Pairing &quot;&lt;code&gt;&quot; &amp; more\""))
        assertEquals(link, decode(svg))
    }

    /** Reads the SVG back through ZXing by rasterizing its path: each `MxyhNv1h-Nz` run is N dark modules. */
    private fun decode(svg: String): String {
        val size = Regex("viewBox=\"0 0 (\\d+) (\\d+)\"").find(svg)!!.groupValues.let { it[1].toInt() to it[2].toInt() }
        val dark = Array(size.second) { BooleanArray(size.first) }
        for (run in Regex("M(\\d+) (\\d+)h(\\d+)v1h-\\d+z").findAll(svg)) {
            val (x, y, w) = run.destructured
            for (i in 0 until w.toInt()) dark[y.toInt()][x.toInt() + i] = true
        }
        val scale = 4
        val source = object : LuminanceSource(size.first * scale, size.second * scale) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray = ByteArray(width).also { for (x in 0 until width) it[x] = pixel(x, y) }
            override fun getMatrix(): ByteArray = ByteArray(width * height).also { for (y in 0 until height) for (x in 0 until width) it[y * width + x] = pixel(x, y) }
            private fun pixel(x: Int, y: Int): Byte = if (dark[y / scale][x / scale]) 0 else 255.toByte()
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }
}
