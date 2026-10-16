package dev.jamesrich.sofaking

import androidx.compose.ui.window.ComposeUIViewController
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.ClientInfo
import dev.jamesrich.sofaking.data.inDirectory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController

/**
 * Called once from the Swift app's init, before the first [MainViewController]. Not named `init…`: Kotlin/Native
 * exports such functions to Swift as `doInit…`, since Objective-C reserves the init family for initializers.
 */
@OptIn(ExperimentalForeignApi::class)
fun startSofaKingIos() {
    val support = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    initSofaKing(AppStorage.inDirectory(requireNotNull(support?.path) { "no Application Support directory" }, ClientInfo("ios", UIDevice.currentDevice.name)))
}

/** The whole app as one view controller for the SwiftUI host. */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
