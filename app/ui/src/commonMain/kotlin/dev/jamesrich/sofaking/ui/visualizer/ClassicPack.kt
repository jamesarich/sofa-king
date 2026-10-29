package dev.jamesrich.sofaking.ui.visualizer

import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.visualizer.MilkPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** The classic MilkDrop pack that ships in [CLASSIC_PACK_FILE], read and parsed the first time it is asked. */
internal object ClassicPack {
    private val loading = Mutex()
    private var loaded: List<MilkPreset>? = null

    /** The pack's presets, or none when the file cannot be read (the next call tries again). */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun presets(): List<MilkPreset> = loading.withLock {
        loaded ?: withContext(Dispatchers.Default) {
            runCatchingCancellable { MilkPreset.parsePack(Res.readBytes(CLASSIC_PACK_FILE).decodeToString()) }
                .onSuccess { loaded = it }
                .getOrDefault(emptyList())
        }
    }
}

internal const val CLASSIC_PACK_FILE = "files/presets.txt"
