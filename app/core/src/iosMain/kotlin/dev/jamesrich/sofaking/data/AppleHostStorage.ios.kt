package dev.jamesrich.sofaking.data

import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice

actual fun AppStorage.Companion.onApple(directory: String, defaults: NSUserDefaults): AppStorage =
    inDirectory(directory, ClientInfo("ios", UIDevice.currentDevice.name))

actual fun appleStorageDirectory(): String = userDirectory(NSApplicationSupportDirectory, "Application Support")
