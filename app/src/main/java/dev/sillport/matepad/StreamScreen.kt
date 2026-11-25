package dev.sillport.matepad

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DesktopMac
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class Panel { NONE, SWITCHER, MENUS, SETTINGS, GUIDE }

class BarActions(val open: (Panel) -> Unit, val typing: () -> Unit, val hide: () -> Unit, val disconnect: () -> Unit, val note: (String) -> Unit)

/** The picture and everything over it: floating bar, side rail, hidden handle, or laptop mode upright. */
@Composable
fun StreamScreen(client: SillClient, prefs: Prefs, macName: String, refreshRate: Int, reconnecting: ClientState.Reconnecting?, link: String) {
    val usbSession = link == "USB"
    val rtt by client.rttMs.collectAsState()
    var barVisible by remember { mutableStateOf(!prefs.autoHideBar) }
    var panel by remember { mutableStateOf(if (prefs.seenGuide) Panel.NONE else Panel.GUIDE) }
    var typing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var barStyle by remember { mutableStateOf(prefs.barStyle) }
    var poke by remember { mutableStateOf(0) } // a touch on the bar restarts the auto-hide clock

    LaunchedEffect(Unit) {
        prefs.markUsed(macName)
        client.textScale = prefs.textScale.takeIf { it > 0f }?.toDouble()
        client.wantedFps = if (prefs.fps60) 60 else 120
    }
    LaunchedEffect(barVisible, poke, panel) {
        if (prefs.autoHideBar && barVisible && panel == Panel.NONE) { kotlinx.coroutines.delay(3000); barVisible = false }
    }
    LaunchedEffect(note) { if (note != null) { kotlinx.coroutines.delay(2000); note = null } }
    val menuNote by client.menuNote.collectAsState()
    LaunchedEffect(menuNote) { if (menuNote != null) note = menuNote }

    val actions = BarActions(
        open = { p -> panel = if (panel == p) Panel.NONE else p; poke++ },
        typing = { typing = !typing },
        hide = { barVisible = false; panel = Panel.NONE },
        disconnect = { client.disconnect() },
        note = { note = it },
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val portrait = maxHeight > maxWidth
        var cursor by remember { mutableStateOf(0.5f to 0.5f) }

        if (portrait) {
            // Mockup 14, laptop mode: picture on top, keys, trackpad.
            Column(Modifier.fillMaxSize().background(C.Ink).statusBarsPadding()) {
                Box(Modifier.fillMaxWidth().weight(0.46f), contentAlignment = Alignment.Center) {
                    Picture(client, prefs, macName, refreshRate, onToggleBar = {}, cursor = cursor)
                }
                CompactBar(client, actions, rtt, link)
                KeyRow(client)
                Trackpad(client, prefs, Modifier.weight(0.54f).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    start = cursor, onCursor = { cursor = it })
            }
        } else if (barStyle == "side") {
            // Mockup 7: a rail on the left, the picture beside it.
            Row(Modifier.fillMaxSize()) {
                SideRail(client, actions, rtt, link)
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Picture(client, prefs, macName, refreshRate, onToggleBar = {}, cursor = null)
                }
            }
        } else {
            // Mockups 5 and 6: the floating bar, or just a handle.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Picture(client, prefs, macName, refreshRate, onToggleBar = { barVisible = !barVisible }, cursor = null)
            }
            AnimatedVisibility(barVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Box(Modifier.statusBarsPadding().padding(top = 10.dp).pointerInput(Unit) {
                    awaitPointerEventScope { while (true) { awaitPointerEvent(); poke++ } }
                }) { FloatingBar(client, actions, rtt, link, panel) }
            }
            AnimatedVisibility(!barVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Box(Modifier.statusBarsPadding().width(140.dp).height(30.dp).clickable(role = Role.Button) { barVisible = true }
                    .semantics { contentDescription = "Show the bar" }, contentAlignment = Alignment.Center) {
                    Box(Modifier.width(64.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xB3ECEDEF)))
                }
            }
            // The link health dot: only while the bar is hidden and the link has slowed.
            val slow = (rtt ?: 0) > 80
            if (!barVisible && slow) Row(
                Modifier.align(Alignment.BottomEnd).padding(16.dp).clip(RoundedCornerShape(999.dp)).background(C.Bar).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { Dot(C.Amber); Text("$link is slow · $rtt ms", color = C.Text1, fontSize = 12.sp) }
        }

        // Panels over the picture (mockups 8–11, 13).
        when (panel) {
            Panel.SWITCHER -> SwitcherPanel(client, macName) { panel = Panel.NONE }
            Panel.MENUS -> MenuPanel(client, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 18.dp, top = 76.dp)) { panel = Panel.NONE }
            Panel.SETTINGS -> SettingsPanel(client, prefs, barStyle, onBarStyle = { barStyle = it; prefs.barStyle = it }) { panel = Panel.NONE }
            Panel.GUIDE -> GestureGuide { prefs.seenGuide = true; panel = Panel.NONE }
            Panel.NONE -> {}
        }
        ClipboardLayer(client, macName, Modifier.align(Alignment.BottomCenter)) { note = it }
        if (typing) TypeBox(client, Modifier.align(Alignment.BottomCenter))
        note?.let {
            Text(it, color = C.Text1, fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
                .clip(RoundedCornerShape(14.dp)).background(C.Bar).padding(horizontal = 18.dp, vertical = 10.dp))
        }
        reconnecting?.let { ReconnectingPanel(client, it) }
    }
}

