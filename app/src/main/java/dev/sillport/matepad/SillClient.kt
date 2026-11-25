package dev.sillport.matepad

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

data class MacWindow(val id: Long, val title: String, val appName: String, val bundleID: String = "")
data class MacApp(val name: String, val bundleID: String)
/** The Mac's stream settings as its kind 16 says them (HostSettings.swift). */
data class HostQuality(val bitrate: Int, val captureScale: Double, val maxFPS: Int)

sealed class ClientState {
    object Idle : ClientState()
    data class Working(val what: String) : ClientState()
    /** The Mac shows a 12-digit code; type it here. `macFingerprint` is the key the ask saw. */
    data class NeedCode(val mac: FoundMac, val macFingerprint: ByteArray, val problem: String? = null) : ClientState()
    data class Streaming(val mac: FoundMac) : ClientState()
    /** The link dropped (sleep, a Wi-Fi change, the Mac restarting Sill): the last picture stays while it redials. */
    data class Reconnecting(val mac: FoundMac, val attempt: Int, val why: String) : ClientState()
    data class Failed(val message: String) : ClientState()
}

/**
 * One Mac at a time: pair (ask, then the typed code), then a session — hello, viewport, Desktop,
 * then frames in and input out (the order Scripts/sillclient.py and the iOS client use).
 */
class SillClient(private val saved: SavedMacs, context: android.content.Context) {
    private val tag = "SillClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ClientState>(ClientState.Idle)
    val state: StateFlow<ClientState> = _state
    private val _windows = MutableStateFlow<List<MacWindow>>(emptyList())
    val windows: StateFlow<List<MacWindow>> = _windows
    private val _activeWindow = MutableStateFlow<Long?>(null) // null = Desktop
    val activeWindow: StateFlow<Long?> = _activeWindow
    private val _videoSize = MutableStateFlow(0 to 0)
    val videoSize: StateFlow<Pair<Int, Int>> = _videoSize
    private val _rttMs = MutableStateFlow<Int?>(null)
    val rttMs: StateFlow<Int?> = _rttMs

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private data class View(val w: Double, val h: Double, val fps: Int, val scale: Double?)
    private var viewport: View? = null
    /** Aa: device points per Mac point for the streamed window's text, or null to leave the window alone. */
    @Volatile var textScale: Double? = null
        set(v) { field = v; sendViewport() }

    val decoder = VideoDecoder(
        onSize = { w, h -> _videoSize.value = w to h },
        onNeedKeyframe = { send(Kind.KEYFRAME_REQUEST) },
    )

    private val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    private val app = context.applicationContext

    /** The Mac's current address by Bonjour name (its port changes when Sill restarts). Set by the activity. */
    var lookup: (String) -> FoundMac? = { null }

    // MARK: staying connected

    /** What to redial: the Mac, its door and pin. Null after a disconnect the user chose. */
    private data class Target(val mac: FoundMac, val plain: Boolean, val pin: ByteArray?)
    @Volatile private var target: Target? = null
    /** Moves on every disconnect the user chooses, so a redial already dialing can't bring the session back. */
    @Volatile private var generation = 0
    @Volatile private var lastHeardAt = 0L
    private val kick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var reconnectJob: kotlinx.coroutines.Job? = null

    /** Wi-Fi without power save while a picture shows: Wi-Fi power save alone adds 50–300 ms per packet. */
    private val wifiLock = (app.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
        .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "sill-stream").apply { setReferenceCounted(false) }

