package dev.sillport.matepad

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The USB cable to the Mac. This tablet can't open a USB link to a Mac by itself, so the Mac runs
 * `sill-usb.sh`, which keeps `adb reverse tcp:47455 tcp:<Sill's port>` up while the cable is in. On
 * this side the tunnel is just 127.0.0.1:47455; this watches for it.
 */
object UsbLink {
    const val HOST = "127.0.0.1"
    const val PORT = 47455

    data class Status(
        /** The tablet is on a USB cable (charging from a computer). */
        val cable: Boolean = false,
        /** USB debugging is on, which adb needs. */
        val debugging: Boolean = false,
        /** The Mac's helper has the tunnel up: 127.0.0.1:47455 answers. */
        val tunnel: Boolean = false,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    private var job: Job? = null

    /** Polls while the app is in front (every 2 s; a local connect takes well under a millisecond). */
    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                _status.value = Status(cable = onUsbPower(app), debugging = adbOn(app), tunnel = tunnelUp())
                delay(2000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private fun onUsbPower(c: Context): Boolean {
        val i: Intent? = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == BatteryManager.BATTERY_PLUGGED_USB
    }

    private fun adbOn(c: Context): Boolean =
        runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)

    private fun tunnelUp(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(HOST, PORT), 300); true }
    }.getOrDefault(false)
}

/** What the reconnecting screen shows: which hop of the path answers. */
object PathCheck {
    data class Result(val onWifi: Boolean, val routerMs: Int?, val macReachable: Boolean?)

    fun run(context: Context, macHost: String?): Result {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val gateway = net?.let { cm.getLinkProperties(it) }?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress
        val routerMs = gateway?.let { ping(it) }
        val mac = macHost?.takeIf { it != UsbLink.HOST }?.let { ping(it) != null }
        return Result(onWifi, routerMs, mac)
    }

    /** One ICMP echo through the system's ping (apps may run it on Android), its time in ms or null. */
    private fun ping(host: String): Int? = runCatching {
        val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", host).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        Regex("time=([0-9.]+)").find(out)?.groupValues?.get(1)?.toDouble()?.toInt()
    }.getOrNull()
}
