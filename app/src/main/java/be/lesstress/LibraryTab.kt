package be.lesstress

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
import be.lesstress.providers.AppProvider
import be.lesstress.providers.LibraryProviderRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

data class LibRow(val date: LocalDate, val open: LocalTime?, val close: LocalTime?, val note: String? = null)
data class LibraryOccupancy(val occupied: Int, val capacity: Int)
data class LibraryData(val hours: List<LibRow>, val occupancy: LibraryOccupancy? = null)
data class LibraryBookAction(val url: String, val label: () -> String)
data class LibraryCheckInAction(
    val seatOffset: Int,
    val urlPrefix: String = "https://kuleuven.be/kurtqr?id="
) {
    fun urlForSeat(seat: Int): String = "$urlPrefix${seat + seatOffset}"
}

interface LibraryProvider : AppProvider {
    suspend fun fetchData(): LibraryData
    val bookAction: LibraryBookAction? get() = null
    val checkInAction: LibraryCheckInAction? get() = null
}

private val brussels = ZoneId.of("Europe/Brussels")

private fun computeLibStatus(rows: List<LibRow>, now: LocalDateTime, loc: Locale): Status? {
    if (rows.isEmpty()) return null
    val today = now.toLocalDate(); val t = now.toLocalTime()
    val todayRow = rows.firstOrNull { it.date == today }

    if (todayRow != null) {
        val note = todayRow.note
        if (!note.isNullOrBlank()) {
            val isClosed = note.contains("gesloten", ignoreCase = true) || todayRow.open == null || todayRow.close == null
            if (isClosed) {
                val next = rows.filter { it.date > today && it.open != null }.minByOrNull { it.date }
                val nextText = if (next != null) {
                    val whenText = if (next.date == today.plusDays(1)) s("morgen", "tomorrow")
                    else next.date.dayOfWeek.getDisplayName(TextStyle.SHORT, loc) + " " + next.date.dayOfMonth + "/" + next.date.monthValue
                    " · " + s("Opent $whenText om ${hhmm(next.open!!)}", "Opens $whenText at ${hhmm(next.open)}")
                } else ""
                return Status(2, tr("closed"), "$note$nextText")
            } else {
                if (t >= todayRow.open && t < todayRow.close) {
                    val mins = Duration.between(t, todayRow.close).toMinutes()
                    val timeDetail = if (mins <= 30) s("Sluit over $mins min (om ${hhmm(todayRow.close)})", "Closes in $mins min (at ${hhmm(todayRow.close)})")
                    else s("Sluit om ${hhmm(todayRow.close)}", "Closes at ${hhmm(todayRow.close)}")
                    return Status(1, s("Afwijkend", "Modified"), "$note · $timeDetail")
                } else if (t < todayRow.open) {
                    return Status(1, s("Afwijkend", "Modified"), "$note · ${s("Opent om ${hhmm(todayRow.open)}", "Opens at ${hhmm(todayRow.open)}")}")
                }
            }
        }
        if (todayRow.open != null && todayRow.close != null) {
            if (t >= todayRow.open && t < todayRow.close) {
                val mins = Duration.between(t, todayRow.close).toMinutes()
                return if (mins <= 30) Status(1, tr("open"), s("Sluit over $mins min (om ${hhmm(todayRow.close)})", "Closes in $mins min (at ${hhmm(todayRow.close)})"))
                else Status(0, tr("open"), s("Sluit om ${hhmm(todayRow.close)}", "Closes at ${hhmm(todayRow.close)}"))
            } else if (t < todayRow.open) return Status(2, tr("closed"), s("Opent om ${hhmm(todayRow.open)}", "Opens at ${hhmm(todayRow.open)}"))
        }
    }
    val next = rows.filter { it.date > today && it.open != null }.minByOrNull { it.date } ?: return Status(2, tr("closed"), s("Vandaag gesloten", "Closed today"))
    val whenText = if (next.date == today.plusDays(1)) s("morgen", "tomorrow") else next.date.dayOfWeek.getDisplayName(TextStyle.SHORT, loc) + " " + next.date.dayOfMonth + "/" + next.date.monthValue
    return Status(2, tr("closed"), s("Opent $whenText om ${hhmm(next.open!!)}", "Opens $whenText at ${hhmm(next.open)}"))
}