    init {
        // A network coming back (Wi-Fi rejoined, the tablet woke) redials at once instead of at the next backoff step.
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { kick.trySend(Unit) }
            override fun onLost(network: android.net.Network) {
                // The route the session used is gone: don't wait for TCP to notice.
                if (_state.value is ClientState.Streaming) runCatching { socket?.close() }
            }
        })
    }

    /** The app came back to the front: redial now if a session was lost meanwhile. */
    fun onForeground() { kick.trySend(Unit) }

    // MARK: connect and pair

    fun connect(mac: FoundMac) {
        disconnect()
        scope.launch {
            try {
                when (mac.door) {
                    Door.PLAIN -> openSession(mac, plain = true, pin = null)
                    Door.OPEN, Door.PAIRING_REQUIRED -> {
                        val known = saved.byName(mac.name)
                        if (known != null) {
                            try { openSession(mac, plain = false, pin = known.fingerprint); return@launch }
                            catch (e: PinMismatch) { Log.w(tag, "saved pin for ${mac.name} doesn't match; pairing again") }
                            catch (e: javax.net.ssl.SSLException) { Log.w(tag, "pinned session refused (${e.message}); pairing again") }
                        }
                        if (mac.door == Door.OPEN) openSession(mac, plain = false, pin = null) else ask(mac)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The user disconnected while it dialed: nothing to report.
            } catch (e: Exception) {
                Log.e(tag, "connect failed", e)
                _state.value = ClientState.Failed(friendly(e, mac.name))
            }
        }
    }

    // MARK: USB cable

    /**
     * The cable: Sill for Mac's home door, forwarded by `adb reverse` (sill-usb.sh on the Mac) to this
     * tablet's 127.0.0.1:[UsbLink.PORT]. The Mac sees a loopback client, which its home door serves at
     * home quality. Whose Mac is at the far end isn't known before the handshake, so any key is taken
     * there and the session is kept only if it is one this tablet paired with.
     */
    fun connectUsb() {
        disconnect()
        scope.launch {
            try {
                _state.value = ClientState.Working("Connecting over the USB cable…")
                val probe = SillTls.connect(UsbLink.HOST, UsbLink.PORT, SillTls.SESSION, pin = null)
                val fp = probe.macFingerprint
                runCatching { probe.socket.close() }
                val mac = saved.all().firstOrNull { it.fingerprint.contentEquals(fp) }
                if (mac == null) {
                    _state.value = ClientState.Failed("The Mac on the cable hasn't been paired with this tablet. Pair once over Wi-Fi, then use the cable.")
                    return@launch
                }
                openSession(FoundMac(mac.name, UsbLink.HOST, UsbLink.PORT, Door.PAIRING_REQUIRED), plain = false, pin = fp)
            } catch (e: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                Log.e(tag, "usb connect failed", e)
                _state.value = ClientState.Failed(
                    if (e is java.net.ConnectException) "Nothing on the cable yet. Run ./sill-usb.sh on your Mac, then try again."
                    else friendly(e, "the Mac on the cable"))
            }
        }
    }

    /** kind 19 "ask": "pair me, or show me your code". */
    private fun ask(mac: FoundMac) {
        _state.value = ClientState.Working("Asking ${mac.name} to pair…")
        val c = SillTls.connect(mac.host, mac.port, SillTls.PAIRING, pin = null)
        val r = c.socket.use { pairExchange(it, JSONObject().put("method", "ask").put("proof", "")) }
        when {
            r.optBoolean("ok") -> {
                // A proof-less ok is only for the USB cable, which this tablet never claims.
                _state.value = ClientState.Failed("The Mac answered without a proof. Not paired.")
            }
            r.optString("reason") == "shown" ->
                _state.value = ClientState.NeedCode(mac, c.macFingerprint)
            r.optString("reason") == "openOnMac" ->
                _state.value = ClientState.NeedCode(mac, c.macFingerprint, "On your Mac, choose Pair iPhone or iPad… in the Sill menu, then type the code it shows.")
            r.optString("reason") == "locked" -> _state.value = ClientState.Failed("Your Mac is locked. Unlock it and try again.")
            else -> _state.value = ClientState.Failed(reasonText(r))
        }
    }

    fun submitCode(input: String) {
        val need = _state.value as? ClientState.NeedCode ?: return
        val code = when (val chk = Pairing.checkCode(input)) {
            is Pairing.CodeCheck.Ok -> chk.code
            Pairing.CodeCheck.Length -> { _state.value = need.copy(problem = "A code has 12 digits."); return }
            Pairing.CodeCheck.Typo -> { _state.value = need.copy(problem = "That code has a typo. Check it against your Mac."); return }
        }
        scope.launch {
            try {
                _state.value = ClientState.Working("Pairing with ${need.mac.name}…")
                val c = SillTls.connect(need.mac.host, need.mac.port, SillTls.PAIRING, pin = need.macFingerprint)
                val key = Pairing.codeKey(code, c.macFingerprint)
                val devFp = DeviceIdentity.fingerprint
                val proof = Pairing.deviceProof(key, devFp, c.macFingerprint)
                val r = c.socket.use { pairExchange(it, JSONObject().put("method", "code").put("proof", Pairing.b64u(proof))) }
                if (!r.optBoolean("ok")) {
                    val left = if (r.has("triesLeft")) " ${r.getInt("triesLeft")} tries left." else ""
                    _state.value = if (r.optString("reason") == "code") need.copy(problem = "Wrong code.$left") else ClientState.Failed(reasonText(r))
                    return@launch
                }
                val macProof = Pairing.b64uDecode(r.optString("proof")) ?: ByteArray(0)
                if (!Pairing.isValidMacProof(macProof, key, c.macFingerprint, devFp)) {
                    _state.value = ClientState.Failed("The Mac's proof didn't check. Nothing saved."); return@launch
                }
                val id = Pairing.macID(c.macFingerprint)
                if (r.optString("macID") != id) { _state.value = ClientState.Failed("The Mac ID doesn't match its key. Nothing saved."); return@launch }
                saved.save(SavedMac(id, r.optString("name", need.mac.name).ifEmpty { need.mac.name }, c.macFingerprint))
                openSession(need.mac, plain = false, pin = c.macFingerprint)
            } catch (e: Exception) {
                Log.e(tag, "pairing failed", e)
                _state.value = ClientState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelPairing() { _state.value = ClientState.Idle }

    private fun pairExchange(s: Socket, fields: JSONObject): JSONObject {
        fields.put("v", 1).put("name", deviceName).put("model", Build.MODEL)
        Wire.write(s.getOutputStream(), Kind.PAIR_REQUEST, fields.toString().toByteArray())
        val m = Wire.read(DataInputStream(s.getInputStream()))
        if (m.kind != Kind.PAIR_RESULT) throw java.io.IOException("The Mac sent kind ${m.kind}, not a pairing answer")
        return JSONObject(String(m.payload))
    }

    private fun reasonText(r: JSONObject): String = when (r.optString("reason")) {
        "closed" -> "The Mac isn't taking a code right now. Choose Pair iPhone or iPad… on the Mac and try again."
        "expired" -> "That code expired. Try again for a new one."
        "busy" -> "The Mac is busy. Wait a moment and try again."
        "stopped" -> "Pairing was stopped on the Mac."
        else -> r.optString("message").ifEmpty { "The Mac said: ${r.optString("reason", "no")}" }
    }

    // MARK: session

    private fun openSession(mac: FoundMac, plain: Boolean, pin: ByteArray?, quiet: Boolean = false) {
        val gen = generation
        if (!quiet) _state.value = ClientState.Working("Connecting to ${mac.name}…")
        val s: Socket = if (plain) {
            Socket().apply { tcpNoDelay = true; trafficClass = 0x88; connect(InetSocketAddress(mac.host, mac.port), 10_000) }
        } else SillTls.connect(mac.host, mac.port, SillTls.SESSION, pin).socket
        if (gen != generation) { runCatching { s.close() }; throw kotlinx.coroutines.CancellationException("disconnected meanwhile") }
        target = Target(mac, plain, pin)
        clipboardSubscribed = false; menusSubscribed = false
        decoder.newSession()
        lastHeardAt = System.currentTimeMillis()
        s.soTimeout = 0
        socket = s
        val o = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
        out = o
        val hello = JSONObject().put("appVersion", "0.6.3").put("build", "1").put("protocol", 1)
            .put("device", "$deviceName (MatePad port)")
        send(Kind.HELLO, hello.toString().toByteArray())   // always first on a session
        sendViewport()
        send(Kind.SELECT_SOURCE, """{"desktop":{}}""".toByteArray())
        _activeWindow.value = null
        _state.value = ClientState.Streaming(mac)
        wifiLock.acquire()
        startPings(s)
        Thread({ readLoop(s, mac) }, "sill-read").apply { priority = Thread.MAX_PRIORITY }.start()
    }

    private fun readLoop(s: Socket, mac: FoundMac) {
        val input = DataInputStream(java.io.BufferedInputStream(s.getInputStream(), 256 * 1024))
        try {
            while (true) {
                val m = Wire.read(input)
                lastHeardAt = System.currentTimeMillis()
                when (m.kind) {
                    Kind.PARAMETER_SETS -> ParameterSets.parse(m.payload)?.let { decoder.onParameterSets(it) }
                    Kind.FRAME -> decoder.onFrame(m.payload, m.isKeyframe)
                    Kind.WINDOW_LIST -> onWindowList(JSONObject(String(m.payload)))
                    Kind.CLIPBOARD -> JSONObject(String(m.payload)).optString("text").takeIf { it.isNotEmpty() }?.let { t ->
                        if (t != lastSentClip) _macCopied.tryEmit(t)
                    }
                    Kind.MAC_MENU -> onMacMenu(JSONObject(String(m.payload)))
                    Kind.THUMBNAIL -> onThumbnail(m.payload)
                    Kind.CURSOR_SHAPE -> onCursorShape(m.payload)
                    Kind.MAC_INFO -> runCatching { onMacInfo(JSONObject(String(m.payload))) }
                    Kind.MAC_POINTER -> runCatching { onMacPointer(JSONObject(String(m.payload))) }
                    Kind.APP_ICON -> onIcon(m.payload)
                    Kind.APP_LIST -> runCatching { onAppList(JSONArray(String(m.payload))) }
                    Kind.HOST_SETTINGS -> runCatching { onHostSettings(JSONObject(String(m.payload))) }
                    Kind.PONG -> if (m.payload.size == 8) {
                        val sent = java.nio.ByteBuffer.wrap(m.payload).double
                        _rttMs.value = ((System.currentTimeMillis() / 1000.0 - sent) * 1000).toInt()
                    }
                    Kind.GOODBYE -> {
                        val g = JSONObject(String(m.payload))
                        val reason = g.optString("reason")
                        val text = g.optString("message").ifEmpty { goodbyeText(reason, mac.name) }
                        val again = g.optBoolean("reconnect", reason == "quit" || reason == "restarting")
                        if (reason == "removed") saved.byName(mac.name)?.let { saved.forget(it.macID) }
                        if (again) sessionLost(s, text) else { target = null; endSession(s); _state.value = ClientState.Failed(text) }
                        return
                    }
                    else -> {} // kinds this client doesn't use yet: skipped, as Sill's rules want
                }
            }
        } catch (e: Exception) {
            if (socket === s) {
                Log.w(tag, "session ended: ${e.message}")
                sessionLost(s, if (e is EOFException) "The Mac closed the connection." else "The connection dropped.")
            }
        } finally { runCatching { s.close() } }
    }

    /** What a connection error means for the person holding the tablet. */
    private fun friendly(e: Throwable, name: String): String {
        val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return when {
            "EHOSTUNREACH" in m || "ENETUNREACH" in m ->
                "Can't reach $name on this Wi-Fi. Check that both are on the same network; if they are, the router may be blocking devices from seeing each other."
            "ECONNREFUSED" in m -> "$name isn't accepting connections. Is Sill open on the Mac?"
            "timed out" in m || e is java.net.SocketTimeoutException -> "$name didn't answer in time. Is it awake?"
            e is javax.net.ssl.SSLException -> "$name refused this tablet. It may have been removed on the Mac: pair again."
            else -> "Couldn't connect to $name (${e.message ?: e.javaClass.simpleName})."
        }
    }

    private fun goodbyeText(reason: String, name: String) = when (reason) {
        "quit" -> "Sill quit on $name. Waiting for it to open again…"
        "removed" -> "$name removed this tablet. Pair again to connect."
        "busy" -> "$name is busy with another device."
        "pairingRequired" -> "$name now requires pairing."
        else -> "$name closed the session ($reason)."
    }

    private fun endSession(s: Socket) {
        if (socket === s) { socket = null; out = null }
        runCatching { s.close() }
        wifiLock.release()
    }

    /** Settings › Reconnect by itself. Off: a dropped session just ends. */
    @Volatile var autoReconnect = true

    /** The session ended without the user asking: keep the last picture up and redial with backoff. */
    private fun sessionLost(s: Socket, why: String) {
        endSession(s)
        val t = target?.takeIf { autoReconnect } ?: run { target = null; _state.value = ClientState.Failed(why); return }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val started = System.currentTimeMillis()
            var attempt = 0
            var latest = why
            while (target === t) {
                attempt++
                _state.value = ClientState.Reconnecting(t.mac, attempt, latest)
                // Ask Bonjour where the Mac is now: Sill's port changes when it restarts, the address on a new network.
                val mac = if (t.mac.host == UsbLink.HOST) t.mac
                    else lookup(t.mac.name)?.takeIf { it.door == t.mac.door || t.pin != null } ?: t.mac
                try {
                    openSession(mac, t.plain, t.pin, quiet = true)
                    Log.i(tag, "reconnected after $attempt tries")
                    return@launch
                } catch (e: PinMismatch) {
                    target = null
                    _state.value = ClientState.Failed("${t.mac.name} presented a different key. Pair again from the list.")
                    return@launch
                } catch (e: Exception) {
                    Log.i(tag, "redial $attempt failed: ${e.message}")
                    latest = friendly(e, t.mac.name)
                    _state.value = ClientState.Reconnecting(t.mac, attempt, latest)
                }
                if (System.currentTimeMillis() - started > 3 * 60_000) {
                    target = null
                    _state.value = ClientState.Failed("Couldn't reach ${t.mac.name} for 3 minutes. Tap it to try again.")
                    return@launch
                }
                // 0.5, 1, 2, 4, then every 5 s, or at once when the network comes back.
                val wait = minOf(5000L, 500L shl minOf(attempt - 1, 4))
                kotlinx.coroutines.withTimeoutOrNull(wait) { kick.receive() }
            }
        }
    }

    private fun onWindowList(j: JSONObject) {
        val arr = j.optJSONArray("windows") ?: JSONArray()
        _windows.value = (0 until arr.length()).map {
            val w = arr.getJSONObject(it)
            MacWindow(w.getLong("id"), w.optString("title"), w.optString("appName"), w.optString("bundleID"))
        }
        val active = j.optJSONObject("active")
        _activeWindow.value = active?.optJSONObject("window")?.optLong("_0")
        // Ask for what this host offers, once per connection, after its first window list.
        _gesturesAvailable.value = j.optInt("gestures", 0) >= 1
        val clip = j.optInt("clipboard", 0) >= 1
        _clipboardAvailable.value = clip
        if (clip && !clipboardSubscribed) { clipboardSubscribed = true; send(Kind.CLIPBOARD, "{}".toByteArray()) }
        if (!menusSubscribed) { menusSubscribed = true; send(Kind.FETCH_MENU, JSONObject().put("token", nextToken()).toString().toByteArray()) }
    }

    // MARK: trackpad gestures (kind 28, Gesture.swift)

    private val _gesturesAvailable = MutableStateFlow(false)
    val gesturesAvailable: StateFlow<Boolean> = _gesturesAvailable

    /** swipeUp (Mission Control), swipeDown (App Exposé), swipeLeft/Right (Spaces), pinch (Apps), spread (Show Desktop). */
    fun gesture(name: String, fingers: Int) {
        if (!_gesturesAvailable.value) return
        send(Kind.GESTURE, JSONObject().put("gesture", name).put("fingers", fingers).toString().toByteArray())
    }

    // MARK: clipboard (kind 32, Clipboard.swift)

    private var clipboardSubscribed = false
    @Volatile private var lastSentClip: String? = null
    private val _clipboardAvailable = MutableStateFlow(false)
    val clipboardAvailable: StateFlow<Boolean> = _clipboardAvailable
    private val _macCopied = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Text copied on the Mac; the activity puts it on this tablet's clipboard. */
    val macCopied: kotlinx.coroutines.flow.SharedFlow<String> = _macCopied

    /** Puts `text` on the Mac's clipboard, then presses ⌘V there a moment later, as Sill for iPad's Paste does. */
    fun pasteOnMac(text: String) {
        val bytes = text.toByteArray()
        if (text.isEmpty() || bytes.size > 256 * 1024) return
        lastSentClip = text
        send(Kind.CLIPBOARD, JSONObject().put("text", text).toString().toByteArray())
        scope.launch {
            kotlinx.coroutines.delay(250)
            key(0xE3, true, Keys.COMMAND); key(0x19, true, Keys.COMMAND)
            key(0x19, false, Keys.COMMAND); key(0xE3, false, 0)
        }
    }

    // MARK: the Mac's menus (kinds 24, 25, 27; MacMenu.swift)

    data class MenuItem(val id: String?, val title: String, val separator: Boolean, val enabled: Boolean,
                        val mark: String?, val key: String?, val submenu: Boolean)
    data class MenuBar(val version: Int?, val app: String, val menus: List<MenuItem>)
    data class MenuPage(val title: String, val id: String, val version: Int?, val items: List<MenuItem>?, val note: String? = null)

    private var menusSubscribed = false
    private var token = 0
    @Synchronized private fun nextToken() = ++token
    private val _menuBar = MutableStateFlow<MenuBar?>(null)
    val menuBar: StateFlow<MenuBar?> = _menuBar
    /** The menu pages open now, outermost first (a submenu pushes one). */
    private val _menuPages = MutableStateFlow<List<MenuPage>>(emptyList())
    val menuPages: StateFlow<List<MenuPage>> = _menuPages
    private val pendingFetch = java.util.concurrent.ConcurrentHashMap<Int, String>()   // token → page id
    private val _menuNote = MutableStateFlow<String?>(null)
    val menuNote: StateFlow<String?> = _menuNote

    private fun items(arr: JSONArray?): List<MenuItem> = if (arr == null) emptyList() else (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        MenuItem(o.optString("id").ifEmpty { null }, o.optString("title"), o.optBoolean("separator"),
            o.optBoolean("enabled", true), o.optString("mark").ifEmpty { null }, o.optString("key").ifEmpty { null },
            o.optBoolean("submenu"))
    }

    private fun onMacMenu(j: JSONObject) {
        val version = if (j.has("version")) j.getInt("version") else null
        if (!j.has("answering")) {
            // A top level, now or whenever the app or its titles change: pages from an older version are stale.
            if (_menuBar.value?.version != version) _menuCache.value = emptyMap()
            _menuBar.value = MenuBar(version, j.optString("app"), items(j.optJSONArray("menus")))
            if (_menuPages.value.any { it.version != version }) _menuPages.value = emptyList()
            return
        }
        val answering = j.getInt("answering")
        if (j.has("pressed") || j.optBoolean("stale")) {
            if (!j.optBoolean("pressed")) _menuNote.value = j.optString("note").ifEmpty { "The menus changed. Try again." }
            return
        }
        val pageId = pendingFetch.remove(answering) ?: return
        val got = items(j.optJSONArray("items"))
        menuTitles[pageId]?.let { title -> _menuCache.value = _menuCache.value + (pageId to (title to got)) }
        _menuPages.value = _menuPages.value.map {
            if (it.id == pageId) it.copy(items = items(j.optJSONArray("items")), version = version ?: it.version,
                note = j.optString("note").ifEmpty { null }) else it
        }
    }

    /** Opens a menu (top-level or submenu): pushes a page and asks the Mac for its items. */
    fun openMenu(item: MenuItem, fromTop: Boolean) {
        val id = item.id ?: return
        val version = _menuBar.value?.version
        val t = nextToken()
        pendingFetch[t] = id
        menuTitles[id] = item.title
        val page = MenuPage(item.title, id, version, null)
        _menuPages.value = if (fromTop) listOf(page) else _menuPages.value + page
        val j = JSONObject().put("id", id).put("title", item.title).put("token", t)
        if (version != null) j.put("version", version)
        send(Kind.FETCH_MENU, j.toString().toByteArray())
    }

    /** Every menu read so far, by id: its title and items, for "Search every menu". */
    private val _menuCache = MutableStateFlow<Map<String, Pair<String, List<MenuItem>>>>(emptyMap())
    val menuCache: StateFlow<Map<String, Pair<String, List<MenuItem>>>> = _menuCache
    private val menuTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Reads every top-level menu, for search. Each answer fills the cache. */
    fun prefetchMenus() {
        val bar = _menuBar.value ?: return
        for (m in bar.menus) {
            val id = m.id ?: continue
            if (_menuCache.value.containsKey(id)) continue
            val t = nextToken()
            pendingFetch[t] = id; menuTitles[id] = m.title
            val j = JSONObject().put("id", id).put("title", m.title).put("token", t)
            bar.version?.let { j.put("version", it) }
            send(Kind.FETCH_MENU, j.toString().toByteArray())
        }
    }

    fun menuBack() { _menuPages.value = _menuPages.value.dropLast(1) }
    fun closeMenus() { _menuPages.value = emptyList(); _menuNote.value = null }

    fun pressMenuItem(item: MenuItem) {
        val id = item.id ?: return
        val j = JSONObject().put("id", id).put("title", item.title).put("token", nextToken())
        _menuBar.value?.version?.let { j.put("version", it) }
        send(Kind.PRESS_MENU_ITEM, j.toString().toByteArray())
        _menuPages.value = emptyList()
    }

    private fun startPings(s: Socket) {
        scope.launch {
            while (socket === s && !s.isClosed) {
                val b = java.nio.ByteBuffer.allocate(8).putDouble(System.currentTimeMillis() / 1000.0).array()
                send(Kind.PING, b)
                kotlinx.coroutines.delay(1000)
                // The Mac answers every ping and ticks while streaming: 4 s of silence is a dead link
                // (a sleeping tablet, a Wi-Fi handover) that TCP would take minutes to notice.
                if (System.currentTimeMillis() - lastHeardAt > 4000 && socket === s) {
                    Log.i(tag, "nothing from the Mac for 4 s; redialing")
                    runCatching { s.close() }
                }
            }
        }
    }

    fun disconnect() {
        generation++
        target = null
        reconnectJob?.cancel(); reconnectJob = null
        val s = socket
        socket = null; out = null
        runCatching { s?.close() }
        wifiLock.release()
        _windows.value = emptyList()
        clipboardSubscribed = false; menusSubscribed = false
        _menuBar.value = null; _menuPages.value = emptyList(); _clipboardAvailable.value = false
        _thumbnails.value = emptyMap(); _menuCache.value = emptyMap(); _quality.value = null; _pointerAt.value = null
        if (_state.value is ClientState.Streaming || _state.value is ClientState.Reconnecting) _state.value = ClientState.Idle
    }

    fun dismissError() { if (_state.value is ClientState.Failed) _state.value = ClientState.Idle }

    // MARK: client → host

    /** One writer thread, so messages leave in the order they were made (input must not reorder). */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "sill-write") }

    private fun send(kind: Int, payload: ByteArray = ByteArray(0)) {
        val o = out ?: return
        writer.execute { runCatching { Wire.write(o, kind, payload) } }
    }

    /** Where the picture sits on screen, in dp, and the panel's refresh rate. */
    fun setViewport(widthDp: Double, heightDp: Double, fps: Int) {
        viewport = View(widthDp, heightDp, fps, null)
        sendViewport()
    }

    /** The frame rate this tablet asks for: 120 on its panel, or 60 to save battery. */
    @Volatile var wantedFps: Int = 120
        set(v) { field = v; sendViewport() }

    private fun sendViewport() {
        val v = viewport ?: return
        val j = JSONObject().put("width", v.w.coerceIn(1.0, 16384.0)).put("height", v.h.coerceIn(1.0, 16384.0))
            .put("scale", textScale?.coerceIn(0.5, 1.5) ?: JSONObject.NULL).put("fps", minOf(v.fps, wantedFps))
        send(Kind.VIEWPORT, j.toString().toByteArray())
    }

    // MARK: away from home (kind 18, Remote.swift)

    /**
     * Kind 18: who this Mac is and how to reach it from afar, signed with its identity key. Kept only
     * when the signature verifies over the exact info bytes, the key is the one this session is
     * pinned to, its Mac ID matches, and it is newer than the saved one.
     */
    private fun onMacInfo(j: JSONObject) {
        val pin = target?.pin ?: return
        val bytes = android.util.Base64.decode(j.getString("info"), android.util.Base64.DEFAULT)
        val point = android.util.Base64.decode(j.getString("key"), android.util.Base64.DEFAULT)
        val sig = android.util.Base64.decode(j.getString("sig"), android.util.Base64.DEFAULT)
        if (point.size != 65) return
        val spki = byteArrayOf(0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
            0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00) + point
        val fp = Pairing.sha256(spki)
        if (!fp.contentEquals(pin)) { Log.w(tag, "kind 18 signed by another key; ignored"); return }
        val key = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(spki))
        val ok = java.security.Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(bytes); verify(sig) }
        if (!ok) { Log.w(tag, "kind 18 signature doesn't verify; ignored"); return }
        val text = String(bytes)
        val id = JSONObject(text).optString("macID")
        if (id != Pairing.macID(fp)) return
        saved.saveRemote(id, text)
        Log.i(tag, "saved how to reach ${JSONObject(text).optString("name")} from afar")
    }

    /**
     * Away from home: the Mac's remote door (Remote Access on), at the addresses it last sent while
     * this tablet was home (Tailscale first, in the Mac's dial order), pinned to the paired key.
     */
    fun connectAway(mac: SavedMac) {
        disconnect()
        scope.launch {
            val info = saved.remote(mac.macID)
            if (info == null || !info.remoteAccess || info.addresses.isEmpty()) {
                _state.value = ClientState.Failed("${mac.name} hasn't told this tablet how to reach it from afar. Turn on Remote Access in Sill on the Mac, then connect once at home.")
                return@launch
            }
            var last: Exception? = null
            for ((host, port, via) in info.addresses) {
                try {
                    _state.value = ClientState.Working("Reaching ${mac.name} through $via…")
                    openSession(FoundMac(mac.name, host, port ?: info.port, Door.PAIRING_REQUIRED, away = true), plain = false, pin = mac.fingerprint, quiet = true)
                    return@launch
                } catch (e: kotlinx.coroutines.CancellationException) { return@launch
                } catch (e: PinMismatch) {
                    _state.value = ClientState.Failed("Something at $host answered with a different key than ${mac.name}. Not connected."); return@launch
                } catch (e: Exception) { last = e; Log.i(tag, "away via $via ($host) failed: ${e.message}") }
            }
            _state.value = ClientState.Failed(
                "Couldn't reach ${mac.name} from here. Check that Tailscale is on on this tablet and that the Mac is awake with Remote Access on." +
                    (last?.message?.let { "\n($it)" } ?: ""))
        }
    }

    // MARK: the Mac's cursor (kinds 14 and 26)

    /** The Mac's current cursor image, with its hotspot and size in points (the Mac leaves it out of the video). */
    data class CursorShape(val image: android.graphics.Bitmap, val hotX: Float, val hotY: Float, val w: Float, val h: Float)
    private val _cursorShape = MutableStateFlow<CursorShape?>(null)
    val cursorShape: StateFlow<CursorShape?> = _cursorShape
    /** Where the pointer is, as a fraction of the picture: from this tablet's own input, or the Mac's report. Null: hidden. */
    private val _pointerAt = MutableStateFlow<Pair<Float, Float>?>(null)
    val pointerAt: StateFlow<Pair<Float, Float>?> = _pointerAt

    private fun onCursorShape(d: ByteArray) {
        if (d.size <= 16) return
        val b = java.nio.ByteBuffer.wrap(d, 0, 16)
        val hx = b.float; val hy = b.float; val w = b.float; val h = b.float
        val bmp = android.graphics.BitmapFactory.decodeByteArray(d, 16, d.size - 16) ?: return
        if (w > 0f && h > 0f) _cursorShape.value = CursorShape(bmp, hx, hy, w, h)
    }

    /** Kind 26: the Mac's own mouse (or another device) moved the pointer. */
    private fun onMacPointer(j: JSONObject) {
        val inside = j.optBoolean("inside", false)
        _pointerAt.value = if (inside && j.has("x") && j.has("y")) j.getDouble("x").toFloat() to j.getDouble("y").toFloat() else null
    }

    // MARK: the switcher's pictures, the Mac's apps, window actions, quality

    private val _thumbnails = MutableStateFlow<Map<Long, android.graphics.Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<Long, android.graphics.Bitmap>> = _thumbnails
    private val _icons = MutableStateFlow<Map<String, android.graphics.Bitmap>>(emptyMap())
    val icons: StateFlow<Map<String, android.graphics.Bitmap>> = _icons
    private val _apps = MutableStateFlow<List<MacApp>>(emptyList())
    val apps: StateFlow<List<MacApp>> = _apps
    private val _quality = MutableStateFlow<HostQuality?>(null)
    val quality: StateFlow<HostQuality?> = _quality

    private fun onThumbnail(d: ByteArray) {
        if (d.size <= 4) return
        val id = java.nio.ByteBuffer.wrap(d, 0, 4).int.toLong() and 0xFFFFFFFFL
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 } // bar and grid sizes, not full
        val bmp = android.graphics.BitmapFactory.decodeByteArray(d, 4, d.size - 4, opts) ?: return
        _thumbnails.value = _thumbnails.value + (id to bmp)
    }

    private fun onIcon(d: ByteArray) {
        if (d.size <= 2) return
        val len = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        if (d.size <= 2 + len) return
        val id = String(d, 2, len)
        val bmp = android.graphics.BitmapFactory.decodeByteArray(d, 2 + len, d.size - 2 - len) ?: return
        _icons.value = _icons.value + (id to bmp)
    }

    private fun onAppList(arr: JSONArray) {
        _apps.value = (0 until arr.length()).map { val o = arr.getJSONObject(it); MacApp(o.optString("name"), o.optString("bundleID")) }
            .filter { it.bundleID.isNotEmpty() }.sortedBy { it.name.lowercase() }
    }

    private fun onHostSettings(j: JSONObject) {
        val st = j.optJSONObject("settings") ?: return
        _quality.value = HostQuality(st.optInt("bitrate"), st.optDouble("captureScale", 2.0), st.optInt("maxFPS", 60))
    }

    /** Opens an app on the Mac; its first window gets picked. */
    fun launchApp(bundleID: String) = send(Kind.LAUNCH_APP, JSONObject().put("bundleID", bundleID).toString().toByteArray())

    /** "close", "minimize" or "fullScreen". */
    fun windowCommand(id: Long, action: String) =
        send(Kind.WINDOW_COMMAND, JSONObject().put("id", id).put("action", action).toString().toByteArray())

    /** A quality change on the Mac (kind 17): only the fields that changed, plus a token. */
    fun changeSettings(bitrate: Int? = null, captureScale: Double? = null) {
        val j = JSONObject().put("token", nextToken())
        bitrate?.let { j.put("bitrate", it) }
        captureScale?.let { j.put("captureScale", it) }
        send(Kind.CHANGE_SETTINGS, j.toString().toByteArray())
    }

    fun selectDesktop() { send(Kind.SELECT_SOURCE, """{"desktop":{}}""".toByteArray()); _activeWindow.value = null }
    fun selectWindow(id: Long) { send(Kind.SELECT_SOURCE, """{"window":{"_0":$id}}""".toByteArray()); _activeWindow.value = id }

    /** x and y are fractions of the video frame, 0…1. */
    fun pointer(action: String, x: Double, y: Double) = run {
        _pointerAt.value = x.toFloat() to y.toFloat()
        input(JSONObject().put("pointer", JSONObject().put("_0", action).put("x", x).put("y", y)))
    }

    fun scroll(x: Double, y: Double, dx: Double, dy: Double) =
        input(JSONObject().put("scroll", JSONObject().put("x", x).put("y", y).put("dx", dx).put("dy", dy)))

    fun scrollPhase(phase: String, x: Double, y: Double) =
        input(JSONObject().put("scrollGesture", JSONObject().put("_0", phase).put("x", x).put("y", y)))

    fun text(t: String) = input(JSONObject().put("text", JSONObject().put("_0", t)))

    fun key(hidUsage: Int, down: Boolean, modifiers: Long) =
        input(JSONObject().put("key", JSONObject().put("hidUsage", hidUsage).put("down", down).put("modifiers", modifiers)))

    private fun input(j: JSONObject) = send(Kind.INPUT, j.toString().toByteArray())
}
