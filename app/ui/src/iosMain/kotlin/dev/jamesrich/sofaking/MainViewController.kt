package dev.jamesrich.sofaking

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.ClientInfo
import dev.jamesrich.sofaking.data.PairingRequest
import dev.jamesrich.sofaking.data.inDirectory
import dev.jamesrich.sofaking.data.parsePairingLink
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
    initSofaKing(
        AppStorage.inDirectory(
            requireNotNull(support?.path) {
                "no Application Support directory"
            },
            ClientInfo("ios", UIDevice.currentDevice.name),
        ),
    )
}

/**
 * Saves [serverUrl] with [apiToken], when given, and mutes the player when [muted], after [startSofaKingIos] and
 * before the first [MainViewController]. The debug host passes its `-serverUrl`, `-apiToken` and `-muted` launch
 * arguments here, so UI tests and headless checks need no typing on the simulator.
 */
fun applySofaKingLaunchSettings(serverUrl: String?, apiToken: String?, muted: Boolean) = runBlocking {
    appGraph.applyLaunchSettings(serverUrl, apiToken, muted)
}

/** A `sofaking://pair` link the host received, until the app has claimed it. */
private val pendingPairing = MutableStateFlow<PairingRequest?>(null)

/** Hands a link from SwiftUI's `onOpenURL` to the app; a `sofaking://pair` link pairs, any other link is ignored. */
fun openSofaKingLink(link: String) {
    parsePairingLink(link)?.let { pendingPairing.value = it }
}

/** The whole app as one view controller for the SwiftUI host. */
fun mainViewController(): UIViewController = ComposeUIViewController {
    val pairing by pendingPairing.collectAsState()
    App(pairing = pairing, onPairingHandled = { pendingPairing.value = null })
}
