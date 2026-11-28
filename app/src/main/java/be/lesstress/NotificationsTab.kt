package be.lesstress

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

private val zone = ZoneId.of("Europe/Brussels")

private fun Event.timeStr(loc: Locale): String {
    val dt = Instant.ofEpochMilli(start).atZone(zone)
    val endDt = Instant.ofEpochMilli(end).atZone(zone)
    val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", loc)
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm", loc)
    return "${dt.format(dateFmt)} · ${dt.format(timeFmt)}–${endDt.format(timeFmt)}"
}

@Composable
fun NotificationsTab() {
    val ctx = LocalContext.current
    val loc = appLocale()
    var showSettings by remember { mutableStateOf(false) }

    var changes by remember { mutableStateOf(listOf<Change>()) }
    LaunchedEffect(AppState.version, showSettings) {
        val forwardDays = ctx.prefs.getInt("notif_forward_days", 14)
        val maxForwardMillis = forwardDays * 86_400_000L
        val now = System.currentTimeMillis()
        val deletedUids = Store.events(ctx).filter { it.deleted }.map { it.uid }.toSet()

        changes = Store.changes(ctx)
            .filter { ch ->
                val ev = ch.new ?: ch.old
                val uid = ev?.uid ?: ""
                val lessonStart = ev?.start ?: 0L

                val notDeleted = uid !in deletedUids
                val withinForwardWindow = (lessonStart - now) <= maxForwardMillis

                notDeleted && withinForwardWindow
            }
            .sortedByDescending { it.time }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = s("Wijzigingen", "Changes"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { showSettings = true }) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = tr("settings"),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (changes.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = GREEN,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = tr("nochanges"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = s(
                                    "Er zijn nog geen roosterwijzigingen gedetecteerd. Alles verloopt zoals gepland!",
                                    "No schedule changes detected yet. Everything is on schedule!"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(changes) { ch ->
                    ChangeCard(ch, loc)
                }
            }
        }
    }

    if (showSettings) {
        SettingsScreen(onDismiss = { showSettings = false })
    }
}

// ---------- Detailed Visual Change Card ----------
@Composable
private fun ChangeCard(ch: Change, loc: Locale) {
    val cs = MaterialTheme.colorScheme
    val timeFmt = remember(loc) {
        SimpleDateFormat("d MMM HH:mm", loc).apply {
            timeZone = TimeZone.getTimeZone("Europe/Brussels")
        }
    }
    val formattedChangeTime = timeFmt.format(Date(ch.time))

    val oldEv = ch.old
    val newEv = ch.new

    val timeChanged = oldEv != null && newEv != null && (oldEv.start != newEv.start || oldEv.end != newEv.end)
    val locChanged = oldEv != null && newEv != null && (oldEv.location.trim() != newEv.location.trim())

    val (statusKind, statusLabel) = when {
        oldEv == null -> 0 to s("Nieuw", "New")
        newEv == null -> 2 to s("Geannuleerd", "Cancelled")
        timeChanged && locChanged -> 1 to s("Tijd & Lokaal gewijzigd", "Time & Room changed")
        timeChanged -> 1 to s("Tijd gewijzigd", "Time changed")
        locChanged -> 1 to s("Lokaal gewijzigd", "Room changed")
        else -> 1 to s("Gewijzigd", "Updated")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header: Status badge & timestamp
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(Status(statusKind, statusLabel, ""))
                Spacer(Modifier.weight(1f))
                Text(
                    text = formattedChangeTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
            }

            when {
                // NIEUWE LES
                oldEv == null && newEv != null -> {
                    Text(
                        text = newEv.summary.substringBefore(","),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    EventHighlightCard(
                        ev = newEv,
                        loc = loc,
                        bgColor = statusBg(0),
                        textColor = statusFg(0)
                    )
                }

                // GEANNULEERDE LES
                oldEv != null && newEv == null -> {
                    Text(
                        text = oldEv.summary.substringBefore(","),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.LineThrough,
                        color = statusFg(2)
                    )
                    EventHighlightCard(
                        ev = oldEv,
                        loc = loc,
                        bgColor = statusBg(2),
                        textColor = statusFg(2),
                        strikethrough = true
                    )
                }

                // GEWIJZIGDE LES (TIJD EN/OF LOKAAL DUIDELIJK IN KAART GEBRACHT)
                oldEv != null && newEv != null -> {
                    Text(
                        text = newEv.summary.substringBefore(","),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // 1. Tijdswijziging blok
                    if (timeChanged) {
                        DiffSection(
                            icon = Icons.Filled.Schedule,
                            label = s("Tijd / Datum", "Time / Date"),
                            oldValue = oldEv.timeStr(loc),
                            newValue = newEv.timeStr(loc)
                        )
                    }

                    // 2. Lokaalwijziging blok
                    if (locChanged) {
                        DiffSection(
                            icon = Icons.Filled.LocationOn,
                            label = s("Lokaal", "Room"),
                            oldValue = oldEv.location.lines().firstOrNull()?.ifBlank { "–" } ?: "–",
                            newValue = newEv.location.lines().firstOrNull()?.ifBlank { "–" } ?: "–"
                        )
                    }

                    // Fallback als onverhoopt noch tijd noch lokaal verschilt
                    if (!timeChanged && !locChanged) {
                        EventHighlightCard(
                            ev = newEv,
                            loc = loc,
                            bgColor = cs.surfaceContainerHigh,
                            textColor = cs.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    oldValue: String,
    newValue: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        // Oud
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(statusBg(2))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${tr("old")}: ",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = statusFg(2)
            )
            Text(
                text = oldValue,
                style = MaterialTheme.typography.bodySmall,
                color = statusFg(2),
                textDecoration = TextDecoration.LineThrough
            )
        }

        // Nieuw
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(statusBg(0))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${tr("new")}: ",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = statusFg(0)
            )
            Text(
                text = newValue,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = statusFg(0)
            )
        }
    }
}

@Composable
private fun EventHighlightCard(
    ev: Event,
    loc: Locale,
    bgColor: Color,
    textColor: Color,
    strikethrough: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = ev.timeStr(loc),
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            textDecoration = if (strikethrough) TextDecoration.LineThrough else TextDecoration.None
        )
        if (ev.location.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, Modifier.size(14.dp), tint = textColor)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = ev.location.lines().firstOrNull() ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                    textDecoration = if (strikethrough) TextDecoration.LineThrough else TextDecoration.None
                )
            }
        }
    }
}

