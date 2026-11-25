package dev.sillport.matepad

import android.graphics.BitmapFactory
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopMac
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Everything before a picture shows: the Macs, the cable, pairing, errors. */
@Composable
fun ConnectScreen(client: SillClient, discovery: Discovery, saved: SavedMacs, prefs: Prefs, state: ClientState) {
    val macs by discovery.macs.collectAsState()
    val usb by UsbLink.status.collectAsState()
    var showSetup by remember { mutableStateOf(false) }
    var showAddress by remember { mutableStateOf(false) }

    if (state is ClientState.NeedCode) { PairCodeScreen(client, state); return }

    Box(Modifier.fillMaxSize().background(C.Ink)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            // header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(40.dp))
                Text("Sill", color = C.Text1, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (usb.tunnel) Chip("USB cable connected", amber = true, icon = Icons.Rounded.Usb)
                else Chip("Wi-Fi", icon = Icons.Rounded.Wifi)
            }

            when (state) {
                is ClientState.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.Amber)
                    Text(state.what, color = C.Text1, fontSize = 15.sp)
                }
                is ClientState.Failed -> Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF2A1D1B)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(state.message, color = Color(0xFFF2B8A8), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("OK", color = C.Amber, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { client.dismissError() }.padding(10.dp))
                }
                else -> {}
            }

            // USB hero: the cable is up and leads to a paired Mac
            val pairedNames = saved.all().map { it.name }
            if (usb.tunnel) UsbHero(onUsb = { client.connectUsb() }, prefs = prefs)

            BoxWithConstraints {
                val wide = maxWidth > 820.dp
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    if (wide && !usb.tunnel) Column(Modifier.width(320.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Your Mac's windows, on this MatePad.", color = C.Text1, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold)
                        Text("Open Sill on your Mac. It shows up here on the same Wi-Fi, or straight away over a USB cable.", color = C.Text2, fontSize = 15.sp, lineHeight = 22.sp)
                        SecondaryButton("Use a USB cable", icon = Icons.Rounded.Cable) { showSetup = true }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionLabel("Your Macs")
                        // Paired Macs first, found or not; then anything else Bonjour shows.
                        val rows = (pairedNames.map { n -> n to macs.firstOrNull { it.name == n || it.name.startsWith("$n (") } } +
                            macs.filter { m -> pairedNames.none { m.name == it || m.name.startsWith("$it (") } }.map { it.name to it })
                        if (rows.isEmpty()) Text("Looking for Sill for Mac on this Wi-Fi…", color = C.Text2, fontSize = 15.sp)
                        rows.forEach { (name, found) ->
                            val savedMac = saved.all().firstOrNull { it.name == name }
                            val remote = savedMac?.let { saved.remote(it.macID) }
                            MacCard(name, found, paired = name in pairedNames, lastUsed = prefs.lastUsed(name), usbUp = usb.tunnel,
                                remote = remote,
                                onConnect = { found?.let { client.connect(it) } }, onUsb = { client.connectUsb() },
                                onAway = { savedMac?.let { client.connectAway(it) } })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton("Connect by address", icon = Icons.Rounded.Add) { showAddress = true }
                            if (!wide || usb.tunnel) SecondaryButton("USB setup", icon = Icons.Rounded.Cable) { showSetup = true }
                        }
                        Text("Don't see your Mac? Check Sill is open there and both are on the same Wi-Fi.", color = C.Text2, fontSize = 13.sp)
                    }
                }
            }
        }

        if (showSetup) UsbSetupSheet(usb, onClose = { showSetup = false }, onConnect = { showSetup = false; client.connectUsb() })
        if (showAddress) AddressSheet(onClose = { showAddress = false }) { host, port -> showAddress = false; client.connect(FoundMac(host, host, port, Door.PAIRING_REQUIRED)) }
    }
}

