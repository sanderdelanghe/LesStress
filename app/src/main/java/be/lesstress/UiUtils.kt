package be.lesstress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalTime

/* =====================  SHARED UTILS, COLORS & UI  ===================== */

val GREEN = Color(0xFF1E7B3A)
val GREEN_BG = Color(0xFFDDF3E4)
val AMBER = Color(0xFF8A5A00)
val AMBER_BG = Color(0xFFFFEBC2)
val RED = Color(0xFFB3261E)
val RED_BG = Color(0xFFFADBD8)

val GREEN_DARK = Color(0xFF81C784)
val GREEN_BG_DARK = Color(0xFF14381E)
val AMBER_DARK = Color(0xFFFFB74D)
val AMBER_BG_DARK = Color(0xFF3E2800)
val RED_DARK = Color(0xFFE57373)
val RED_BG_DARK = Color(0xFF3E1A1A)

@Composable
fun isDarkTheme(): Boolean {
    val mode = AppState.themeMode
    return if (mode == "system") androidx.compose.foundation.isSystemInDarkTheme() else mode == "dark"
}

@Composable
fun statusFg(kind: Int): Color {
    val dark = isDarkTheme()
    return when (kind) {
        0 -> if (dark) GREEN_DARK else GREEN
        1 -> if (dark) AMBER_DARK else AMBER
        else -> if (dark) RED_DARK else RED
    }
}

@Composable
fun statusBg(kind: Int): Color {
    val dark = isDarkTheme()
    return when (kind) {
        0 -> if (dark) GREEN_BG_DARK else GREEN_BG
        1 -> if (dark) AMBER_BG_DARK else AMBER_BG
        else -> if (dark) RED_BG_DARK else RED_BG
    }
}

fun fg(kind: Int) = when (kind) { 0 -> GREEN; 1 -> AMBER; else -> RED }
fun bg(kind: Int) = when (kind) { 0 -> GREEN_BG; 1 -> AMBER_BG; else -> RED_BG }

data class Status(val kind: Int, val label: String, val detail: String)

@Composable
fun StatusPill(st: Status) {
    val pillBg = statusBg(st.kind)
    val pillFg = statusFg(st.kind)
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(pillBg).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(pillFg))
        Spacer(Modifier.width(6.dp))
        Text(st.label, color = pillFg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

fun s(nl: String, en: String) = if (appLocale().language == "nl") nl else en
fun String.capf() = replaceFirstChar { it.uppercase() }
fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)