// ---------- Re-add Removed Lessons Dialog ----------
@Composable
fun RemovedLessonsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val loc = appLocale()

    val removedLessons = remember(AppState.version) {
        Store.events(ctx)
            .filter { it.deleted }
            .sortedByDescending { if (it.deletedAt > 0) it.deletedAt else it.start }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = s("Verwijderde lessen", "Removed Lessons"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (removedLessons.isEmpty()) {
                    Text(
                        text = s("Er zijn momenteel geen verwijderde lessen.", "No removed lessons found."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    removedLessons.forEach { ev ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = ev.summary.substringBefore(","),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = ev.timeStr(loc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (ev.location.isNotBlank()) {
                                    Text(
                                        text = ev.location.lines().firstOrNull() ?: "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (ev.deletedAt > 0) {
                                    val timeFmt = remember(loc) {
                                        SimpleDateFormat("d MMM HH:mm", loc).apply {
                                            timeZone = TimeZone.getTimeZone("Europe/Brussels")
                                        }
                                    }
                                    Text(
                                        text = "${s("Verwijderd op", "Removed on")}: ${timeFmt.format(Date(ev.deletedAt))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            val updated = Store.events(ctx).map {
                                                if (it.uid == ev.uid) it.copy(deleted = false, deletedAt = 0L) else it
                                            }
                                            Store.saveEvents(ctx, updated)
                                            scheduleAlarms(ctx, updated)
                                            AppState.version++
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Filled.Restore, contentDescription = null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(tr("restore"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(s("Klaar", "Done"))
            }
        }
    )
}