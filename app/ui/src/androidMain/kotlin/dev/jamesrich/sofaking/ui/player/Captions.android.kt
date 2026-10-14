package dev.jamesrich.sofaking.ui.player

import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/**
 * The viewer's caption settings from Android's CaptioningManager, or null while they are off; a change in system
 * settings applies while the app is open.
 */
@Composable
internal actual fun rememberSystemCaptionAppearance(): CaptionAppearance? {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(CaptioningManager::class.java) }
    var appearance by remember(manager) { mutableStateOf(manager?.appearance()) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val listener = object : CaptioningManager.CaptioningChangeListener() {
            override fun onEnabledChanged(enabled: Boolean) {
                appearance = manager.appearance()
            }

            override fun onUserStyleChanged(userStyle: CaptioningManager.CaptionStyle) {
                appearance = manager.appearance()
            }

            override fun onFontScaleChanged(fontScale: Float) {
                appearance = manager.appearance()
            }
        }
        manager.addCaptioningChangeListener(listener)
        onDispose { manager.removeCaptioningChangeListener(listener) }
    }
    return appearance
}

private fun CaptioningManager.appearance(): CaptionAppearance? {
    if (!isEnabled) return null
    val user = userStyle
    val typeface = user.typeface
    return CaptionAppearance(
        foreground = if (user.hasForegroundColor()) Color(user.foregroundColor) else Color.White,
        background = if (user.hasBackgroundColor()) Color(user.backgroundColor) else Color.Black,
        window = if (user.hasWindowColor()) Color(user.windowColor) else Color.Transparent,
        edge = if (user.hasEdgeType()) edgeOf(user.edgeType) else CaptionEdge.NONE,
        edgeColor = if (user.hasEdgeColor()) Color(user.edgeColor) else Color.White,
        fontFamily = typeface?.let { FontFamily(it) },
        bold = typeface?.isBold == true || typeface?.style == Typeface.BOLD,
        scale = fontScale,
        embeddedStyles = true,
    )
}

private fun edgeOf(type: Int): CaptionEdge = when (type) {
    CaptioningManager.CaptionStyle.EDGE_TYPE_OUTLINE -> CaptionEdge.OUTLINE
    CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW -> CaptionEdge.DROP_SHADOW
    CaptioningManager.CaptionStyle.EDGE_TYPE_RAISED -> CaptionEdge.RAISED
    CaptioningManager.CaptionStyle.EDGE_TYPE_DEPRESSED -> CaptionEdge.DEPRESSED
    else -> CaptionEdge.NONE
}
