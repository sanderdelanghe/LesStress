package be.lesstress

import android.Manifest
import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector

import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.*
import org.json.*
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

const val ICS_GUIDE_URL = "https://webwsp.aps.kuleuven.be/sap/bc/ui2/flp?sap-language=NL#zr_1014-display"

// ---------- Data classes ----------
data class Event(
    val uid: String,
    val summary: String,
    val location: String,
    val start: Long,
    val end: Long,
    val hash: String,
    val deleted: Boolean = false,
    val desc: String = "",
    val deletedAt: Long = 0L
)

data class Change(val time: Long, val old: Event?, val new: Event?)

// ---------- App-wide state ----------
object AppState {
    var lang by mutableStateOf("system")
    var themeMode by mutableStateOf("system") // "system", "light", "dark"
    var version by mutableIntStateOf(0)

    fun load(c: Context) {
        themeMode = c.prefs.getString("theme", "system") ?: "system"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = c.getSystemService(LocaleManager::class.java)
            val appLocales = lm?.applicationLocales
            lang = if (appLocales != null && !appLocales.isEmpty) {
                appLocales[0].language
            } else {
                c.prefs.getString("lang", "system") ?: "system"
            }
        } else {
            lang = c.prefs.getString("lang", "system") ?: "system"
        }
    }

    fun setLanguage(c: Context, code: String) {
        lang = code
        c.prefs.edit().putString("lang", code).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = c.getSystemService(LocaleManager::class.java)
            lm?.applicationLocales = if (code == "system") LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
        }
        version++
    }
}

val Context.prefs: SharedPreferences
    get() {
        val p = getSharedPreferences("lesstress", Context.MODE_PRIVATE)
        if (p.all.isEmpty()) {
            val old = getSharedPreferences("kulak", Context.MODE_PRIVATE)
            if (old.all.isNotEmpty()) {
                val ed = p.edit()
                old.all.forEach { (k, v) ->
                    when (v) {
                        is String -> ed.putString(k, v)
                        is Int -> ed.putInt(k, v)
                        is Boolean -> ed.putBoolean(k, v)
                        is Long -> ed.putLong(k, v)
                    }
                }
                ed.apply()
            }
        }
        return p
    }

// ---------- Translations ----------
private val nl = mapOf(
    "calendar" to "Agenda", "dining" to "Eten", "library" to "Bibliotheek", "changes" to "Wijzigingen", "notifications" to "Meldingen",
    "day" to "Dag", "week" to "Week", "delete" to "Verwijderen", "cancel" to "Annuleren", "open" to "Open", "closed" to "Gesloten",
    "order" to "Broodje bestellen", "occupancy" to "Bezetting", "seat" to "Zitplaatsnummer", "checkin" to "Inchecken",
    "old" to "Oud", "new" to "Nieuw", "nochanges" to "Geen wijzigingen", "language" to "Taal", "lead" to "Melding (minuten vooraf)",
    "ics" to "ICS-link", "sync" to "Nu synchroniseren", "synced" to "Gesynchroniseerd", "restaurant" to "KULAK Restaurant",
    "broodjes" to "KULAK Broodjesbar", "menu" to "Menu (van de Alma-website)", "hours" to "Openingsuren", "today" to "Vandaag",
    "system" to "Systeem", "noevents" to "Geen lessen gepland.", "settings" to "Instellingen", "list" to "Lijst",
    "removed_lessons" to "Verwijderde lessen", "restore" to "Herstel"
)

private val en = mapOf(
    "calendar" to "Calendar", "dining" to "Dining", "library" to "Library", "changes" to "Changes", "notifications" to "Notifications",
    "day" to "Day", "week" to "Week", "delete" to "Delete", "cancel" to "Cancel", "open" to "Open", "closed" to "Closed",
    "order" to "Order sandwich", "occupancy" to "Occupancy", "seat" to "Seat number", "checkin" to "Check in",
    "old" to "Old", "new" to "New", "nochanges" to "No changes", "language" to "Language", "lead" to "Alert (minutes before)",
    "ics" to "ICS URL", "sync" to "Sync now", "synced" to "Synced", "restaurant" to "KULAK Restaurant",
    "broodjes" to "KULAK Sandwich bar", "menu" to "Menu (from the Alma website)", "hours" to "Opening hours", "today" to "Today",
    "system" to "System", "noevents" to "No events scheduled.", "settings" to "Settings", "list" to "List",
    "removed_lessons" to "Removed lessons", "restore" to "Restore"
)