/** The decoded picture: a SurfaceView fed by the hardware decoder, touch → Sill input, a snapshot now and then. */
@Composable
private fun Picture(client: SillClient, prefs: Prefs, macName: String, refreshRate: Int, onToggleBar: () -> Unit, cursor: Pair<Float, Float>?) {
    val size by client.videoSize.collectAsState()
    val density = LocalDensity.current
    var view by remember { mutableStateOf<SurfaceView?>(null) }
    val ratio = if (size.first > 0 && size.second > 0) size.first.toFloat() / size.second else 16f / 10f

    // Keep a recent picture for this Mac's card on the connect screen.
    LaunchedEffect(view, macName) {
        val sv = view ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(15_000)
            if (sv.width <= 0 || !sv.holder.surface.isValid) continue
            val bmp = Bitmap.createBitmap(maxOf(1, sv.width / 4), maxOf(1, sv.height / 4), Bitmap.Config.ARGB_8888)
            runCatching {
                PixelCopy.request(sv, bmp, { r -> if (r == PixelCopy.SUCCESS) Thread { Snapshots.save(macName, bmp) }.start() }, Handler(Looper.getMainLooper()))
            }
        }
    }

    BoxWithConstraints(Modifier.aspectRatio(ratio).onSizeChanged { px ->
        with(density) { client.setViewport(px.width.toDp().value.toDouble(), px.height.toDp().value.toDouble(), refreshRate) }
    }) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            if (android.os.Build.VERSION.SDK_INT >= 30) runCatching {
                                h.surface.setFrameRate(refreshRate.toFloat(), android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                            }
                            client.decoder.setSurface(h.surface)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { client.decoder.setSurface(null) }
                    })
                    val touch = TouchTranslator(client, prefs, onToggleBar)
                    setOnTouchListener { v, e -> touch.onTouch(e, v.width.toFloat(), v.height.toFloat()) }
                    setOnGenericMotionListener { v, e -> touch.onGeneric(e, v.width.toFloat(), v.height.toFloat()) }
                    view = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // The Mac's own cursor: the Mac leaves it out of the video and sends its shape (kind 14) and,
        // when its own mouse moves it, its place (kind 26); this tablet's input moves it too.
        val at by client.pointerAt.collectAsState()
        val shape by client.cursorShape.collectAsState()
        val p = at
        if (p != null) {
            val x = with(density) { (maxWidth.toPx() * p.first).roundToInt() }
            val y = with(density) { (maxHeight.toPx() * p.second).roundToInt() }
            val s = shape
            if (s != null) {
                val hx = with(density) { s.hotX.dp.roundToPx() }; val hy = with(density) { s.hotY.dp.roundToPx() }
                Image(s.image.asImageBitmap(), null, Modifier.offset { IntOffset(x - hx, y - hy) }.size(s.w.dp, s.h.dp))
            } else Icon(Icons.Rounded.NearMe, null, tint = Color.White,
                modifier = Modifier.offset { IntOffset(x - 6, y - 6) }.size(22.dp).rotate(-90f))
        }
    }
}

