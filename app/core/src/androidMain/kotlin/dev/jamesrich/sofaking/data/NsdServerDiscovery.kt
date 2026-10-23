package dev.jamesrich.sofaking.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.util.concurrent.Executors

/**
 * Browses with Android's [NsdManager], which runs mDNS in the system and needs no multicast lock. On Android 14 and
 * later each service found keeps a callback that follows its address; before that, services resolve one at a time,
 * as the older resolver allows.
 */
class NsdServerDiscovery(context: Context) : ServerDiscovery {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    override fun servers(): Flow<List<DiscoveredServer>> = callbackFlow {
        val found = DiscoveredServers()
        launch { found.list.collect(::send) }
        val resolver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            CallbackResolver(found)
        } else {
            QueueResolver(found)
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) = resolver.found(service)
            override fun onServiceLost(service: NsdServiceInfo) = resolver.lost(service)
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                channel.close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(Wire.Discovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolver.close()
        }
    }

    private interface Resolver {
        fun found(service: NsdServiceInfo)
        fun lost(service: NsdServiceInfo)
        fun close()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private inner class CallbackResolver(private val servers: DiscoveredServers) : Resolver {
        private val executor = Executors.newSingleThreadExecutor()
        private val callbacks = HashMap<String, NsdManager.ServiceInfoCallback>()

        override fun found(service: NsdServiceInfo) = executor.execute {
            val name = service.serviceName
            if (name in callbacks) return@execute
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    val host = info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress ?: return
                    servers.resolved(name, host, info.port, info.version())
                }
                override fun onServiceLost() = servers.lost(name)
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            callbacks[name] = callback
            runCatching {
                nsd.registerServiceInfoCallback(service, executor, callback)
            }.onFailure { callbacks.remove(name) }
        }

        override fun lost(service: NsdServiceInfo) = executor.execute {
            callbacks.remove(service.serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            servers.lost(service.serviceName)
        }

        override fun close() = executor.execute {
            callbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            callbacks.clear()
            executor.shutdown()
        }
    }

    /** The resolver before Android 14 takes one request at a time and fails the others, so they wait their turn. */
    private inner class QueueResolver(private val servers: DiscoveredServers) : Resolver {
        private val pending = ArrayDeque<NsdServiceInfo>()
        private var resolving = false
        private var closed = false
        private val lock = Any()

        override fun found(service: NsdServiceInfo) {
            synchronized(lock) { if (!closed) pending.addLast(service) }
            next()
        }

        override fun lost(service: NsdServiceInfo) {
            synchronized(lock) { pending.removeAll { it.serviceName == service.serviceName } }
            servers.lost(service.serviceName)
        }

        override fun close() = synchronized(lock) {
            closed = true
            pending.clear()
        }

        @Suppress("DEPRECATION") // resolveService and host are what Android 13 and earlier offer.
        private fun next() {
            val service = synchronized(lock) {
                if (resolving || closed) return
                pending.removeFirstOrNull()?.also { resolving = true } ?: return
            }
            val listener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    (info.host as? Inet4Address)?.hostAddress?.let {
                        servers.resolved(info.serviceName, it, info.port, info.version())
                    }
                    done()
                }
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
            }
            runCatching { nsd.resolveService(service, listener) }.onFailure { done() }
        }

        private fun done() {
            synchronized(lock) { resolving = false }
            next()
        }
    }
}

private fun NsdServiceInfo.version(): String? = attributes[Wire.Discovery.TXT_VERSION]?.decodeToString()
