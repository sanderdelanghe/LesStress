package be.lesstress

// LibraryTab.kt = KBIB live occupancy, expandable opening hours, KURT reservations & seat check-in.

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

const val LIB_API = "https://bib.kuleuven.be/apps/ub/blokkeninleuven/api/libraries/kbib"
const val KURT_URL = "https://kurt3.ghum.kuleuven.be/locations/15"
fun seatUrl(seat: Int) = "https://kuleuven.be/kurtqr?id=${seat + 401629}"

private val brussels = ZoneId.of("Europe/Brussels")

private data class LibRow(val date: LocalDate, val open: LocalTime?, val close: LocalTime?)

private fun parseTime(str: String): LocalTime? {
    if (str.isBlank()) return null
    val p = str.replace(':', '.').split('.')
    return if (p.size == 2) LocalTime.of(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null) else null
}

private fun parseLibHours(j: JSONObject): List<LibRow> {
    val hrs = j.optJSONObject("hours") ?: return emptyList()
    val dtf = DateTimeFormatter.BASIC_ISO_DATE
    return hrs.keys().asSequence().mapNotNull { k ->
        val obj = hrs.getJSONObject(k)
        val dStr = obj.optString("date")
        if (dStr.length == 8) LibRow(LocalDate.parse(dStr, dtf), parseTime(obj.optString("begin")), parseTime(obj.optString("einde"))) else null
    }.sortedBy { it.date }.toList()
}

private fun computeLibStatus(rows: List<LibRow>, now: LocalDateTime, loc: Locale): Status? {
    if (rows.isEmpty()) return null
    val today = now.toLocalDate(); val t = now.toLocalTime()
    val todayRow = rows.firstOrNull { it.date == today }

    if (todayRow?.open != null && todayRow.close != null) {
        if (t >= todayRow.open && t < todayRow.close) {
            val mins = Duration.between(t, todayRow.close).toMinutes()
            return if (mins <= 30) Status(1, tr("open"), s("Sluit over $mins min (om ${hhmm(todayRow.close)})", "Closes in $mins min (at ${hhmm(todayRow.close)})"))
            else Status(0, tr("open"), s("Sluit om ${hhmm(todayRow.close)}", "Closes at ${hhmm(todayRow.close)}"))
        } else if (t < todayRow.open) return Status(2, tr("closed"), s("Opent om ${hhmm(todayRow.open)}", "Opens at ${hhmm(todayRow.open)}"))
    }
    val next = rows.filter { it.date > today && it.open != null }.minByOrNull { it.date } ?: return Status(2, tr("closed"), s("Vandaag gesloten", "Closed today"))
    val whenText = if (next.date == today.plusDays(1)) s("morgen", "tomorrow") else next.date.dayOfWeek.getDisplayName(TextStyle.SHORT, loc) + " " + next.date.dayOfMonth + "/" + next.date.monthValue
    return Status(2, tr("closed"), s("Opent $whenText om ${hhmm(next.open!!)}", "Opens $whenText at ${hhmm(next.open)}"))
}

@Composable
fun LibraryTab() {
    val ctx = LocalContext.current; val loc = appLocale()
    var reload by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(LocalDateTime.now(brussels)) }

    var occupied by remember { mutableStateOf<Int?>(null) }
    var capacity by remember { mutableStateOf(0) }
    var hoursList by remember { mutableStateOf<List<LibRow>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var seat by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { while (isActive) { delay(30_000); now = LocalDateTime.now(brussels) } }

    LaunchedEffect(AppState.version, reload) {
        while (isActive) {
            try {
                val jsonStr = withContext(Dispatchers.IO) { URL(LIB_API).readText() }
                val j = JSONObject(jsonStr)
                occupied = j.optInt("occupancy"); capacity = j.optInt("zitjes")
                hoursList = parseLibHours(j); error = null
            } catch (e: Exception) { error = e.message } finally { loading = false }
            delay(60_000)
        }
    }

    fun launchUrl(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s("Bibliotheek KBIB", "Library KBIB"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton({ reload++ }, Modifier.size(36.dp)) { Icon(Icons.Filled.Refresh, contentDescription = s("Vernieuwen", "Refresh"), Modifier.size(20.dp)) }
        }

        // Kaart 1: Bezetting & Openingsuren
        LibVenueCard(occupied, capacity, hoursList, now, loc, loading, error)

        // Kaart 2: Materiaal & Plekken (KURT)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(s("Materiaal & Plekken", "Material & Spaces"), style = MaterialTheme.typography.titleMedium)
                }
                Text(s("Reserveer een lokaal, studieplek of materiaal via KURT.", "Reserve a room, study space or material via KURT."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({ launchUrl(KURT_URL) }, Modifier.fillMaxWidth().height(44.dp)) {
                    Text(s("Reserveren via KURT", "Reserve via KURT"), style = MaterialTheme.typography.titleSmall)
                }
            }
        }

        // Kaart 3: Zitplaats inchecken (QR)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(s("Zitplaats inchecken", "Seat Check-in"), style = MaterialTheme.typography.titleMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = seat,
                        onValueChange = { seat = it.filter(Char::isDigit) },
                        label = { Text(tr("seat")) },
                        placeholder = { Text("369") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { seat.toIntOrNull()?.let { launchUrl(seatUrl(it)) } },
                        enabled = seat.isNotEmpty(),
                        modifier = Modifier.height(56.dp)
                    ) { Text(tr("checkin")) }
                }
            }
        }
    }
}

@Composable
private fun LibVenueCard(occupied: Int?, capacity: Int, hours: List<LibRow>, now: LocalDateTime, loc: Locale, loading: Boolean, error: String?) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val status = computeLibStatus(hours, now, loc)

    Card(Modifier.fillMaxWidth().animateContentSize(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s("Bezetting & Openingsuren", "Occupancy & Hours"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (status != null) StatusPill(status) else if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (status != null) Text(status.detail, color = fg(status.kind), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (error != null) Text("⚠ $error", color = RED, style = MaterialTheme.typography.bodySmall)

            occupied?.let { occ ->
                val ratio = if (capacity > 0) (occ.toFloat() / capacity).coerceIn(0f, 1f) else 0f
                val percent = (ratio * 100).toInt()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(s("Bezetting", "Occupancy"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("$occ / $capacity ($percent%)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = if (ratio > 0.85f) RED else cs.primary)
                }
            }

            if (hours.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tr("hours"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, Modifier.size(18.dp))
                }
                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        hours.forEach { r ->
                            val isToday = r.date == now.toLocalDate()
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (isToday) cs.secondaryContainer else Color.Transparent).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(r.date.dayOfWeek.getDisplayName(TextStyle.SHORT, loc).capf() + " " + r.date.dayOfMonth + "/" + r.date.monthValue, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
                                if (r.open == null || r.close == null) Text(tr("closed"), color = RED, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                else Text("${hhmm(r.open)}–${hhmm(r.close)}", style = MaterialTheme.typography.bodySmall, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    }
}