@Composable
fun LibraryTab() {
    val ctx = LocalContext.current
    val loc = appLocale()
    var reload by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(LocalDateTime.now(brussels)) }
    LaunchedEffect(Unit) { while (isActive) { delay(30_000); now = LocalDateTime.now(brussels) } }

    val providers = remember(AppState.version) { LibraryProviderRegistry.activeOrdered(ctx) }
    fun launchUrl(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("library"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton({ reload++ }, Modifier.size(36.dp)) { Icon(Icons.Filled.Refresh, contentDescription = s("Vernieuwen", "Refresh"), Modifier.size(20.dp)) }
        }

        if (providers.isEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Text(
                    s("Geen bibliotheken ingeschakeld. Schakel aanbieders in via Instellingen.", "No library providers enabled. Enable providers in Settings."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            providers.forEachIndexed { index, provider ->
                if (index > 0) HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                key(provider.id) {
                    LibraryProviderBlock(provider, now, loc, reload, ::launchUrl)
                }
            }
        }
    }
}

@Composable
private fun LibraryProviderBlock(
    provider: LibraryProvider,
    now: LocalDateTime,
    loc: Locale,
    reload: Int,
    onLaunch: (String) -> Unit
) {
    var data by remember(provider.id) { mutableStateOf<LibraryData?>(null) }
    var error by remember(provider.id) { mutableStateOf<String?>(null) }
    var loading by remember(provider.id) { mutableStateOf(true) }
    var seat by remember(provider.id) { mutableStateOf("") }

    LaunchedEffect(provider.id, AppState.version, reload) {
        while (isActive) {
            try {
                data = provider.fetchData()
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Error"
            } finally {
                loading = false
            }
            delay(60_000)
        }
    }

    LibVenueCard(provider.displayName(), data, now, loc, loading, error)

    provider.bookAction?.let { book ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ onLaunch(book.url) }, Modifier.fillMaxWidth().height(44.dp)) {
                    Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(book.label(), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }

    provider.checkInAction?.let { checkIn ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(s("Zitplaats inchecken", "Seat Check-in"), style = MaterialTheme.typography.titleMedium)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = seat,
                        onValueChange = { seat = it.filter(Char::isDigit) },
                        placeholder = { Text(tr("seat") + " (bijv. 369)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f).height(52.dp)
                    )
                    Button(
                        onClick = { seat.toIntOrNull()?.let { onLaunch(checkIn.urlForSeat(it)) } },
                        enabled = seat.isNotEmpty(),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.height(52.dp)
                    ) { Text(tr("checkin")) }
                }
            }
        }
    }
}

@Composable
private fun LibVenueCard(
    title: String,
    data: LibraryData?,
    now: LocalDateTime,
    loc: Locale,
    loading: Boolean,
    error: String?
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val hours = data?.hours.orEmpty()
    val status = computeLibStatus(hours, now, loc)

    Card(Modifier.fillMaxWidth().animateContentSize(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (status != null) StatusPill(status) else if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (status != null) Text(status.detail, color = fg(status.kind), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (error != null) Text("⚠ $error", color = RED, style = MaterialTheme.typography.bodySmall)

            data?.occupancy?.let { occ ->
                val ratio = if (occ.capacity > 0) (occ.occupied.toFloat() / occ.capacity).coerceIn(0f, 1f) else 0f
                val percent = (ratio * 100).toInt()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(tr("occupancy"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("${occ.occupied} / ${occ.capacity} ($percent%)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
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
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (isToday) cs.secondaryContainer else Color.Transparent)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        r.date.dayOfWeek.getDisplayName(TextStyle.SHORT, loc).capf() + " " + r.date.dayOfMonth + "/" + r.date.monthValue,
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (!r.note.isNullOrBlank()) {
                                        val isClosed = r.note.contains("gesloten", ignoreCase = true) || r.open == null || r.close == null
                                        if (isClosed) {
                                            Text(tr("closed"), color = RED, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                        } else {
                                            Text(
                                                "${hhmm(r.open!!)}–${hhmm(r.close!!)} (${s("afwijkend", "modified")})",
                                                color = AMBER,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    } else if (r.open == null || r.close == null) {
                                        Text(tr("closed"), color = RED, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    } else {
                                        Text(
                                            "${hhmm(r.open)}–${hhmm(r.close)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                                if (!r.note.isNullOrBlank()) {
                                    Text(
                                        r.note,
                                        color = cs.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(top = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
