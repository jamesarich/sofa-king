package dev.jamesrich.sofaking.ui.player

import android.graphics.Typeface
import android.net.Uri
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.media3.common.MediaMetadata
import androidx.media3.common.text.Cue

/** Session metadata: the program on top, the channel number and name under it. */
internal fun NowPlaying.toMetadata(): MediaMetadata = MediaMetadata.Builder()
    .setTitle(title)
    .setSubtitle(subtitle)
    .setArtworkUri(artworkUrl?.let(Uri::parse))
    .setIsPlayable(true)
    .build()

/** A Media3 text cue as the common renderer's [CaptionCue]; bitmap cues are skipped, as the streams here carry none. */
internal fun Cue.toCaptionCue(): CaptionCue? {
    val source = text ?: return null
    return CaptionCue(
        text = source.toAnnotatedString(),
        line = line,
        lineType = lineType,
        lineAnchor = lineAnchor,
        position = position,
        positionAnchor = positionAnchor,
    )
}

/** The stream's caption spans (608/708 colors, italics, underline) as span styles. */
private fun CharSequence.toAnnotatedString(): AnnotatedString {
    if (this !is Spanned) return AnnotatedString(toString())
    val spanned = this
    return AnnotatedString.Builder(toString()).apply {
        fun <T> each(type: Class<T>, style: (T) -> SpanStyle?) {
            for (span in spanned.getSpans(0, spanned.length, type)) {
                val start = spanned.getSpanStart(span)
                val end = spanned.getSpanEnd(span)
                if (start < end) style(span)?.let { addStyle(it, start, end) }
            }
        }
        each(ForegroundColorSpan::class.java) { SpanStyle(color = Color(it.foregroundColor)) }
        each(BackgroundColorSpan::class.java) { SpanStyle(background = Color(it.backgroundColor)) }
        each(UnderlineSpan::class.java) { SpanStyle(textDecoration = TextDecoration.Underline) }
        each(StyleSpan::class.java) {
            when (it.style) {
                Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                Typeface.BOLD_ITALIC -> SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)
                else -> null
            }
        }
    }.toAnnotatedString()
}
