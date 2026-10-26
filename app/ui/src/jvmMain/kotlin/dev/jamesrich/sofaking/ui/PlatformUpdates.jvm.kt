package dev.jamesrich.sofaking.ui

import androidx.compose.runtime.Composable
import dev.jamesrich.sofaking.data.PublishedBuild
import dev.jamesrich.sofaking.data.UpdateInstaller

internal actual fun updateDownloadUrl(latest: PublishedBuild): String? = latest.pageUrl

@Composable
internal actual fun rememberUpdateInstaller(): UpdateInstaller? = null

internal actual val pageServerUrl: String? = null

internal actual val updatesWithServer: Boolean = false
