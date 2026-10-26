package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.DiscoveredServer
import dev.jamesrich.sofaking.data.DiscoveredServers
import dev.jamesrich.sofaking.data.ServerDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * The Swift host's Bonjour browser for `Wire.Discovery.SERVICE_TYPE`, which the Network framework serves best from
 * Swift. [start] reports to [ServerBrowserListener] on the main queue until [stop]; it is started once at a time.
 */
interface HostServerBrowser {
    fun start(listener: ServerBrowserListener)

    fun stop()
}

/** What [HostServerBrowser] finds: a server [resolved] to an IPv4 [host] and [port], or one [lost] by its name. */
interface ServerBrowserListener {
    fun resolved(name: String, host: String, port: Int, version: String?)

    fun lost(name: String)
}

/** [ServerDiscovery] over the host's [browser], browsing while [servers] is collected. */
internal class HostServerDiscovery(private val browser: HostServerBrowser) : ServerDiscovery {
    override fun servers(): Flow<List<DiscoveredServer>> = callbackFlow {
        val found = DiscoveredServers()
        launch { found.list.collect(::send) }
        browser.start(
            object : ServerBrowserListener {
                override fun resolved(name: String, host: String, port: Int, version: String?) =
                    found.resolved(name, host, port, version)

                override fun lost(name: String) = found.lost(name)
            },
        )
        awaitClose { browser.stop() }
    }.flowOn(Dispatchers.Main)
}