@Composable
private fun MacCard(name: String, found: FoundMac?, paired: Boolean, lastUsed: Long, usbUp: Boolean, remote: RemoteInfo?,
                    onConnect: () -> Unit, onUsb: () -> Unit, onAway: () -> Unit) {
    val awayReady = paired && remote != null && remote.remoteAccess && remote.addresses.isNotEmpty()
    val snap = remember(name) { Snapshots.load(name) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.Card).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(Modifier.width(220.dp).height(138.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF24303B)), contentAlignment = Alignment.Center) {
            if (snap != null) Image(snap.asImageBitmap(), "$name's screen when you last used it", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Rounded.DesktopMac, null, tint = C.Muted, modifier = Modifier.size(48.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, color = C.Text1, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                if (paired) Chip("Paired", amber = true)
            }
            val where = buildList {
                add(if (found != null) "On this Wi-Fi" else "Not on this Wi-Fi right now")
                lastUsedText(lastUsed)?.let { add("Last used $it") }
            }.joinToString("  ·  ")
            Text(where, color = C.Text2, fontSize = 13.sp)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (paired && usbUp) PrimaryButton("Connect over USB", onClick = onUsb)
                if (found != null) {
                    if (paired && usbUp) SecondaryButton("Wi-Fi", onClick = onConnect)
                    else PrimaryButton(if (paired) "Connect" else "Pair", onClick = onConnect)
                } else if (awayReady && !usbUp) PrimaryButton("Connect from afar", onClick = onAway)
                if (found != null && awayReady) SecondaryButton("From afar", onClick = onAway)
            }
            // Away from home: what the Mac told this tablet, or what's missing.
            if (paired) Text(
                when {
                    awayReady -> "From afar: " + remote!!.addresses.map { it.third.ifEmpty { it.first } }.distinct().joinToString(", ")
                    remote != null && !remote.remoteAccess -> "From afar: off. Turn on Remote Access in Sill on the Mac."
                    else -> "From afar: not set up yet. Turn on Remote Access in Sill on the Mac, then connect once at home."
                }, color = C.Text2, fontSize = 12.sp)
        }
    }
}

@Composable
private fun UsbHero(onUsb: () -> Unit, prefs: Prefs) {
    var always by remember { mutableStateOf(prefs.preferUsb) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Card).border(1.dp, C.AmberLine, RoundedCornerShape(24.dp)).padding(24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(C.AmberWash), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Cable, null, tint = C.Amber, modifier = Modifier.size(48.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your Mac is on the cable", color = C.Text1, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("The cable skips Wi-Fi: steadier frames, less delay, and the tablet charges while you work.", color = C.Text2, fontSize = 15.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(always, "Always use USB when the cable is in") { always = it; prefs.preferUsb = it }
                Text("Always use USB when the cable is in", color = C.Text1, fontSize = 14.sp)
            }
        }
        PrimaryButton("Connect over USB", height = 52.dp, onClick = onUsb)
    }
}

@Composable
private fun Scrim(onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC08090A)).clickable(onClick = onClose, indication = null,
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }), contentAlignment = Alignment.Center) {
        Box(Modifier.clickable(enabled = false) {}) { content() }
    }
}

@Composable
private fun UsbSetupSheet(usb: UsbLink.Status, onClose: () -> Unit, onConnect: () -> Unit) {
    Scrim(onClose) {
        Sheet(Modifier.width(720.dp)) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Use the USB cable", color = C.Text1, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text("Three things, once. After that the cable just works whenever it's plugged in.", color = C.Text2, fontSize = 15.sp)
                    }
                    IconBtn(Icons.Rounded.Close, "Close", bg = C.Raised, onClick = onClose)
                }
                SetupStep(1, usb.debugging, "USB debugging is on", "Settings › System & updates › Developer options")
                SetupStep(2, usb.cable, "The cable is plugged into your Mac", "Tap Allow on \"Allow USB debugging?\" the first time")
                SetupStep(3, usb.tunnel, "The USB link is running on your Mac", null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val clip = LocalClipboardManager.current
                        Text("./sill-usb.sh", color = C.Text1, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.Ink).padding(horizontal = 14.dp, vertical = 10.dp))
                        SecondaryButton("Copy", height = 40.dp) { clip.setText(androidx.compose.ui.text.AnnotatedString("./sill-usb.sh")) }
                    }
                    Text(if (usb.tunnel) "Linked." else "Run it in Terminal from the sill-huaweii folder and leave it open. Waiting for it…",
                        color = C.Text2, fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PrimaryButton("Connect over USB", enabled = usb.tunnel, onClick = onConnect)
                }
            }
        }
    }
}

