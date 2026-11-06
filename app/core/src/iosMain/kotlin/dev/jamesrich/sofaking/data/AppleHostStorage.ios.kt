package dev.jamesrich.sofaking.data

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice

actual fun AppStorage.Companion.onApple(directory: String, defaults: NSUserDefaults): AppStorage {
    protectSettings("$directory/datastore")
    return inDirectory(directory, ClientInfo("ios", UIDevice.currentDevice.name))
}

actual fun appleStorageDirectory(): String = userDirectory(NSApplicationSupportDirectory, "Application Support")

/**
 * The settings file holds the server address and API token: the folder it sits in stays out of device backups, and
 * the file, which inherits the folder's class, opens once the device has been unlocked after boot, as the app needs
 * for a background refresh. Both are set on every start so a folder an earlier build made gets them too.
 */
@OptIn(ExperimentalForeignApi::class)
private fun protectSettings(folder: String) {
    val manager = NSFileManager.defaultManager
    val protection = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)
    manager.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = protection, error = null)
    manager.setAttributes(protection, ofItemAtPath = folder, error = null)
    NSURL.fileURLWithPath(folder, isDirectory = true)
        .setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    val file = "$folder/settings.preferences_pb"
    if (manager.fileExistsAtPath(file)) manager.setAttributes(protection, ofItemAtPath = file, error = null)
}
