package dev.jamesrich.sofaking

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.ClientInfo
import dev.jamesrich.sofaking.data.JmdnsServerDiscovery
import dev.jamesrich.sofaking.data.inDirectory
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.app_name
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.LaunchedEffect
import java.awt.Dimension

/** Sofa King on Linux, macOS and Windows desktops; a keyboard's arrows, Enter and Escape stand in for the remote. */
fun main() {
    initSofaKing(AppStorage.inDirectory(desktopDataDirectory().absolutePath, ClientInfo("desktop", hostName())), JmdnsServerDiscovery())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = stringResource(Res.string.app_name),
            state = rememberWindowState(width = 1280.dp, height = 720.dp),
        ) {
            // Below this the guide has no room for a row; the layout adapts to anything larger.
            LaunchedEffect(Unit) { window.minimumSize = Dimension(480, 360) }
            App()
        }
    }
}

/** This computer's name, for the server's Devices page; the user name when the OS has none. */
private fun hostName(): String =
    sequenceOf("COMPUTERNAME", "HOSTNAME").firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
        ?: resolvedHostName()
        ?: System.getProperty("user.name").orEmpty()

/**
 * The name the resolver gives this computer, looked up on another thread and given up after [HOST_LOOKUP_MS]: on
 * macOS, where no variable names the computer, the lookup can stall for seconds before the window opens.
 */
private fun resolvedHostName(): String? = runCatching {
    CompletableFuture.supplyAsync { InetAddress.getLocalHost().hostName }.get(HOST_LOOKUP_MS, TimeUnit.MILLISECONDS)
}.getOrNull()?.takeIf { it.isNotBlank() }

private const val HOST_LOOKUP_MS = 300L

/** The OS's per-user application data directory: Application Support on macOS, APPDATA on Windows, XDG elsewhere. */
private fun desktopDataDirectory(): File {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    return when {
        "mac" in os -> File(home, "Library/Application Support/Sofa King")
        "win" in os -> File(System.getenv("APPDATA") ?: home, "Sofa King")
        else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "sofa-king")
    }
}
