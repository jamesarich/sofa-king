package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataMigration
import androidx.datastore.core.InterProcessCoordinator
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.core.createSingleProcessCoordinator
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import okio.Buffer
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.UIKit.UIDevice
import platform.posix.memcpy

/**
 * Storage for the Apple TV app. tvOS may purge any file while the app is closed and lets an app write only to Caches,
 * so the settings live in `NSUserDefaults`, which it keeps (up to 500 KB), and the lineup cache, which is a cache, in
 * [cachesDirectory]. Settings an older build kept as a DataStore file under Caches move over once on the first read.
 */
fun AppStorage.Companion.onAppleTv(
    cachesDirectory: String,
    defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
): AppStorage = AppStorage(
    settings = PreferenceDataStoreFactory.create(
        storage = UserDefaultsStorage(defaults, "sofaking.preferences"),
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        migrations = listOf(CachesFileMigration(Path(cachesDirectory, "datastore", "settings.preferences_pb"))),
        scope = CoroutineScope(ioDispatcher + SupervisorJob()),
    ),
    lineupCache = lineupCacheDataStore(Path(cachesDirectory, "lineup-cache.bin").toString()),
    client = ClientInfo("tvos", UIDevice.currentDevice.name),
)

/** A DataStore [Storage] holding the serialized preferences as one `NSData` entry of [defaults] under [key]. */
@OptIn(ExperimentalForeignApi::class)
internal class UserDefaultsStorage(private val defaults: NSUserDefaults, private val key: String) :
    Storage<Preferences> {
    override fun createConnection(): StorageConnection<Preferences> = Connection()

    private inner class Connection : StorageConnection<Preferences> {
        override val coordinator: InterProcessCoordinator = createSingleProcessCoordinator(key)
        private val writes = Mutex()

        override suspend fun <R> readScope(block: suspend ReadScope<Preferences>.(locked: Boolean) -> R): R =
            Scope().block(false)

        override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) = writes.withLock {
            Scope().block()
        }

        override fun close() = Unit
    }

    private inner class Scope : WriteScope<Preferences> {
        override suspend fun readData(): Preferences {
            val data = defaults.dataForKey(key) ?: return PreferencesSerializer.defaultValue
            return PreferencesSerializer.readFrom(Buffer().write(data.toByteArray()))
        }

        override suspend fun writeData(value: Preferences) {
            val buffer = Buffer()
            PreferencesSerializer.writeTo(value, buffer)
            defaults.setObject(buffer.readByteArray().toNSData(), key)
        }

        override fun close() = Unit
    }
}

/**
 * Moves the preferences an older build kept in the DataStore file [file] under Caches into
 * the new store, then deletes it.
 */
internal class CachesFileMigration(private val file: Path) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = SystemFileSystem.exists(file)

    override suspend fun migrate(currentData: Preferences): Preferences {
        val old = try {
            val bytes = SystemFileSystem.source(file).buffered().use { it.readByteArray() }
            PreferencesSerializer.readFrom(Buffer().write(bytes))
        } catch (_: Exception) {
            return currentData
        }
        return currentData.toMutablePreferences().apply { this += old }.toPreferences()
    }

    override suspend fun cleanUp() {
        SystemFileSystem.delete(file, mustExist = false)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
    return bytes
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
