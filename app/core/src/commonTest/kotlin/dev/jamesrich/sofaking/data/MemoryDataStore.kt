package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A [DataStore] in memory, so stores are tested the same on every target without a file system. */
internal class MemoryDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()

    /** The value held now, for assertions. */
    val value: T get() = state.value

    override val data: Flow<T> = state

    override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock {
        state.value = transform(state.value)
        state.value
    }
}
