package dev.jamesrich.sofaking

import androidx.compose.ui.window.ComposeUIViewController
import dev.jamesrich.sofaking.data.AppStorage
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

/** Called once from the Swift app's init, before the first [MainViewController]. */
@OptIn(ExperimentalForeignApi::class)
fun initSofaKingIos() {
    val support = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    initSofaKing(AppStorage.inDirectory(requireNotNull(support?.path) { "no Application Support directory" }))
}

/** The whole app as one view controller for the SwiftUI host. */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
