package dev.jamesrich.sofaking.data

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where the DataStores run their file and decoding work: the IO pool where the platform has
 * one, the default pool on the web.
 */
internal expect val ioDispatcher: CoroutineDispatcher
