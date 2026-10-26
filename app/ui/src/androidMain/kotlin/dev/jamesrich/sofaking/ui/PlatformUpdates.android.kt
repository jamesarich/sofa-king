package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.jamesrich.sofaking.data.PublishedBuild
import dev.jamesrich.sofaking.data.UpdateInstaller
import dev.jamesrich.sofaking.data.appUpdateInstaller

internal actual fun updateDownloadUrl(latest: PublishedBuild): String? = latest.apkUrl ?: latest.pageUrl

@Composable
internal actual fun rememberUpdateInstaller(): UpdateInstaller? {
    val context = LocalContext.current
    return remember(context) { appUpdateInstaller(context) }
}

internal actual val pageServerUrl: String? = null

internal actual val updatesWithServer: Boolean = false
