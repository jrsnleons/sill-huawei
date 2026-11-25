package dev.sillport.matepad

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    private lateinit var client: SillClient
    private lateinit var discovery: Discovery
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Snapshots.init(this)
        prefs = Prefs(this)
        val saved = SavedMacs(this)
        client = SillClient(saved, this)
        client.autoReconnect = prefs.autoReconnect
        discovery = Discovery(this)
        client.lookup = { name -> discovery.macs.value.firstOrNull { it.name == name } }
        // Run the panel at its top refresh rate (120 Hz on the MatePad Mini) rather than an adaptive lower one.
        display?.supportedModes?.maxByOrNull { it.refreshRate }?.let { best ->
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
        val refresh = (display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f).toInt().coerceIn(60, 120)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = C.Amber, background = C.Ink, surface = C.Card)) {
                val state by client.state.collectAsState()
                val usb by UsbLink.status.collectAsState()

                // "Always use USB when the cable is in": the moment the tunnel appears, move onto it
                // (from the connect screen, or from a Wi-Fi session to a paired Mac).
                var hadTunnel by remember { mutableStateOf(usb.tunnel) }
                LaunchedEffect(usb.tunnel) {
                    val appeared = usb.tunnel && !hadTunnel
                    hadTunnel = usb.tunnel
                    val onWifi = (state as? ClientState.Streaming)?.mac?.host?.let { it != UsbLink.HOST } ?: false
                    if (appeared && prefs.preferUsb && (state is ClientState.Idle || onWifi || state is ClientState.Reconnecting)) client.connectUsb()
                }

                // While it redials, the stream screen stays up with its last picture under a "Reconnecting…" veil.
                val mac = (state as? ClientState.Streaming)?.mac ?: (state as? ClientState.Reconnecting)?.mac
                Immersive(mac != null)
                if (mac != null) StreamScreen(client, prefs, mac.name, refresh, state as? ClientState.Reconnecting, usbSession = mac.host == UsbLink.HOST)
                else ConnectScreen(client, discovery, saved, prefs, state)
            }
        }
    }

    override fun onStart() { super.onStart(); discovery.start(); UsbLink.start(this, lifecycleScope); client.onForeground() }
    override fun onStop() { discovery.stop(); UsbLink.stop(); super.onStop() }
    override fun onDestroy() { if (isFinishing) { client.disconnect(); client.decoder.release() }; super.onDestroy() }

    /** A hardware keyboard types on the Mac while the picture shows. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (client.state.value is ClientState.Streaming) {
            val hid = Keys.hid(event.keyCode)
            if (hid != null && event.repeatCount == 0 && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
                client.key(hid, event.action == KeyEvent.ACTION_DOWN, Keys.modifiers(event))
                return true
            }
            if (hid != null) return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Composable
    private fun Immersive(on: Boolean) {
        DisposableEffect(on) {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            if (on) {
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } else c.show(WindowInsetsCompat.Type.systemBars())
            onDispose {}
        }
    }
}
