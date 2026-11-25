package dev.sillport.matepad

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sill's colours on the MatePad: dark ink, one amber accent (the mockups' palette). */
object C {
    val Ink = Color(0xFF0E0F11)
    val Deep = Color(0xFF08090A)
    val Card = Color(0xFF181A1E)
    val Card2 = Color(0xFF141518)
    val Raised = Color(0xFF24262B)
    val Line = Color(0xFF2A2D33)
    val Text1 = Color(0xFFECEDEF)
    val Text2 = Color(0xFF9BA0A8)
    val Muted = Color(0xFF5A606A)
    val Amber = Color(0xFFE8A33D)
    val AmberSoft = Color(0xFFF4C27A)
    val AmberWash = Color(0xFF2A2418)
    val AmberLine = Color(0xFF3A2F1C)
    val Bar = Color(0xEB151619)
}

/** The tablet's own choices, kept across launches. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var preferUsb: Boolean get() = p.getBoolean("preferUsb", true); set(v) = p.edit().putBoolean("preferUsb", v).apply()
    var autoReconnect: Boolean get() = p.getBoolean("autoReconnect", true); set(v) = p.edit().putBoolean("autoReconnect", v).apply()
    var gestures: Boolean get() = p.getBoolean("gestures", true); set(v) = p.edit().putBoolean("gestures", v).apply()
    var reverseScroll: Boolean get() = p.getBoolean("reverseScroll", false); set(v) = p.edit().putBoolean("reverseScroll", v).apply()
    var autoHideBar: Boolean get() = p.getBoolean("autoHideBar", false); set(v) = p.edit().putBoolean("autoHideBar", v).apply()
    /** "top" (floating bar) or "side" (rail). */
    var barStyle: String get() = p.getString("barStyle", "top") ?: "top"; set(v) = p.edit().putString("barStyle", v).apply()
    var seenGuide: Boolean get() = p.getBoolean("seenGuide", false); set(v) = p.edit().putBoolean("seenGuide", v).apply()
    /** Aa, as a slider position: 0 = leave the Mac window alone, else 0.5…1.5. */
    var textScale: Float get() = p.getFloat("textScale", 0f); set(v) = p.edit().putFloat("textScale", v).apply()
    var fps60: Boolean get() = p.getBoolean("fps60", false); set(v) = p.edit().putBoolean("fps60", v).apply()

    fun lastUsed(mac: String): Long = p.getLong("lastUsed:$mac", 0L)
    fun markUsed(mac: String) = p.edit().putLong("lastUsed:$mac", System.currentTimeMillis()).apply()
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 48.dp, onClick: () -> Unit) {
    Box(
        modifier.height(height).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) C.Amber else Color(0xFF3A3324))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color(0xFF151619) else Color(0xFFB8A27A), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, height: Dp = 48.dp, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier.height(height).clip(RoundedCornerShape(14.dp)).background(C.Raised)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = C.Text1, modifier = Modifier.size(18.dp))
        Text(text, color = C.Text1, fontSize = 15.sp)
    }
}

/** A 44 dp square icon button (the touch target the mockups use), labelled for TalkBack. */
@Composable
fun IconBtn(icon: ImageVector, label: String, tint: Color = C.Text1, bg: Color = Color.Transparent, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(bg)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
}

@Composable
fun Chip(text: String, amber: Boolean = false, icon: ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (amber) C.AmberWash else C.Card)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (amber) C.AmberSoft else C.Text2, modifier = Modifier.size(14.dp))
        Text(text, color = if (amber) C.AmberSoft else C.Text2, fontSize = 12.sp, fontWeight = if (amber) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun SectionLabel(text: String) = Text(text.uppercase(), color = C.Text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)

@Composable
fun Switch(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.width(50.dp).height(30.dp).clip(RoundedCornerShape(999.dp)).background(if (on) C.Amber else C.Line)
            .clickable(role = Role.Switch) { onChange(!on) }.semantics { contentDescription = label }.padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(24.dp).clip(CircleShape).background(C.Text1)) }
}

/** A row of choices, one picked (Quality, Frame rate, Bar). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T?, onPick: (T) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(C.Ink).padding(3.dp)) {
        options.forEach { (v, label) ->
            val on = v == selected
            Box(
                Modifier.height(36.dp).defaultMinSize(minWidth = 52.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (on) C.Amber else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onPick(v) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) Color(0xFF151619) else C.Text2, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium) }
        }
    }
}

@Composable
fun Sheet(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(24.dp))) { content() }
}

@Composable
fun RowScope.Grow() = Box(Modifier.weight(1f))

@Composable
fun ColumnSpacer(h: Dp) = Box(Modifier.height(h))

/** "today, 4:53 PM", "yesterday", "Nov 22". */
fun lastUsedText(ms: Long): String? {
    if (ms <= 0) return null
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(then.time)
    val days = ((now.timeInMillis - ms) / 86_400_000L).toInt()
    return when {
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR) && days < 1 -> "today, $time"
        days < 2 -> "yesterday, $time"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(then.time)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun Stack(gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) { content() }
