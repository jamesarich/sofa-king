package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import dev.jamesrich.sofaking.data.PublishedBuild
import dev.jamesrich.sofaking.data.UpdateInstaller
import kotlinx.browser.window

internal actual fun updateDownloadUrl(latest: PublishedBuild): String? = latest.pageUrl

@Composable
internal actual fun rememberUpdateInstaller(): UpdateInstaller? = null

internal actual val pageServerUrl: String? =
    window.location.takeIf { it.protocol == "http:" || it.protocol == "https:" }?.origin

internal actual val updatesWithServer: Boolean =
    pageServerUrl != null && window.location.pathname.startsWith("/app/")