@Composable
private fun ConnChip(rtt: Int?, link: String) {
    val lit = link != "Wi-Fi" // USB and Away stand out
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(if (lit) C.AmberWash else C.Raised).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(when (link) { "USB" -> Icons.Rounded.Usb; "Away" -> Icons.Rounded.Public; else -> Icons.Rounded.Wifi }, null,
            tint = if (lit) C.AmberSoft else C.Text2, modifier = Modifier.size(14.dp))
        Text(link + (rtt?.let { " · $it ms" } ?: ""), color = if (lit) C.AmberSoft else C.Text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WindowChip(client: SillClient, w: MacWindow, active: Boolean, compact: Boolean = false) {
    val thumbs by client.thumbnails.collectAsState()
    val thumb = thumbs[w.id]
    Row(
        Modifier.height(44.dp).widthIn(max = if (compact) 140.dp else 180.dp).clip(RoundedCornerShape(14.dp))
            .background(if (active) C.AmberWash else Color.Transparent)
            .border(1.dp, if (active) C.Amber else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { client.selectWindow(w.id) }.padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(44.dp).height(30.dp).clip(RoundedCornerShape(6.dp)).background(C.Raised)) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(w.appName.ifEmpty { w.title }, color = C.Text1, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DesktopButton(client: SillClient, active: Boolean) {
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).background(if (active) C.AmberWash else C.Raised)
            .border(1.dp, if (active) C.Amber else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { client.selectDesktop() }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.DesktopMac, null, tint = if (active) C.AmberSoft else C.Text1, modifier = Modifier.size(18.dp))
        Text("Desktop", color = if (active) C.AmberSoft else C.Text1, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Mockup 5. */
@Composable
private fun FloatingBar(client: SillClient, a: BarActions, rtt: Int?, link: String, panel: Panel) {
    val windows by client.windows.collectAsState()
    val active by client.activeWindow.collectAsState()
    val menuBar by client.menuBar.collectAsState()
    val clipOn by client.clipboardAvailable.collectAsState()
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(C.Bar).border(1.dp, C.Line, RoundedCornerShape(20.dp)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DesktopButton(client, active == null)
        LazyRow(Modifier.widthIn(max = 400.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(windows, key = { it.id }) { w -> WindowChip(client, w, active == w.id) }
        }
        IconBtn(Icons.Rounded.GridView, "All windows and apps", bg = if (panel == Panel.SWITCHER) C.AmberWash else Color.Transparent) { a.open(Panel.SWITCHER) }
        Box(Modifier.width(1.dp).height(26.dp).background(C.Line))
        if (menuBar?.menus?.isNotEmpty() == true) Row(
            Modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).background(if (panel == Panel.MENUS) C.AmberWash else Color.Transparent)
                .clickable(role = Role.Button) { client.closeMenus(); a.open(Panel.MENUS) }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text((menuBar?.app?.ifEmpty { null } ?: "Menus") + " ▾", color = if (panel == Panel.MENUS) C.AmberSoft else C.Text1, fontSize = 14.sp) }
        if (clipOn) PasteButton(client, a)
        IconBtn(Icons.Rounded.Keyboard, "Keyboard") { a.typing() }
        ConnChip(rtt, link)
        IconBtn(Icons.Rounded.ExpandLess, "Hide the bar", tint = C.Text2) { a.hide() }
        IconBtn(Icons.Rounded.MoreHoriz, "Settings", bg = if (panel == Panel.SETTINGS) C.AmberWash else Color.Transparent) { a.open(Panel.SETTINGS) }
        IconBtn(Icons.Rounded.PowerSettingsNew, "Disconnect", tint = C.Text2) { a.disconnect() }
    }
}

@Composable
private fun PasteButton(client: SillClient, a: BarActions) {
    val ctx = LocalContext.current
    IconBtn(Icons.Rounded.ContentPaste, "Paste on Mac") {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (text.isNullOrEmpty()) a.note("Nothing to paste: copy some text on this tablet first.") else { client.pasteOnMac(text); a.note("Pasted on the Mac") }
    }
}

/** Mockup 7. */
@Composable
private fun SideRail(client: SillClient, a: BarActions, rtt: Int?, link: String) {
    val windows by client.windows.collectAsState()
    val active by client.activeWindow.collectAsState()
    val thumbs by client.thumbnails.collectAsState()
    val clipOn by client.clipboardAvailable.collectAsState()
    Column(
        Modifier.width(88.dp).fillMaxHeight().background(C.Card2).statusBarsPadding().padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconBtn(Icons.Rounded.DesktopMac, "Desktop", tint = if (active == null) C.AmberSoft else C.Text1,
            bg = if (active == null) C.AmberWash else C.Raised, size = 52.dp) { client.selectDesktop() }
        Box(Modifier.width(36.dp).height(1.dp).background(C.Line))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            items(windows, key = { it.id }) { w ->
                val on = active == w.id
                Column(
                    Modifier.width(66.dp).clip(RoundedCornerShape(14.dp)).background(if (on) C.AmberWash else Color.Transparent)
                        .border(1.dp, if (on) C.Amber else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button) { client.selectWindow(w.id) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.width(50.dp).height(34.dp).clip(RoundedCornerShape(6.dp)).background(C.Raised)) {
                        thumbs[w.id]?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    Text(w.appName.ifEmpty { w.title }, color = C.Text1, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        IconBtn(Icons.Rounded.GridView, "All windows and apps") { a.open(Panel.SWITCHER) }
        IconBtn(Icons.Rounded.Menu, "Mac menus") { client.closeMenus(); a.open(Panel.MENUS) }
        if (clipOn) PasteButton(client, a)
        IconBtn(Icons.Rounded.Keyboard, "Keyboard") { a.typing() }
        IconBtn(Icons.Rounded.MoreHoriz, "Settings") { a.open(Panel.SETTINGS) }
        Text(link + (rtt?.let { "\n$it ms" } ?: ""), color = if (link != "Wi-Fi") C.AmberSoft else C.Text2,
            fontSize = 10.sp, fontWeight = FontWeight.SemiBold, lineHeight = 12.sp)
        IconBtn(Icons.Rounded.PowerSettingsNew, "Disconnect", tint = C.Text2) { a.disconnect() }
    }
}

/** Laptop mode's bar: windows on the left, panels on the right. */
@Composable
private fun CompactBar(client: SillClient, a: BarActions, rtt: Int?, link: String) {
    val windows by client.windows.collectAsState()
    val active by client.activeWindow.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IconBtn(Icons.Rounded.DesktopMac, "Desktop", tint = if (active == null) C.AmberSoft else C.Text1,
            bg = if (active == null) C.AmberWash else C.Raised) { client.selectDesktop() }
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(windows, key = { it.id }) { w -> WindowChip(client, w, active == w.id, compact = true) }
        }
        IconBtn(Icons.Rounded.GridView, "All windows and apps") { a.open(Panel.SWITCHER) }
        IconBtn(Icons.Rounded.Menu, "Mac menus") { client.closeMenus(); a.open(Panel.MENUS) }
        IconBtn(Icons.Rounded.Keyboard, "Keyboard") { a.typing() }
        IconBtn(Icons.Rounded.MoreHoriz, "Settings") { a.open(Panel.SETTINGS) }
        ConnChip(rtt, link)
    }
}

@Composable
private fun KeyRow(client: SillClient) {
    // esc, tab, ⌃, ⌥, ⌘ (held for the next key), ←, →, ⏎
    var held by remember { mutableStateOf(0L) }
    val keys = listOf(
        Triple("esc", "Escape", 0x29), Triple("⇥", "Tab", 0x2B), Triple("⌃", "Control", -1), Triple("⌥", "Option", -2),
        Triple("⌘", "Command", -3), Triple("←", "Left arrow", 0x50), Triple("→", "Right arrow", 0x4F), Triple("⏎", "Return", 0x28),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { (label, name, code) ->
            val mod = when (code) { -1 -> Keys.CONTROL; -2 -> Keys.OPTION; -3 -> Keys.COMMAND; else -> 0L }
            val on = mod != 0L && held and mod != 0L
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(12.dp)).background(if (on) C.AmberWash else Color(0xFF202228))
                    .border(1.dp, if (on) C.Amber else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) {
                        if (mod != 0L) held = held xor mod
                        else { client.key(code, true, held); client.key(code, false, held); held = 0L }
                    }.semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) C.AmberSoft else C.Text1, fontSize = if (label.length > 1) 14.sp else 18.sp) }
        }
    }
}

/**
 * Laptop mode's trackpad: one finger moves the Mac's pointer, a tap clicks where it is, two fingers
 * scroll, hold then move drags; the two buttons click and right-click.
 */
@Composable
private fun Trackpad(client: SillClient, prefs: Prefs, modifier: Modifier, start: Pair<Float, Float>, onCursor: (Pair<Float, Float>) -> Unit) {
    var pos by remember { mutableStateOf(start) }
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(22.dp))) {
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                val speed = 1.6f
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        val first = down.changes.firstOrNull { it.pressed } ?: continue
                        val t0 = first.uptimeMillis
                        client.pointerAt.value?.let { pos = it } // continue from where the Mac's pointer is now
                        var moved = 0f; var dragging = false; var maxFingers = 1; var scrolling = false
                        var last = first.position
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            maxFingers = maxOf(maxFingers, pressed.size)
                            val p = pressed.first().position
                            val dx = p.x - last.x; val dy = p.y - last.y
                            last = p
                            moved += hypot(dx, dy)
                            if (pressed.size >= 2) {
                                if (!scrolling) { client.scrollPhase("began", pos.first.toDouble(), pos.second.toDouble()); scrolling = true }
                                val s = if (prefs.reverseScroll) -1f else 1f
                                client.scroll(pos.first.toDouble(), pos.second.toDouble(), (s * dx / size.width).toDouble(), (s * dy / size.height).toDouble())
                            } else if (!scrolling) {
                                if (!dragging && moved > 10f && ev.changes.first().uptimeMillis - t0 > 400) {
                                    dragging = true; client.pointer("leftDown", pos.first.toDouble(), pos.second.toDouble())
                                }
                                pos = ((pos.first + speed * dx / size.width).coerceIn(0f, 1f)) to ((pos.second + speed * dy / size.height).coerceIn(0f, 1f))
                                onCursor(pos)
                                client.pointer("move", pos.first.toDouble(), pos.second.toDouble())
                            }
                            ev.changes.forEach { it.consume() }
                        }
                        val x = pos.first.toDouble(); val y = pos.second.toDouble()
                        when {
                            scrolling -> client.scrollPhase("ended", x, y)
                            dragging -> client.pointer("leftUp", x, y)
                            moved < 10f && maxFingers == 2 -> { client.pointer("rightDown", x, y); client.pointer("rightUp", x, y) }
                            moved < 10f -> { client.pointer("leftDown", x, y); client.pointer("leftUp", x, y) }
                        }
                    }
                }
            }.semantics { contentDescription = "Trackpad" },
            contentAlignment = Alignment.Center,
        ) { Text("Trackpad · one finger moves, tap clicks, two scroll", color = C.Muted, fontSize = 14.sp) }
        Row(Modifier.fillMaxWidth().height(64.dp).border(1.dp, C.Line)) {
            listOf("Click" to ("leftDown" to "leftUp"), "Right-click" to ("rightDown" to "rightUp")).forEach { (label, act) ->
                Box(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button) {
                    client.pointer(act.first, pos.first.toDouble(), pos.second.toDouble()); client.pointer(act.second, pos.first.toDouble(), pos.second.toDouble())
                }, contentAlignment = Alignment.Center) { Text(label, color = C.Text2, fontSize = 14.sp) }
            }
        }
    }
}

