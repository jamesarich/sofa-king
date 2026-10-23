package dev.jamesrich.sofaking.backend.admin

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.jamesrich.sofaking.backend.http.xml

/**
 * [text] as a QR code in SVG, one path of unit squares on a white field with a two-module quiet zone, so the portal
 * can draw it inline under its `img-src 'self'` policy. The encoding is ZXing's; only the drawing is here.
 */
internal fun qrSvg(text: String, title: String): String {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val path = StringBuilder()
    for (y in 0 until matrix.height) {
        var x = 0
        while (x < matrix.width) {
            if (!matrix.get(x, y)) {
                x++
                continue
            }
            val start = x
            while (x < matrix.width && matrix.get(x, y)) x++
            path.append(
                "M",
            ).append(
                start,
            ).append(' ').append(y).append('h').append(x - start).append("v1h-").append(x - start).append('z')
        }
    }
    return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${matrix.width} ${matrix.height}" role="img" aria-label="${xml(
        title,
    )}" shape-rendering="crispEdges">""" +
        """<rect width="${matrix.width}" height="${matrix.height}" fill="#fff"/><path fill="#000" d="$path"/></svg>"""
}
