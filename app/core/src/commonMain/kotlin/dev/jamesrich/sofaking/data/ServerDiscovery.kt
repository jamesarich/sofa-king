package dev.jamesrich.sofaking.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * A Sofa King server advertising itself on the device's network: its name, its address as
 * Settings stores it, and its version.
 */
data class DiscoveredServer(val name: String, val url: String, val version: String? = null)

/**
 * Browses the device's network for servers advertising `Wire.Discovery.SERVICE_TYPE` over mDNS while [servers] is
 * collected. [None] stands in where the platform cannot browse.
 */
fun interface ServerDiscovery {
    /** The servers found so far, sorted by name; browsing stops when the collector does. */
    fun servers(): Flow<List<DiscoveredServer>>

    companion object {
        val None = ServerDiscovery { flowOf(emptyList()) }
    }
}

/**
 * The servers a browse has resolved, by service name. Platform browsers feed it from their callback threads; each
 * change is one atomic update, and [list] publishes the result sorted by name.
 */
class DiscoveredServers {
    private val byName = MutableStateFlow<Map<String, DiscoveredServer>>(emptyMap())

    val list: Flow<List<DiscoveredServer>> = byName.map { found ->
        found.values.sortedWith(compareBy({ it.name.lowercase() }, { it.url }))
    }

    /**
     * Records [name] resolved to [host] and [port]. An IPv6 or unusable host leaves the list as it was: the server
     * answers on IPv4 too, and Settings takes an address it can append the API's paths to.
     */
    fun resolved(name: String, host: String, port: Int, version: String?) {
        val url = if (':' in host || port !in 1..65535) null else ServerAddress.normalizeUrl("http://$host:$port")
        if (url != null) {
            byName.update { it + (name to DiscoveredServer(name, url, version?.takeIf(String::isNotBlank))) }
        }
    }

    fun lost(name: String) = byName.update { it - name }
}