/** The on-screen keyboard: what you type goes to the Mac as text; Delete and Return as keys. */
@Composable
private fun TypeBox(client: SillClient, modifier: Modifier) {
    var value by remember { mutableStateOf(" ") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { new ->
            when {
                new.length < value.length -> client.text("\u0008")
                new.length > value.length -> client.text(new.substring(value.length))
            }
            value = " "
        },
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        modifier = modifier.fillMaxWidth().height(1.dp).focusRequester(focus),
    )
}

/** Mockup 12: the last picture stays, dimmed, with what Sill knows about the path. */
@Composable
private fun ReconnectingPanel(client: SillClient, r: ClientState.Reconnecting) {
    val ctx = LocalContext.current
    val usb by UsbLink.status.collectAsState()
    var check by remember { mutableStateOf<PathCheck.Result?>(null) }
    LaunchedEffect(r.attempt) {
        check = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PathCheck.run(ctx, r.mac.host) }
    }
    Box(Modifier.fillMaxSize().background(Color(0x9E08090A)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Sheet(Modifier.width(540.dp)) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(44.dp), color = C.Amber, strokeWidth = 4.dp, trackColor = C.Line)
                    Column {
                        Text("Reconnecting to ${r.mac.name}", color = C.Text1, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text("Try ${r.attempt} · gives up after 3 minutes", color = C.Text2, fontSize = 13.sp)
                    }
                }
                Text(r.why, color = C.Text1, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card2).padding(14.dp))
                if (r.mac.host != UsbLink.HOST) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val c = check
                    PathRow("This tablet on Wi-Fi", c?.onWifi, if (c == null) "checking…" else if (c.onWifi) "yes" else "no")
                    PathRow("Router answering", c?.let { it.routerMs != null }, c?.routerMs?.let { "$it ms" } ?: if (c == null) "checking…" else "no reply")
                    PathRow("${r.mac.name} answering", c?.macReachable, when (c?.macReachable) { true -> "yes"; false -> "no reply"; null -> "checking…" })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (usb.tunnel && r.mac.host != UsbLink.HOST) PrimaryButton("Use the USB cable", Modifier.weight(1f)) { client.connectUsb() }
                    SecondaryButton("Try now") { client.onForeground() }
                    SecondaryButton("Stop") { client.disconnect() }
                }
            }
        }
    }
}

