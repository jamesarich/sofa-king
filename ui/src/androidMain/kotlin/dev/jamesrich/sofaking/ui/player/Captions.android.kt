package dev.jamesrich.sofaking.ui.player

import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/** The viewer's caption settings from Android's CaptioningManager, or null while they are off. */
@Composable
internal actual fun rememberSystemCaptionAppearance(): CaptionAppearance? {
    val context = LocalContext.current
    return remember(context) {
        val manager = context.getSystemService(CaptioningManager::class.java)
        if (manager == null || !manager.isEnabled) return@remember null
        val user = manager.userStyle
        val typeface = user.typeface
        CaptionAppearance(
            foreground = if (user.hasForegroundColor()) Color(user.foregroundColor) else Color.White,
            background = if (user.hasBackgroundColor()) Color(user.backgroundColor) else Color.Black,
            window = if (user.hasWindowColor()) Color(user.windowColor) else Color.Transparent,
            edge = if (user.hasEdgeType()) edgeOf(user.edgeType) else CaptionEdge.NONE,
            edgeColor = if (user.hasEdgeColor()) Color(user.edgeColor) else Color.White,
            fontFamily = typeface?.let { FontFamily(it) },
            bold = typeface?.isBold == true || typeface?.style == Typeface.BOLD,
            scale = manager.fontScale,
            embeddedStyles = true,
        )
    }
}

private fun edgeOf(type: Int): CaptionEdge = when (type) {
    CaptioningManager.CaptionStyle.EDGE_TYPE_OUTLINE -> CaptionEdge.OUTLINE
    CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW -> CaptionEdge.DROP_SHADOW
    CaptioningManager.CaptionStyle.EDGE_TYPE_RAISED -> CaptionEdge.RAISED
    CaptioningManager.CaptionStyle.EDGE_TYPE_DEPRESSED -> CaptionEdge.DEPRESSED
    else -> CaptionEdge.NONE
}
