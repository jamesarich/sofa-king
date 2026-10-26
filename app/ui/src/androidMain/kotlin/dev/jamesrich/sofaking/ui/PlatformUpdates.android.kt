package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import dev.jamesrich.sofaking.data.PublishedBuild
import dev.jamesrich.sofaking.data.UpdateInstaller
import dev.jamesrich.sofaking.data.appUpdateInstaller

internal actual fun updateDownloadUrl(latest: PublishedBuild): String? = latest.apkUrl ?: latest.pageUrl

/** None in a preview, whose package manager cannot say who installed the app. */
@Composable
internal actual fun rememberUpdateInstaller(): UpdateInstaller? {
    val context = LocalContext.current
    if (LocalInspectionMode.current) return null
    return remember(context) { appUpdateInstaller(context) }
}

internal actual val pageServerUrl: String? = null

internal actual val updatesWithServer: Boolean = false
