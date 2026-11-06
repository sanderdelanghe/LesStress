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

fun fg(kind: Int) = when (kind) { 0 -> GREEN; 1 -> AMBER; else -> RED }
fun bg(kind: Int) = when (kind) { 0 -> GREEN_BG; 1 -> AMBER_BG; else -> RED_BG }

data class Status(val kind: Int, val label: String, val detail: String)

@Composable
fun StatusPill(st: Status) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg(st.kind)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(fg(st.kind)))
        Spacer(Modifier.width(6.dp))
        Text(st.label, color = fg(st.kind), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

fun s(nl: String, en: String) = if (appLocale().language == "nl") nl else en
fun String.capf() = replaceFirstChar { it.uppercase() }
fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)