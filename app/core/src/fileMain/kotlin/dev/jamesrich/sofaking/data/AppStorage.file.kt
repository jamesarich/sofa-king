package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.files.Path
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Both stores under [directory], at the paths the Android app has always used (`datastore/settings.preferences_pb`
 * and `lineup-cache.bin` under its files directory), so an upgrade keeps them.
 */
fun AppStorage.Companion.inDirectory(directory: String): AppStorage = AppStorage(
    settings = preferencesDataStore(Path(directory, "datastore", "settings.preferences_pb").toString()),
    lineupCache = lineupCacheDataStore(Path(directory, "lineup-cache.bin").toString()),
)

/**
 * A lineup cache in [file]; a file that does not decode, such as one written before the cache moved to DataStore, is
 * replaced with an empty cache and the lineup refetched. DataStore allows one open store per file, so create one as a
 * DI single; [scope] runs its reads and writes, and cancelling it closes the store.
 */
fun lineupCacheDataStore(
    file: String,
    scope: CoroutineScope = CoroutineScope(ioDispatcher + SupervisorJob()),
): DataStore<CachedLineup?> = DataStoreFactory.create(
    storage = OkioStorage(FileSystem.SYSTEM, CachedLineupSerializer) { file.toPath() },
    corruptionHandler = ReplaceFileCorruptionHandler { null },
    scope = scope,
)