@Composable
private fun SetupStep(n: Int, done: Boolean, title: String, hint: String?, extra: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (!done && extra != null) Color(0xFF1E1B15) else C.Card2).padding(16.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(999.dp)).background(if (done) C.Amber else Color.Transparent)
                .border(2.dp, C.Amber, RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Rounded.Check, null, tint = Color(0xFF151619), modifier = Modifier.size(18.dp))
            else Text("$n", color = C.AmberSoft, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = C.Text1, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (hint != null) Text(hint, color = C.Text2, fontSize = 13.sp)
            extra?.invoke()
        }
        if (done) Text("Done", color = C.AmberSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AddressSheet(onClose: () -> Unit, onGo: (String, Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val host = text.substringBeforeLast(':').trim()
    val port = text.substringAfterLast(':', "").trim().toIntOrNull()
    Scrim(onClose) {
        Sheet(Modifier.width(560.dp)) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Connect by address", color = C.Text1, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text("The Mac's address and Sill's port, like 192.168.1.56:52926, or its Tailscale address.", color = C.Text2, fontSize = 14.sp)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("192.168.1.56:52926") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Text1, unfocusedTextColor = C.Text1, focusedBorderColor = C.Amber, cursorColor = C.Amber))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    SecondaryButton("Cancel", onClick = onClose)
                    PrimaryButton("Connect", enabled = host.isNotEmpty() && port != null) { onGo(host, port!!) }
                }
            }
        }
    }
}

/** Mockup 4: twelve boxes and a big number pad; a hardware keyboard types too. */
@Composable
fun PairCodeScreen(client: SillClient, state: ClientState.NeedCode) {
    var code by remember(state.mac) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    fun type(d: Char) { if (code.length < 12) code += d; if (code.length == 12) client.submitCode(code) }
    fun back() { code = code.dropLast(1) }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(C.Ink).safeDrawingPadding().focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                val c = e.nativeKeyEvent.unicodeChar.toChar()
                when {
                    c in '0'..'9' -> { type(c); true }
                    e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DEL -> { back(); true }
                    else -> false
                }
            }
    ) {
        val wide = maxWidth > maxHeight
        val info: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { client.cancelPairing() }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.ArrowBack, null, tint = C.Text2); Text("Back", color = C.Text2, fontSize = 15.sp)
                }
                Text("Pair with ${state.mac.name}", color = C.Text1, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)
                Text(state.problem ?: "Your Mac is showing a 12-digit code now. Type it here. You only do this once; after that the tablet connects on its own.",
                    color = if (state.problem != null) C.AmberSoft else C.Text2, fontSize = 16.sp, lineHeight = 23.sp)
                Text("Code not showing? On the Mac, open the Sill menu and choose Pair iPhone or iPad…", color = C.Text2, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(C.Card).padding(16.dp))
            }
        }
        val pad: @Composable () -> Unit = {
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    (0 until 3).forEach { g ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (0 until 4).forEach { k ->
                                val i = g * 4 + k
                                val d = code.getOrNull(i)
                                Box(
                                    Modifier.width(28.dp).height(46.dp).clip(RoundedCornerShape(9.dp))
                                        .background(if (d != null) Color(0xFF202228) else C.Card2)
                                        .border(if (i == code.length) 2.dp else 1.dp, if (i == code.length) C.Amber else C.Line, RoundedCornerShape(9.dp)),
                                    contentAlignment = Alignment.Center,
                                ) { Text(d?.toString() ?: "", color = C.Text1, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
                Text("${code.length} of 12 · the last digit checks for typos", color = C.Text2, fontSize = 13.sp)
                val clip = LocalClipboardManager.current
                val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "paste", "0", "del")
                keys.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { k ->
                            Box(
                                Modifier.width(133.dp).height(60.dp).clip(RoundedCornerShape(16.dp))
                                    .background(if (k.length > 1) C.Card2 else Color(0xFF202228))
                                    .clickable(role = Role.Button) {
                                        when (k) {
                                            "del" -> back()
                                            "paste" -> clip.getText()?.text?.filter { it.isDigit() }?.take(12)?.let { code = ""; it.forEach(::type) }
                                            else -> type(k[0])
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                when (k) {
                                    "del" -> Icon(Icons.Rounded.Backspace, "Delete", tint = C.Text1)
                                    "paste" -> Text("Paste", color = C.Text1, fontSize = 16.sp)
                                    else -> Text(k, color = C.Text1, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (wide) Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(56.dp)) { Box(Modifier.weight(1f)) { info() }; pad() }
        else Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) { info(); pad() }
    }
}

/** The last picture of each Mac, for its card on the connect screen. */
object Snapshots {
    private var dir: java.io.File? = null
    fun init(context: android.content.Context) { dir = context.filesDir }
    private fun file(mac: String) = dir?.let { java.io.File(it, "snap-" + mac.hashCode().toUInt().toString(16) + ".jpg") }
    fun save(mac: String, bmp: android.graphics.Bitmap) {
        val f = file(mac) ?: return
        runCatching { f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) } }
    }
    fun load(mac: String): android.graphics.Bitmap? = file(mac)?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
}
