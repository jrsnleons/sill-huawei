package dev.sillport.matepad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun Backdrop(onClose: () -> Unit, alpha: Long = 0xCC, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color((alpha shl 24) or 0x08090AL)).clickable(
        indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = onClose,
    )) { content() }
}

/** Mockup 8: every window as a picture, hold one for its actions; the Mac's apps, searchable, to open. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwitcherPanel(client: SillClient, macName: String, onClose: () -> Unit) {
    val windows by client.windows.collectAsState()
    val active by client.activeWindow.collectAsState()
    val thumbs by client.thumbnails.collectAsState()
    val apps by client.apps.collectAsState()
    val icons by client.icons.collectAsState()
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    val openBundles = windows.map { it.bundleID }.toSet()

    Backdrop(onClose, 0xF2) {
        Row(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 36.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f).clickable(enabled = false) {}, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Windows on $macName", color = C.Text1, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconBtn(Icons.Rounded.Close, "Close", bg = C.Raised, onClick = onClose)
                }
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(windows, key = { it.id }) { w ->
                        Box {
                            Column(
                                Modifier.clip(RoundedCornerShape(18.dp)).background(C.Card)
                                    .border(2.dp, if (active == w.id) C.Amber else Color.Transparent, RoundedCornerShape(18.dp))
                                    .combinedClickable(onClick = { client.selectWindow(w.id); onClose() }, onLongClick = { menuFor = w.id })
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(10.dp)).background(C.Raised)) {
                                    thumbs[w.id]?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    icons[w.bundleID]?.let { Image(it.asImageBitmap(), null, Modifier.size(20.dp)) }
                                    Column {
                                        Text(w.appName, color = C.Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(w.title.ifEmpty { " " }, color = C.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (menuFor == w.id) Column(
                                Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(C.Raised).border(1.dp, C.Line, RoundedCornerShape(14.dp)).padding(6.dp),
                            ) {
                                listOf("Close" to "close", "Minimize" to "minimize", "Full Screen" to "fullScreen").forEach { (label, action) ->
                                    Text(label, color = C.Text1, fontSize = 15.sp, modifier = Modifier.width(160.dp).clip(RoundedCornerShape(10.dp))
                                        .clickable(role = Role.Button) { client.windowCommand(w.id, action); menuFor = null }.padding(horizontal = 14.dp, vertical = 12.dp))
                                }
                            }
                        }
                    }
                }
                Text("Hold a window for Close, Minimize and Full Screen.", color = C.Text2, fontSize = 13.sp)
            }
            // All apps
            Column(
                Modifier.width(290.dp).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(C.Card).clickable(enabled = false) {}.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SearchField(query, "Open an app…") { query = it }
                SectionLabel("All apps")
                val shown = apps.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown, key = { it.bundleID }) { a ->
                        Row(
                            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { client.launchApp(a.bundleID); onClose() }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(C.Raised)) {
                                icons[a.bundleID]?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                            }
                            Text(a.name, color = C.Text1, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (a.bundleID in openBundles) Text("open", color = C.AmberSoft, fontSize = 11.sp)
                        }
                    }
                }
                if (apps.isEmpty()) Text("The Mac's app list arrives a moment after connecting.", color = C.Text2, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(C.Ink).border(1.dp, C.Line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = C.Text2, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = C.Muted, fontSize = 15.sp)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = C.Text1, fontSize = 15.sp), cursorBrush = SolidColor(C.Amber), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Mockup 9: the app's menus on the left, one menu's items on the right; search reads every menu. */