@Composable
private fun PathRow(label: String, ok: Boolean?, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Dot(if (ok == true) C.Amber else C.Muted)
        Text(label, color = C.Text1, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = C.Text2, fontSize = 14.sp)
    }
}

/**
 * Touch, stylus and mouse on the picture → Sill input, in fractions of the picture:
 * tap = click · hold = right-click · drag = scroll · hold then drag = drag · two fingers scroll ·
 * three or four fingers = the Mac's trackpad gestures (a three-finger tap shows or hides the bar) ·
 * stylus/mouse hover moves the pointer · mouse buttons and wheel map directly.
 */
private class TouchTranslator(private val client: SillClient, private val prefs: Prefs, private val toggleBar: () -> Unit) {
    private val holdMs = 350L
    private val slop = 12f
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var downAt = 0L
    private var mode = Mode.NONE
    private var maxPointers = 0
    private var mouseRight = false
    private enum class Mode { NONE, PENDING, SCROLL, DRAG, MOUSE, MULTI }
    private var gx = 0f; private var gy = 0f; private var gSpread = 0f
    private var gFired = false; private var gMoved = false
    private val sign get() = if (prefs.reverseScroll) -1f else 1f

    private fun centroid(e: MotionEvent): Pair<Float, Float> {
        var x = 0f; var y = 0f
        for (i in 0 until e.pointerCount) { x += e.getX(i); y += e.getY(i) }
        return x / e.pointerCount to y / e.pointerCount
    }