fun tr(key: String): String {
    val code = if (AppState.lang == "system") Locale.getDefault().language else AppState.lang
    return (if (code == "nl") nl else en)[key] ?: key
}

// ---------- Persistence ----------
object Store {
    private fun Event.toJson() = JSONObject().put("uid", uid).put("s", summary).put("l", location)
        .put("st", start).put("e", end).put("h", hash).put("d", deleted).put("desc", desc).put("da", deletedAt)

    private fun JSONObject.toEvent() = Event(
        getString("uid"),
        optString("s", optString("summary")),
        optString("l", optString("location")),
        optLong("st", optLong("start")),
        optLong("e", optLong("end")),
        optString("h", optString("hash")),
        optBoolean("d", optBoolean("deleted")),
        optString("desc", ""),
        optLong("da", optLong("deletedAt", 0L))
    )

    fun events(c: Context): List<Event> {
        val json = c.prefs.getString("events", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).toEvent() }
        }.getOrDefault(emptyList())
    }

    fun saveEvents(c: Context, list: List<Event>) =
        c.prefs.edit().putString("events", JSONArray(list.map { it.toJson() }).toString()).apply()

    fun changes(c: Context): List<Change> {
        val json = c.prefs.getString("changes", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                Change(
                    o.optLong("t", o.optLong("time")),
                    (o.optJSONObject("o") ?: o.optJSONObject("old"))?.toEvent(),
                    (o.optJSONObject("n") ?: o.optJSONObject("new"))?.toEvent()
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addChange(c: Context, ch: Change) {
        val all = listOf(ch) + changes(c)
        val arr = JSONArray(all.take(100).map {
            JSONObject().put("t", it.time).put("o", it.old?.toJson()).put("n", it.new?.toJson())
        })
        c.prefs.edit().putString("changes", arr.toString()).apply()
    }
}

suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) { URL(url).readText() }

// ---------- Background Sync & Notifications ----------
class SyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = runCatching {
        syncCalendar(applicationContext)
        Result.success()
    }.getOrDefault(Result.retry())
}

fun schedulePeriodicSync(c: Context) {
    val req = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
    WorkManager.getInstance(c).enqueueUniquePeriodicWork("ics-sync", ExistingPeriodicWorkPolicy.KEEP, req)
}

fun notify(c: Context, title: String, text: String, id: Int) {
    val nm = c.getSystemService(NotificationManager::class.java) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        nm.createNotificationChannel(NotificationChannel("lesstress", "LesStress", NotificationManager.IMPORTANCE_DEFAULT))
    }
    val builder = Notification.Builder(c, "lesstress")
        .setSmallIcon(R.drawable.notification_icon_white)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        builder.setColor(PrimaryBlue.toArgb())
    }
    nm.notify(id, builder.build())
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) =
        notify(c, i.getStringExtra("title") ?: "", i.getStringExtra("text") ?: "", i.getIntExtra("id", 0))
}

fun scheduleAlarms(c: Context, events: List<Event>) {
    val am = c.getSystemService(AlarmManager::class.java) ?: return
    val notifType = c.prefs.getString("notif_type", "both") ?: "both"
    val lessonsEnabled = notifType == "both" || notifType == "lessons"
    val lead = c.prefs.getInt("lead", 5) * 60_000L
    val now = System.currentTimeMillis()
    for (e in events) {
        val intent = Intent(c, AlarmReceiver::class.java).putExtra("title", e.summary).putExtra("text", e.location).putExtra("id", e.uid.hashCode())
        val pi = PendingIntent.getBroadcast(c, e.uid.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (!lessonsEnabled || e.deleted || e.start - lead < now) am.cancel(pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, e.start - lead, pi)
    }
}

// ---------- Material 3 Theme (LesStress #1183D5 Scheme) ----------
val PrimaryBlue = Color(0xFF1183D5)


private val LightColors = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCE5FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = PrimaryBlue,
    onSecondary = Color.White,
    surface = Color(0xFFF8FAFD),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDFE3EB),
    onSurfaceVariant = Color(0xFF42474E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF88C5FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = PrimaryBlue,
    onPrimaryContainer = Color(0xFFCCE5FF),
    secondary = Color(0xFF88C5FF),
    onSecondary = Color(0xFF00325B),
    surface = Color(0xFF111417),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC3C7D0)
)