@Composable
fun MenuPanel(client: SillClient, modifier: Modifier, onClose: () -> Unit) {
    val bar by client.menuBar.collectAsState()
    val pages by client.menuPages.collectAsState()
    val cache by client.menuCache.collectAsState()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(query.isNotBlank()) { if (query.isNotBlank()) client.prefetchMenus() }
    val b = bar ?: return
    Backdrop(onClose, 0x40) {
        Row(modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF1A1C20)).border(1.dp, C.Line, RoundedCornerShape(20.dp)).clickable(enabled = false) {}) {
            Column(Modifier.width(240.dp).heightIn(max = 560.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SearchField(query, "Search every menu") { query = it }
                Box(Modifier.height(6.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    b.menus.filter { !it.separator }.forEach { m ->
                        val on = pages.firstOrNull()?.id == m.id && query.isBlank()
                        MenuRow(m.title, null, null, m.enabled, chevron = true, selected = on) { query = ""; client.openMenu(m, fromTop = true) }
                    }
                }
            }
            val page = pages.lastOrNull()
            if (query.isNotBlank() || page != null) Column(Modifier.width(340.dp).heightIn(max = 560.dp).background(C.Card2).padding(10.dp)) {
                if (query.isNotBlank()) {
                    val hits = cache.values.flatMap { (menu, items) -> items.filter { !it.separator && !it.submenu && it.id != null && it.title.contains(query, true) }.map { menu to it } }
                    Text(if (hits.isEmpty()) "Searching…" else "${hits.size} found", color = C.Text2, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                    LazyColumn { items(hits) { (menu, it) -> MenuRow(it.title, it.mark, it.key, it.enabled, chevron = false, sub = menu) { client.pressMenuItem(it); onClose() } } }
                } else if (page != null) {
                    Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).clickable { if (pages.size > 1) client.menuBack() else client.closeMenus() }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (pages.size > 1) Icon(Icons.Rounded.ChevronLeft, "Back", tint = C.AmberSoft)
                        SectionLabel(page.title)
                    }
                    val items = page.items
                    when {
                        items == null -> Text("Reading…", color = C.Text2, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                        items.isEmpty() -> Text(page.note ?: "Nothing in this menu.", color = C.Text2, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                        else -> LazyColumn {
                            items(items) { it ->
                                if (it.separator) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).height(1.dp).background(C.Line))
                                else MenuRow(it.title, it.mark, it.key, it.enabled, chevron = it.submenu) {
                                    if (it.submenu) client.openMenu(it, fromTop = false) else { client.pressMenuItem(it); onClose() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(title: String, mark: String?, key: String?, enabled: Boolean, chevron: Boolean, selected: Boolean = false, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(if (sub != null) 52.dp else 44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) C.AmberWash else Color.Transparent).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(mark ?: "", color = C.Amber, fontSize = 14.sp, modifier = Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (!enabled) Color(0xFF6B7078) else if (selected) C.AmberSoft else C.Text1, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = C.Text2, fontSize = 11.sp)
        }
        if (key != null) Text(key, color = C.Text2, fontSize = 13.sp)
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = C.Text2, modifier = Modifier.size(18.dp))
    }
}

/** Mockup 10. */
@Composable
fun SettingsPanel(client: SillClient, prefs: Prefs, barStyle: String, onBarStyle: (String) -> Unit, onClose: () -> Unit) {
    val q by client.quality.collectAsState()
    var fps60 by remember { mutableStateOf(prefs.fps60) }
    var text by remember { mutableStateOf(prefs.textScale) }
    var preferUsb by remember { mutableStateOf(prefs.preferUsb) }
    var reconnect by remember { mutableStateOf(prefs.autoReconnect) }
    var gestures by remember { mutableStateOf(prefs.gestures) }
    var reverse by remember { mutableStateOf(prefs.reverseScroll) }
    var autoHide by remember { mutableStateOf(prefs.autoHideBar) }
    Backdrop(onClose, 0x80) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.TopEnd) {
            Sheet(Modifier.width(460.dp).fillMaxHeight().clickable(enabled = false) {}) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Settings", color = C.Text1, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconBtn(Icons.Rounded.Close, "Close", bg = C.Raised, onClick = onClose)
                    }
                    SectionLabel("Picture")
                    SettingRow("Quality") {
                        Segmented(listOf(4_000_000 to "Low", 15_000_000 to "Balanced", 25_000_000 to "High", 40_000_000 to "Pro"), q?.bitrate) { client.changeSettings(bitrate = it) }
                    }
                    SettingRow("Resolution") {
                        Segmented(listOf(1.0 to "Standard", 2.0 to "Retina"), q?.captureScale) { client.changeSettings(captureScale = it) }
                    }
                    SettingRow("Frame rate") {
                        Segmented(listOf(true to "60", false to "120"), fps60) { fps60 = it; prefs.fps60 = it; client.wantedFps = if (it) 60 else 120 }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row { Text("Text size on the Mac", color = C.Text1, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(if (text <= 0f) "Off" else "${(text * 100).toInt()}%", color = C.Text2, fontSize = 15.sp) }
                        Slider(text, { v -> text = if (v < 0.5f) 0f else (v * 20).toInt() / 20f },
                            onValueChangeFinished = { prefs.textScale = text; client.textScale = text.takeIf { it > 0f }?.toDouble() },
                            valueRange = 0.4f..1.5f,
                            colors = SliderDefaults.colors(thumbColor = C.Text1, activeTrackColor = C.Amber, inactiveTrackColor = C.Line))
                        Text("Resizes the window you stream so its text reads well here. Off leaves it alone.", color = C.Text2, fontSize = 12.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                    SectionLabel("Layout")
                    SettingRow("Bar") { Segmented(listOf("top" to "Top", "side" to "Side rail"), barStyle) { onBarStyle(it) } }
                    ToggleRow("Hide the bar after 3 s", "A handle at the top brings it back", autoHide) { autoHide = it; prefs.autoHideBar = it }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                    SectionLabel("Connection & controls")
                    ToggleRow("Prefer the USB cable", "Switch to it whenever it's plugged in", preferUsb) { preferUsb = it; prefs.preferUsb = it }
                    ToggleRow("Reconnect by itself", "After sleep or a Wi-Fi change", reconnect) { reconnect = it; prefs.autoReconnect = it; client.autoReconnect = it }
                    ToggleRow("Trackpad gestures", "Three and four fingers, as on a Mac", gestures) { gestures = it; prefs.gestures = it }
                    ToggleRow("Reverse scrolling", "Content moves against your finger", reverse) { reverse = it; prefs.reverseScroll = it }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Text1, fontSize = 15.sp, modifier = Modifier.weight(1f)); control()
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = C.Text1, fontSize = 15.sp); Text(hint, color = C.Text2, fontSize = 12.sp) }
        Switch(on, title, onChange)
    }
}

/**
 * Mockup 11: a copy on the Mac lands here with a note; a copy on this tablet offers itself as a
 * "Paste on <Mac>" chip until it's pasted or dismissed.
 */
@Composable
fun ClipboardLayer(client: SillClient, macName: String, modifier: Modifier, note: (String) -> Unit) {
    val ctx = LocalContext.current
    val available by client.clipboardAvailable.collectAsState()
    val cm = remember { ctx.getSystemService(android.content.ClipboardManager::class.java) }
    var offer by remember { mutableStateOf<String?>(null) }
    var fromMac by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(client) {
        client.macCopied.collect { text ->
            fromMac = text
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Mac", text))
            note("Copied from $macName · ready to paste on this tablet")
        }
    }
    DisposableEffect(cm) {
        val l = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
            if (!t.isNullOrBlank() && t != fromMac) offer = t
        }
        cm.addPrimaryClipChangedListener(l)
        onDispose { cm.removePrimaryClipChangedListener(l) }
    }
    val text = offer
    if (available && text != null) Row(
        modifier.padding(bottom = 28.dp).clip(RoundedCornerShape(18.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(18.dp)).padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Rounded.ContentPaste, null, tint = C.AmberSoft, modifier = Modifier.size(20.dp))
        Column(Modifier.width(300.dp)) {
            Text("Copied on this tablet", color = C.Text2, fontSize = 12.sp)
            Text("“${text.replace('\n', ' ')}”", color = C.Text1, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PrimaryButton("Paste on $macName", height = 44.dp) { client.pasteOnMac(text); offer = null; note("Pasted on $macName") }
        IconBtn(Icons.Rounded.Close, "Dismiss", tint = C.Text2) { offer = null }
    }
}

/** Mockup 13: shown the first time a picture shows; again from Settings later. */
@Composable
fun GestureGuide(onDone: () -> Unit) {
    data class Card(val title: String, val does: String, val dots: List<Offset>, val arrow: List<Pair<Offset, Offset>>)
    val three = { cx: Float, cy: Float -> listOf(Offset(cx - 22, cy), Offset(cx, cy - 6), Offset(cx + 22, cy)) }
    val cards = listOf(
        Card("Tap", "Click", listOf(Offset(60f, 45f)), emptyList()),
        Card("Hold", "Right-click. Hold, then drag, to drag", listOf(Offset(60f, 45f)), listOf(Offset(36f, 20f) to Offset(84f, 20f))),
        Card("Two fingers", "Scroll, with momentum", listOf(Offset(50f, 58f), Offset(70f, 58f)), listOf(Offset(60f, 40f) to Offset(60f, 12f))),
        Card("Three up", "Mission Control", three(60f, 64f), listOf(Offset(60f, 44f) to Offset(60f, 12f))),
        Card("Three down", "App Exposé", three(60f, 28f), listOf(Offset(60f, 48f) to Offset(60f, 80f))),
        Card("Three sideways", "Move between Spaces", three(60f, 40f), listOf(Offset(18f, 74f) to Offset(102f, 74f))),
        Card("Pinch three", "Apps · spread: Show Desktop", listOf(Offset(34f, 28f), Offset(86f, 28f), Offset(60f, 72f)), listOf(Offset(42f, 34f) to Offset(52f, 42f), Offset(78f, 34f) to Offset(68f, 42f))),
        Card("Three-finger tap", "Show or hide the bar", three(60f, 50f), emptyList()),
    )
    Box(Modifier.fillMaxSize().background(C.Ink).safeDrawingPadding().padding(horizontal = 44.dp, vertical = 30.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Your fingers, your Mac's trackpad", color = C.Text1, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text("The glass works like a Mac trackpad. Shown once; turn gestures off in Settings.", color = C.Text2, fontSize = 15.sp)
                }
                PrimaryButton("Got it", onClick = onDone)
            }
            LazyVerticalGrid(GridCells.Adaptive(220.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(cards) { c ->
                    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(C.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(14.dp)).background(C.Card2), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(120.dp, 90.dp)) {
                                val k = size.width / 120f
                                c.dots.forEach { drawCircle(C.Amber, 9f * k, Offset(it.x * k, it.y * k)) }
                                c.arrow.forEach { (a, b) -> drawLine(C.AmberSoft, Offset(a.x * k, a.y * k), Offset(b.x * k, b.y * k), 3f * k, StrokeCap.Round) }
                            }
                        }
                        Text(c.title, color = C.Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(c.does, color = C.Text2, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
