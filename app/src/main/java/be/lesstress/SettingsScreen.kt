package be.lesstress

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import be.lesstress.providers.*
import kotlinx.coroutines.launch

private enum class SettingsCategory(val icon: ImageVector) {
    SCHEDULE(Icons.Filled.DateRange),
    NOTIFICATIONS(Icons.Filled.Notifications),
    PROVIDERS(Icons.Filled.Tune),
    APP(Icons.Filled.Settings)
}

@Composable
private fun SettingsCategory.label(): String = when (this) {
    SettingsCategory.SCHEDULE -> s("Rooster", "Schedule")
    SettingsCategory.NOTIFICATIONS -> tr("notifications")
    SettingsCategory.PROVIDERS -> s("Aanbieders", "Providers")
    SettingsCategory.APP -> s("App & Updates", "App & Updates")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var category by remember { mutableStateOf(SettingsCategory.SCHEDULE) }
    var showRemovedLessons by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(tr("settings"), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s("Sluiten", "Close"))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(SettingsCategory.entries) { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { category = item },
                            leadingIcon = { Icon(item.icon, contentDescription = null, Modifier.size(18.dp)) },
                            label = { Text(item.label()) }
                        )
                    }
                }

                HorizontalDivider(thickness = 0.5.dp)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (category) {
                        SettingsCategory.SCHEDULE -> ScheduleSettingsSection(ctx) { showRemovedLessons = true }
                        SettingsCategory.NOTIFICATIONS -> NotificationSettingsSection(ctx)
                        SettingsCategory.PROVIDERS -> ProvidersSettingsSection(ctx)
                        SettingsCategory.APP -> AppSettingsSection(ctx)
                    }
                }
            }
        }
    }

    if (showRemovedLessons) {
        RemovedLessonsDialog(onDismiss = { showRemovedLessons = false })
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun ScheduleSettingsSection(ctx: Context, onOpenRemoved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var ics by remember { mutableStateOf(ctx.prefs.getString("ics", "") ?: "") }
    var ics2 by remember { mutableStateOf(ctx.prefs.getString("ics2", "") ?: "") }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var isSyncing by remember { mutableStateOf(false) }
    val deletedCount = remember(AppState.version) { Store.events(ctx).count { it.deleted } }

    SectionHeader(s("Rooster & Synchronisatie", "Schedule & Sync"))

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = ics,
                onValueChange = {
                    ics = it
                    ctx.prefs.edit().putString("ics", it).apply()
                },
                label = { Text(tr("ics")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = ics2,
                onValueChange = {
                    ics2 = it
                    ctx.prefs.edit().putString("ics2", it).apply()
                },
                label = { Text(s("Extra Agenda", "Extra Calendar")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    scope.launch {
                        isSyncing = true
                        syncStatus = try {
                            val currentIcs = ctx.prefs.getString("ics", "")?.trim().orEmpty()
                            val currentIcs2 = ctx.prefs.getString("ics2", "")?.trim().orEmpty()
                            if (currentIcs.isBlank() && currentIcs2.isBlank()) {
                                s("Voer eerst een ICS-link in", "Please enter an ICS link first")
                            } else {
                                syncCalendar(ctx)
                                AppState.version++
                                s("Gesynchroniseerd!", "Synced!")
                            }
                        } catch (e: Exception) {
                            "⚠ " + (e.message ?: "Sync mislukt")
                        } finally {
                            isSyncing = false
                        }
                    }
                },
                enabled = !isSyncing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Filled.Sync, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(tr("sync"))
            }

            syncStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.startsWith("⚠")) RED else GREEN
                )
            }

            TextButton(
                onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ICS_GUIDE_URL))) } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(s("Roosterlink opzoeken op KU Leuven", "Find schedule link on KU Leuven"))
            }
        }
    }

    SectionHeader(tr("removed_lessons"))

    OutlinedCard(onClick = onOpenRemoved, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("removed_lessons"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = "$deletedCount ${s("les(sen) herstelbaar", "lesson(s) restorable")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationSettingsSection(ctx: Context) {
    var notifType by remember { mutableStateOf(ctx.prefs.getString("notif_type", "both") ?: "both") }
    var forwardDays by remember { mutableIntStateOf(ctx.prefs.getInt("notif_forward_days", 14)) }
    var lead by remember { mutableStateOf(ctx.prefs.getInt("lead", 5).toString()) }

    SectionHeader(s("Meldingen", "Notifications"))

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s("Meldingen ontvangen voor:", "Receive notifications for:"), style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    "both" to s("Beide", "Both"),
                    "lessons" to s("Lessen", "Lessons"),
                    "changes" to s("Wijzigingen", "Changes"),
                    "none" to s("Uit", "Off")
                ).forEach { (type, label) ->
                    FilterChip(
                        selected = notifType == type,
                        onClick = {
                            notifType = type
                            ctx.prefs.edit().putString("notif_type", type).apply()
                            scheduleAlarms(ctx, Store.events(ctx))
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            if (notifType == "both" || notifType == "changes") {
                HorizontalDivider(thickness = 0.5.dp)
                Text(s("Wijzigingen vooraf melden tot:", "Notify changes ahead up to:"), style = MaterialTheme.typography.bodySmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(7 to "7 d (1w)", 14 to "14 d (2w)", 30 to "30 d (1m)", 60 to "60 d (2m)").forEach { (days, label) ->
                        FilterChip(
                            selected = forwardDays == days,
                            onClick = {
                                forwardDays = days
                                ctx.prefs.edit().putInt("notif_forward_days", days).apply()
                                AppState.version++
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            if (notifType == "both" || notifType == "lessons") {
                HorizontalDivider(thickness = 0.5.dp)
                OutlinedTextField(
                    value = lead,
                    onValueChange = {
                        lead = it.filter(Char::isDigit)
                        val leadVal = lead.toIntOrNull() ?: 5
                        ctx.prefs.edit().putInt("lead", leadVal).apply()
                        scheduleAlarms(ctx, Store.events(ctx))
                    },
                    label = { Text(tr("lead")) },
                    suffix = { Text("min.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ProvidersSettingsSection(ctx: Context) {
    SectionHeader(s("Eten & Menu", "Dining & Menu"))
    ProviderRegistryList(ctx, MenuProviderRegistry)

    SectionHeader(tr("library"))
    ProviderRegistryList(ctx, LibraryProviderRegistry)
}

@Composable
private fun <T : AppProvider> ProviderRegistryList(ctx: Context, registry: ProviderRegistry<T>) {
    val items = remember(AppState.version) { registry.orderedAll(ctx) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            items.forEachIndexed { index, provider ->
                val enabled = remember(AppState.version, provider.id) { registry.isEnabled(ctx, provider.id) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = provider.displayName(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { registry.move(ctx, provider.id, -1); AppState.version++ },
                        enabled = index > 0,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = s("Omhoog", "Move up"))
                    }
                    IconButton(
                        onClick = { registry.move(ctx, provider.id, 1); AppState.version++ },
                        enabled = index < items.lastIndex,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = s("Omlaag", "Move down"))
                    }
                    Spacer(Modifier.width(4.dp))
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            registry.setEnabled(ctx, provider.id, it)
                            AppState.version++
                        }
                    )
                }
                if (index < items.lastIndex) {
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppSettingsSection(ctx: Context) {
    SectionHeader(s("Thema", "Theme"))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(14.dp)
        ) {
            listOf("system" to tr("system"), "light" to s("Licht", "Light"), "dark" to s("Donker", "Dark")).forEach { (mode, label) ->
                FilterChip(
                    selected = AppState.themeMode == mode,
                    onClick = {
                        AppState.themeMode = mode
                        ctx.prefs.edit().putString("theme", mode).apply()
                    },
                    label = { Text(label) }
                )
            }
        }
    }

    SectionHeader(tr("language"))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(14.dp)
        ) {
            listOf("system" to tr("system"), "nl" to "Nederlands", "en" to "English").forEach { (code, label) ->
                FilterChip(
                    selected = AppState.lang == code,
                    onClick = { AppState.setLanguage(ctx, code) },
                    label = { Text(label) }
                )
            }
        }
    }
}