@Composable
fun LesStressTheme(
    themeMode: String = AppState.themeMode,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

// ---------- Main UI & Activity ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        AppState.load(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        schedulePeriodicSync(this)
        setContent { LesStressTheme { AppRoot() } }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { runCatching { syncCalendar(this@MainActivity) }; AppState.version++ }
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    var onboardingDone by remember { mutableStateOf(ctx.prefs.getBoolean("onboarding_done", false)) }


    if (!onboardingDone) {
        OnboardingScreen(onFinished = {
            onboardingDone = true
            (ctx as? ComponentActivity)?.lifecycleScope?.launch {
                runCatching { syncCalendar(ctx) }
                AppState.version++
            }
        })
    } else {
        MainScreen()
    }
}

// ---------- First Time Guide / Onboarding Screen ----------
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var icsInput by remember { mutableStateOf(ctx.prefs.getString("ics", "") ?: "") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Image(
                painter = painterResource(R.drawable.app_icon),
                contentDescription = "App Icon",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
            )

            Text(
                text = s("Welkom bij LesStress", "Welcome to LesStress"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            Text(
                text = s(
                    "Volg je lessenrooster, mis nooit een lokaalwijziging en ervaar minder lesstress.",
                    "Track your class schedule, never miss a room change, and stay stress-free."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Stap 1
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = s("Stap 1: Haal je rooster-link op", "Step 1: Get your schedule link"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = s(
                            "Log in op het KU Leuven portaal om je persoonlijke ICS-agendalink te kopiëren.",
                            "Sign in to the KU Leuven portal to copy your personal ICS schedule link."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ICS_GUIDE_URL))
                            ctx.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s("Open KU Leuven Roosterportaal", "Open KU Leuven Schedule Portal"))
                    }
                }
            }

            // Stap 2
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = s("Stap 2: Plak je ICS-link", "Step 2: Paste your ICS link"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = icsInput,
                        onValueChange = { icsInput = it },
                        label = { Text(tr("ics")) },
                        placeholder = { Text("https://cloud.timeedit.net/...") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                val text = clipboard.getText()?.text
                                if (!text.isNullOrBlank()) icsInput = text.trim()
                            }) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.weight(1f, fill = false))

            // Start knop
            Button(
                onClick = {
                    val finalIcs = icsInput.trim()
                    ctx.prefs.edit().putString("ics", finalIcs).putBoolean("onboarding_done", true).apply()
                    onFinished()
                },
                enabled = icsInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(
                    text = s("Aan de slag", "Get started"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            TextButton(
                onClick = {
                    ctx.prefs.edit().putString("ics", "").putBoolean("onboarding_done", true).apply()
                    onFinished()
                }
            ) {
                Text(
                    text = s("Overslaan (later instellen)", "Skip (configure later)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class NavItem(
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val key: String
)

@Composable
fun MainScreen() {
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val items = remember {
        listOf(
            NavItem(Icons.Filled.DateRange, Icons.Outlined.DateRange, "calendar"),
            NavItem(Icons.Filled.Restaurant, Icons.Outlined.Restaurant, "dining"),
            NavItem(Icons.AutoMirrored.Filled.MenuBook, Icons.AutoMirrored.Outlined.MenuBook, "library"),
            NavItem(Icons.Filled.Notifications, Icons.Outlined.Notifications, "notifications")
        )
    }

    val pagerState = rememberPagerState(pageCount = { items.size })

    LaunchedEffect(pagerState.currentPage) {
        tab = pagerState.currentPage
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                items.forEachIndexed { i, item ->
                    val selected = tab == i
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            tab = i
                            scope.launch {
                                pagerState.animateScrollToPage(i)
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = tr(item.key)
                            )
                        },
                        label = { Text(tr(item.key)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { pad ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            beyondViewportPageCount = 3,
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
        ) { page ->
            when (page) {
                0 -> CalendarTab()
                1 -> DiningTab()
                2 -> LibraryTab()
                else -> NotificationsTab()
            }
        }
    }
}