    private fun spread(e: MotionEvent, cx: Float, cy: Float): Float {
        var d = 0f
        for (i in 0 until e.pointerCount) d += hypot(e.getX(i) - cx, e.getY(i) - cy)
        return d / e.pointerCount
    }

    private fun beginMulti(e: MotionEvent, fx: Double, fy: Double) {
        when (mode) {
            Mode.SCROLL -> client.scrollPhase("ended", fx, fy)
            Mode.DRAG -> client.pointer("leftUp", fx, fy)
            else -> {}
        }
        mode = Mode.MULTI
        val (cx, cy) = centroid(e)
        gx = cx; gy = cy; gSpread = spread(e, cx, cy)
    }

    private fun trackMulti(e: MotionEvent, w: Float) {
        if (gFired) return
        val (cx, cy) = centroid(e)
        val sp = spread(e, cx, cy)
        val dx = cx - gx; val dy = cy - gy
        if (hypot(dx, dy) > slop * 2 || abs(sp - gSpread) > slop * 2) gMoved = true
        val name = when {
            gSpread > 0f && sp / gSpread < 0.65f -> "pinch"
            gSpread > 0f && sp / gSpread > 1.5f -> "spread"
            hypot(dx, dy) > w * 0.08f -> if (abs(dx) > abs(dy)) (if (dx < 0) "swipeLeft" else "swipeRight") else (if (dy < 0) "swipeUp" else "swipeDown")
            else -> null
        }
        if (name != null) { gFired = true; if (prefs.gestures) client.gesture(name, maxPointers.coerceIn(3, 4)) }
    }

