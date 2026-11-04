package dev.jamesrich.sofaking.data

import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSUserDefaults

actual fun AppStorage.Companion.onApple(directory: String, defaults: NSUserDefaults): AppStorage =
    onAppleTv(directory, defaults)

actual fun appleStorageDirectory(): String = userDirectory(NSCachesDirectory, "Caches")
