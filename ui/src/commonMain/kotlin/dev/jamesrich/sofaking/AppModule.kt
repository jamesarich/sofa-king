package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.DataStoreSettingsStore
import dev.jamesrich.sofaking.data.SettingsStore
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The app graph over a platform's [storage]: one settings store per file, one lineup ViewModel per screen host. */
fun appModule(storage: AppStorage): Module = module {
    single<SettingsStore> { DataStoreSettingsStore(storage.settings) }
    viewModel { LineupViewModel(store = get(), cacheStore = storage.lineupCache) }
}