    fun onTouch(e: MotionEvent, w: Float, h: Float): Boolean {
        if (w <= 0f || h <= 0f) return false
        val fx = (e.x / w).toDouble().coerceIn(0.0, 1.0)
        val fy = (e.y / h).toDouble().coerceIn(0.0, 1.0)
        val isMouse = e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; downAt = e.eventTime; maxPointers = 1
                gFired = false; gMoved = false
                if (isMouse) {
                    mode = Mode.MOUSE
                    mouseRight = e.buttonState and MotionEvent.BUTTON_SECONDARY != 0
                    client.pointer(if (mouseRight) "rightDown" else "leftDown", fx, fy)
                } else mode = Mode.PENDING
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                if (!isMouse && e.pointerCount >= 3) beginMulti(e, fx, fy)
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.MOUSE, Mode.DRAG -> client.pointer("move", fx, fy)
                Mode.MULTI -> trackMulti(e, w)
                Mode.PENDING -> if (hypot(e.x - downX, e.y - downY) > slop) {
                    if (e.pointerCount == 1 && e.eventTime - downAt >= holdMs) {
                        mode = Mode.DRAG
                        client.pointer("leftDown", (downX / w).toDouble(), (downY / h).toDouble())
                        client.pointer("move", fx, fy)
                    } else {
                        mode = Mode.SCROLL
                        client.scrollPhase("began", fx, fy)
                    }
                    lastX = e.x; lastY = e.y
                }
                Mode.SCROLL -> {
                    val dx = sign * (e.x - lastX) / w; val dy = sign * (e.y - lastY) / h
                    if (abs(dx) > 0f || abs(dy) > 0f) client.scroll(fx, fy, dx.toDouble(), dy.toDouble())
                    lastX = e.x; lastY = e.y
                }
                Mode.NONE -> {}
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.MOUSE -> client.pointer(if (mouseRight) "rightUp" else "leftUp", fx, fy)
                    Mode.DRAG -> client.pointer("leftUp", fx, fy)
                    Mode.SCROLL -> client.scrollPhase("ended", fx, fy)
                    Mode.MULTI -> if (!gFired && !gMoved && e.eventTime - downAt < 400) toggleBar()
                    Mode.PENDING -> {
                        val held = e.eventTime - downAt >= holdMs
                        val dfx = (downX / w).toDouble(); val dfy = (downY / h).toDouble()
                        if (held || maxPointers == 2) { client.pointer("rightDown", dfx, dfy); client.pointer("rightUp", dfx, dfy) }
                        else { client.pointer("leftDown", dfx, dfy); client.pointer("leftUp", dfx, dfy) }
                    }
                    Mode.NONE -> {}
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAG) client.pointer("leftUp", fx, fy)
                if (mode == Mode.SCROLL) client.scrollPhase("ended", fx, fy)
                mode = Mode.NONE
            }
        }
        return true
    }

    fun onGeneric(e: MotionEvent, w: Float, h: Float): Boolean {
        if (w <= 0f || h <= 0f) return false
        val fx = (e.x / w).toDouble().coerceIn(0.0, 1.0)
        val fy = (e.y / h).toDouble().coerceIn(0.0, 1.0)
        return when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> { client.pointer("move", fx, fy); true }
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL); val hz = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                client.scroll(fx, fy, (sign * -hz * 0.03).toDouble(), (sign * v * 0.03).toDouble()); true
            }
            else -> false
        }
    }
}
