package dev.sillport.matepad

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/** How a Mac's home door speaks, from its Bonjour TXT `p` (HomeDoorTXT in Pairing.swift). */
enum class Door { PLAIN, PAIRING_REQUIRED, OPEN }

data class FoundMac(val name: String, val host: String, val port: Int, val door: Door)

/** Finds Sill for Mac on the local network: Bonjour `_sill._tcp`. */
class Discovery(context: Context) {
    private val tag = "SillDiscovery"
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private val _macs = MutableStateFlow<List<FoundMac>>(emptyList())
    val macs: StateFlow<List<FoundMac>> = _macs
    private val toResolve = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        lock = wifi.createMulticastLock("sill-mdns").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { Log.i(tag, "browsing $serviceType") }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(tag, "start failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { toResolve.add(info); resolveNext() }
            override fun onServiceLost(info: NsdServiceInfo) {
                _macs.value = _macs.value.filterNot { it.name == info.serviceName }
            }
        }
        listener = l
        nsd.discoverServices("_sill._tcp.", NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        lock?.release(); lock = null
    }

    // NsdManager resolves one service at a time on older Android: a queue.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = toResolve.poll() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                Log.w(tag, "resolve ${si.serviceName} failed $errorCode")
                resolving = false; resolveNext()
            }
            override fun onServiceResolved(si: NsdServiceInfo) {
                val host = si.host?.hostAddress?.substringBefore('%')
                if (host != null) {
                    val attrs = si.attributes
                    val p = attrs["p"] ?: attrs["P"]
                    val door = when {
                        !attrs.containsKey("p") && !attrs.containsKey("P") -> Door.PLAIN
                        p != null && String(p) == "0" -> Door.OPEN
                        else -> Door.PAIRING_REQUIRED
                    }
                    val mac = FoundMac(si.serviceName, host, si.port, door)
                    Log.i(tag, "found $mac")
                    _macs.value = _macs.value.filterNot { it.name == mac.name } + mac
                }
                resolving = false; resolveNext()
            }
        })
    }
}
