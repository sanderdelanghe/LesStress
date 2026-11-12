package be.lesstress

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/* =====================  1. PARSING THE .ics TEXT  ===================== */

private val zone = ZoneId.of("Europe/Brussels")

private fun parseTime(v: String): Long {
    if (v.length == 8) // all-day event: 20261021
        return LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone).toInstant().toEpochMilli()
    val ldt = LocalDateTime.parse(v.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
    return ldt.atZone(if (v.endsWith("Z")) ZoneOffset.UTC else zone).toInstant().toEpochMilli()
}
private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\N", "\n")
    .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

fun parseIcs(raw: String): List<Event> {
    val text = raw.replace("\r", "").replace("\n ", "").replace("\n\t", "") // unfold wrapped lines
    val out = mutableListOf<Event>()
    var p = mutableMapOf<String, String>()
    for (line in text.lines()) {
        if (line == "BEGIN:VEVENT") p = mutableMapOf()
        else if (line == "END:VEVENT") runCatching {
            val sum = unescape(p["SUMMARY"] ?: ""); val loc = unescape(p["LOCATION"] ?: "")
            val st = parseTime(p["DTSTART"]!!); val en = parseTime(p["DTEND"]!!)
            out += Event(p["UID"]!!, sum, loc, st, en, "$sum|$loc|$st|$en".hashCode().toString(),
                false, unescape(p["DESCRIPTION"] ?: ""))
        } else if (':' in line) p[line.substringBefore(':').substringBefore(';')] = line.substringAfter(':')
    }
    return out
}

/* =====================  2. SYNC + DELETION-OVERRIDE  ===================== */

suspend fun syncCalendar(c: Context) {
    val icsUrl = c.prefs.getString("ics", "")?.trim().orEmpty()
    if (icsUrl.isBlank()) return
    val fresh = parseIcs(httpGet(icsUrl))
    if (fresh.isEmpty()) return
    val freshIds = fresh.map { it.uid }.toSet()
    val saved = Store.events(c).associateBy { it.uid }.toMutableMap()
    val now = System.currentTimeMillis()
    val notifType = c.prefs.getString("notif_type", "both") ?: "both"
    val changesNotif = notifType == "both" || notifType == "changes"
    val forwardDays = c.prefs.getInt("notif_forward_days", 14)
    val maxForwardMillis = forwardDays * 86_400_000L

    for (n in fresh) {
        val old = saved[n.uid]
        when {
            old == null -> saved[n.uid] = n
            else -> {
                saved[n.uid] = n.copy(deleted = old.deleted, deletedAt = old.deletedAt)
                val timeChanged = old.start != n.start || old.end != n.end
                val locChanged = old.location.trim() != n.location.trim()
                if ((timeChanged || locChanged) && !old.deleted) {
                    Store.addChange(c, Change(now, old, n))
                    if (changesNotif && (n.start - now) in 0..maxForwardMillis) {
                        notify(c, n.summary.substringBefore(","), "⚠ " + tr("changes"), n.uid.hashCode() + 1)
                    }
                }
            }
        }
    }
    val windowStart = fresh.minOf { it.start }
    for (old in saved.values.toList()) {
        if (old.uid !in freshIds && old.start >= maxOf(windowStart, now)) {
            saved.remove(old.uid)
            if (!old.deleted) {
                Store.addChange(c, Change(now, old, null))
                if (changesNotif && (old.start - now) in 0..maxForwardMillis) {
                    notify(c, old.summary.substringBefore(","), "⚠ " + tr("changes"), old.uid.hashCode() + 1)
                }
            }
        }
    }
    val all = saved.values.toList()
    Store.saveEvents(c, all)
    scheduleAlarms(c, all)
}

/* =====================  3. FIELD SPLITTING (no text may get lost)  ===================== */

private val ID = Regex("[A-Z]\\d\\d[A-Z]\\d[a-z]")

/** Only a stable key for the per-course colour preference (e.g. U00K1a). Never shown. */
private fun Event.courseId() = ID.find(summary)?.value ?: summary.take(6)

private class Parsed(
    val vak: List<String>,
    val soort: String,
    val lesinfo: String,
    val groep: String,
    val rooms: List<String>,
    val teachers: List<String>,
    val overig: String
)

private val WS = Regex("\\s+")
private val WORD_RE = Regex("[\\p{L}\\p{N}]+")
private val CODE_RE = Regex("^[A-Za-z]{1,3}\\d{2,}[A-Za-z0-9]*$")                 // U00K1a
private val TYPE_NUM_RE = Regex("^\\d{1,2}\\s+\\S.*")                               // "02 Practicum"
private val TYPE_ALPHA_RE = Regex("^[A-Z]{2,3}\\s+\\S.*")                           // "EC Examen"
private val GROUP_RE = Regex("(?i)\\b(groep|group|gr)\\s*[A-Z]?\\d*\\b")
private val GROUP_IN_TEXT_RE = Regex(
    "(?i)\\b(?:groep|group|gr)\\s*[A-Z]?\\d+(?:\\s*[+&]\\s*[A-Z]?\\d+)*|\\b(?:groep|group|gr)\\s+[A-Z]\\b")
private val DESC_ID_RE = Regex("^ID\\s+\\S+$")
private val ROOM_CODE_RE = Regex("\\s+[A-Za-z]{1,5}\\.\\d{2}\\.\\d{4}\\s*$")

private fun words(s: String): Set<String> = WORD_RE.findAll(s.lowercase()).map { it.value }.toSet()
private fun commaParts(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
private fun looksLikeName(t: String) = !Regex("[\\d:=()]").containsMatchIn(t) && t.split(WS).size in 1..8

/**
 * Splits SUMMARY / LOCATION / DESCRIPTION into fields. Every word of the source either lands in a
 * field or is a known code/label; anything else is appended to lesinfo (prefixed with a warning sign).
 */
private fun splitEvent(e: Event): Parsed {
    val vak = mutableListOf<String>()
    val teachers = mutableListOf<String>()
    val lesinfo = mutableListOf<String>()
    val groups = mutableListOf<String>()
    var soort = ""
    val ignored = mutableSetOf("info", "id")          // labels and codes that are hidden on purpose

    // ---- SUMMARY: "CODE Vak: sublabel, [prof, ...], NN Soort, [info: ..., vrije tekst ...]"
    val tokens = commaParts(e.summary)
    if (tokens.isNotEmpty()) {
        val head = tokens[0].split(WS, limit = 2)
        if (head.size == 2 && CODE_RE.matches(head[0])) { ignored += words(head[0]); vak.add(head[1]) } else vak.add(tokens[0])

        val rest = tokens.drop(1)
        var ti = rest.indexOfFirst { TYPE_NUM_RE.matches(it) }
        if (ti < 0) ti = rest.indexOfFirst { TYPE_ALPHA_RE.matches(it) }
        val pre = if (ti >= 0) rest.take(ti) else rest
        val post = if (ti >= 0) rest.drop(ti + 1).toMutableList() else mutableListOf()
        if (ti >= 0) { val t = rest[ti].split(WS, limit = 2); ignored += words(t[0]); soort = t[1] }

        var infoAt = 0
        for (t in pre) {
            val tw = t.split(WS, limit = 2)
            when {
                tw.size == 2 && CODE_RE.matches(tw[0]) -> { ignored += words(tw[0]); vak.add(tw[1]) }   // 2nd course code
                t.startsWith("info:", ignoreCase = true) -> post.add(infoAt++, t)
                looksLikeName(t) -> teachers.add(t)
                else -> lesinfo.add(t)
            }
        }

        val infoPlain = mutableListOf<String>()
        val free = mutableListOf<String>()
        for (t in post) {
            if (t.startsWith("info:", ignoreCase = true)) {
                val body = t.substring(5).trim()
                if (body.isNotEmpty()) { if (GROUP_RE.containsMatchIn(body)) groups.add(body) else infoPlain.add(body) }
            } else free.add(t)
        }
        lesinfo.addAll(infoPlain); lesinfo.addAll(free)
    }

    // ---- DESCRIPTION ("U00K1a_groep B2", "U00F8a HC", "ID 123") -> "overig":
    //      course codes, the ID line and years 2020..now are removed, everything else stays visible
    val thisYear = LocalDate.now(zone).year
    val descGroups = mutableListOf<String>()
    val overigRaw = mutableListOf<String>()
    for (raw in e.desc.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (DESC_ID_RE.matches(line)) { ignored += words(line); continue }
        for (part in commaParts(line)) {
            val kept = mutableListOf<String>()
            for (tok0 in part.split(WS)) {
                var tok = tok0
                val us = tok.indexOf('_')
                if (us > 0 && CODE_RE.matches(tok.substring(0, us))) { ignored += words(tok.substring(0, us)); tok = tok.substring(us + 1) }
                if (tok.isEmpty()) continue
                if (CODE_RE.matches(tok)) { ignored += words(tok); continue }
                if (tok.length == 4 && tok.all { it.isDigit() } && tok.toInt() in 2020..thisYear) { ignored += words(tok); continue }
                kept.add(tok)
            }
            val text = kept.joinToString(" ")
            if (text.isNotEmpty()) { overigRaw.add(text); if (GROUP_RE.containsMatchIn(text)) descGroups.add(text) }
        }
    }

    // ---- group: from "info:" items, else from free lesinfo ("groep 1: ...", "(groep B3)"), else from DESCRIPTION
    if (groups.isEmpty()) {
        val found = GROUP_IN_TEXT_RE.findAll(lesinfo.joinToString(" , ")).map { it.value }.distinct().toList()
        groups.addAll(found.ifEmpty { descGroups.distinct() })
    }
    val overigShown = overigRaw.distinct()

    // room names without the long trailing building code (e.g. "C.00.0611"); the code stays visible in the raw view
    val rooms = commaParts(e.location).map { r ->
        val m = ROOM_CODE_RE.find(r)
        if (m != null && m.range.first > 0) { ignored += words(m.value); r.removeRange(m.range).trim() } else r
    }

    // ---- safety net: any source word that is not displayed (or a known code/label) is shown in lesinfo
    val shown = words((vak + soort + lesinfo + groups + rooms + teachers + overigShown).joinToString(" ")) + ignored
    val src = e.summary + " " + e.location + " " + e.desc
    val missing = WORD_RE.findAll(src).map { it.value }.filter { it.lowercase() !in shown }.distinct().toList()
    if (missing.isNotEmpty()) lesinfo.add("⚠ " + missing.joinToString(" "))

    return Parsed(vak, soort, lesinfo.joinToString(", "), groups.joinToString(" | "),
        rooms, teachers, overigShown.joinToString(" | "))
}

private val parsedCache = java.util.concurrent.ConcurrentHashMap<String, Parsed>()
private fun Event.parsed(): Parsed = parsedCache.getOrPut("$uid|$hash|${desc.hashCode()}") { splitEvent(this) }

/** Main title: the course name(s), e.g. "MFP bloedsomloop: practica". */
private fun Event.title(): String = parsed().vak.joinToString(" | ") { v ->
    v.split(": ").joinToString(": ") { it.replaceFirstChar { c -> c.uppercase() } }
}

private fun roomShort(line: String): String {
    val words = line.trim().split(" ").filter { it.isNotEmpty() }
    if (words.isEmpty()) return ""
    val first = words[0].substringBefore("-")
    if (first.any { it.isDigit() }) return first
    return when (first.lowercase()) {
        "online" -> "Online"
        "externe" -> if (appLocale().language == "nl") "Extern" else "External"
        else -> words.drop(1).firstOrNull { it.matches(Regex("\\d+\\.\\d+")) }?.let { "$first $it" } ?: first
    }
}

private fun Event.pin() = parsed().rooms.map { roomShort(it) }.filter { it.isNotEmpty() }.distinct().joinToString(", ")

/** Raw text as stored, with any leftover backslashes removed. */
private fun rawClean(s: String) = s.replace("\\", "").trim()

/** Uitgebreid kleurenpalet (16 kleuropties) */
private val EXTENDED_PALETTE = listOf(
    Color(0xFF3F51B5), Color(0xFF1E88E5), Color(0xFF009688), Color(0xFF00AFC8),
    Color(0xFF4CAF50), Color(0xFF2E7D32), Color(0xFF8BC34A), Color(0xFFFF9800),
    Color(0xFFF57C00), Color(0xFFE91E63), Color(0xFFD81B60), Color(0xFF9C27B0),
    Color(0xFF673AB7), Color(0xFF795548), Color(0xFF607D8B), Color(0xFF455A64)
)

private fun getCourseColor(context: Context, courseId: String): Color {
    val prefs = context.prefs
    val key = "course_color_$courseId"
    if (prefs.contains(key)) return Color(prefs.getInt(key, 0))
    val hue = ((courseId.hashCode() and 0x7fffffff) % 997 * 137.5f) % 360f
    return Color.hsv(hue, 0.55f, 0.70f)
}

private fun setCourseColor(context: Context, courseId: String, color: Color) {
    context.prefs.edit().putInt("course_color_$courseId", color.toArgb()).apply()
}

@Composable
private fun Event.color(): Color {
    val ctx = LocalContext.current
    return remember(courseId(), AppState.version) { getCourseColor(ctx, courseId()) }
}

private fun Event.startDt() = Instant.ofEpochMilli(start).atZone(zone)
private fun Event.endDt() = Instant.ofEpochMilli(end).atZone(zone)
private fun Event.startMin() = startDt().hour * 60 + startDt().minute
private fun Event.timeText() = "%02d:%02d - %02d:%02d".format(startDt().hour, startDt().minute, endDt().hour, endDt().minute)
fun appLocale(): Locale = Locale.forLanguageTag(if (AppState.lang == "system") Locale.getDefault().language else AppState.lang)
private fun String.cap() = replaceFirstChar { it.uppercase() }

/* =====================  4. OVERLAP LAYOUT  ===================== */

private class Placed(val e: Event, val col: Int, val cols: Int)

private fun layoutDay(list: List<Event>, minMin: Long): List<Placed> {
    fun effEnd(e: Event) = maxOf(e.end, e.start + minMin * 60_000L)
    val sorted = list.sortedWith(compareBy<Event> { it.start }.thenByDescending { it.end })
    val out = mutableListOf<Placed>()
    var cluster = mutableListOf<Event>(); var clusterEnd = 0L
    fun flush() {
        val colEnds = mutableListOf<Long>(); val cols = mutableListOf<Int>()
        for (e in cluster) {
            var c = colEnds.indexOfFirst { it <= e.start }
            if (c < 0) { colEnds.add(effEnd(e)); c = colEnds.size - 1 } else colEnds[c] = effEnd(e)
            cols.add(c)
        }
        cluster.forEachIndexed { i, e -> out += Placed(e, cols[i], colEnds.size) }
        cluster = mutableListOf()
    }
    for (e in sorted) {
        if (cluster.isNotEmpty() && e.start >= clusterEnd) flush()
        clusterEnd = if (cluster.isEmpty()) effEnd(e) else maxOf(clusterEnd, effEnd(e))
        cluster.add(e)
    }
    if (cluster.isNotEmpty()) flush()
    return out
}

/* =====================  5. UI  ===================== */

// Aangepaste enum voor dagen selectie (1, 2, 3, 7 dagen en Lijst)
private enum class CalView(val days: Int) { D1(1), D2(2), D3(3), D7(7), LIST(-1) }

private val MIN_H = 40.dp
private const val MIN_ZOOM = 20f // Verlaagd zodat er meer uren op korte schermen (3:4) passen
private const val MAX_ZOOM = 160f

private fun isWeekend(d: LocalDate) = d.dayOfWeek.value >= 6
private fun Event.date(): LocalDate = startDt().toLocalDate()
private fun mondayOf(d: LocalDate) = d.minusDays(d.dayOfWeek.value - 1L)
private fun hidden(d: LocalDate, ev: List<Event>) = isWeekend(d) && ev.none { it.date() == d }
private fun stepDay(d: LocalDate, dir: Int, ev: List<Event>): LocalDate {
    var x = d.plusDays(dir.toLong()); while (hidden(x, ev)) x = x.plusDays(dir.toLong()); return x
}

private fun pageDays(view: CalView, day: LocalDate, ev: List<Event>, idx: Int): List<LocalDate> {
    if (view == CalView.D7) {
        val monday = mondayOf(day).plusDays(7L * idx)
        val weekend = ev.any { it.date() == monday.plusDays(5) || it.date() == monday.plusDays(6) }
        return (0 until if (weekend) 7 else 5).map { monday.plusDays(it.toLong()) }
    }
    
    // Voor D1, D2, D3 schalen we dynamisch op basis van verborgen dagen (weekends)
    var startD = day
    val stepDir = if (idx < 0) -1 else 1
    repeat(abs(idx) * view.days) { startD = stepDay(startD, stepDir, ev) }

    val result = mutableListOf<LocalDate>()
    var current = startD
    while (result.size < view.days) {
        if (!hidden(current, ev)) {
            result.add(current)
        }
        current = current.plusDays(1)
    }
    return result
}

private fun t2(nl: String, en: String) = if (appLocale().language == "nl") nl else en

@Composable
private fun PinText(text: String, color: Color, sp: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = color, modifier = Modifier.size((sp + 1f).dp))
        Text(text, color = color, fontSize = sp.sp, lineHeight = (sp + 2f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun CalendarTab() {
    val ctx = LocalContext.current
    var events by remember { mutableStateOf(listOf<Event>()) }
    LaunchedEffect(AppState.version) { events = Store.events(ctx).filter { !it.deleted } }
    var view by remember { mutableStateOf(CalView.D7) }
    var day by remember { mutableStateOf(LocalDate.now(zone)) }
    var selected by remember { mutableStateOf<Event?>(null) }
    var colorPickerEvent by remember { mutableStateOf<Event?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val loc = appLocale()
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var hourDp by remember { mutableFloatStateOf(ctx.prefs.getFloat("hourDp", 64f).coerceIn(MIN_ZOOM, MAX_ZOOM)) }

    fun settleDay(d: LocalDate) = if (hidden(d, events)) stepDay(d, 1, events) else d
    
    fun shift(dir: Int) { 
        if (view == CalView.D7) day = day.plusDays(7L * dir)
        else if (view != CalView.LIST) {
            var d = day
            repeat(view.days) { d = stepDay(d, dir, events) }
            day = d
        }
    }
    
    fun viewName(v: CalView) = when (v) { 
        CalView.LIST -> tr("list") 
        else -> "${v.days}" 
    }

    fun zoom(z: Float, fy: Float) {
        val old = hourDp
        val new = (old * z).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (new == old) return
        val currentScroll = scroll.value
        val newScroll = (((currentScroll + fy) * (new / old)) - fy).roundToInt().coerceAtLeast(0)
        hourDp = new
        scope.launch { scroll.scrollTo(newScroll) }
    }

    LaunchedEffect(events.isNotEmpty()) {
        val monday = mondayOf(day)
        val first = events.filter { it.date() >= monday && it.date() < monday.plusDays(7) }.minOfOrNull { it.startMin() } ?: 8 * 60
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        scroll.scrollTo(with(density) { (hourDp.dp * ((first - 30).coerceAtLeast(0) / 60f)).roundToPx() })
    }

    // Responsive Root: Check schermbreedte én hoogte
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 350.dp
        val isShort = maxHeight < 500.dp
        val labelW = if (isCompact) 32.dp else 44.dp // Smallere tijd-as op kleine schermen
        val topPadding = if (isShort) 2.dp else 6.dp // Krimp verticale marges

        Column(Modifier.fillMaxSize()) {
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = topPadding), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ searching = false; query = "" }) { Icon(Icons.Filled.Close, contentDescription = tr("cancel")) }
                    TextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                        placeholder = { Text(t2("Zoeken...", "Search...")) },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                }
                SearchResults(query, events, loc, isCompact) { selected = it }
            } else {
                // Header (schaalt mee in marges en tekstgrootte)
                Row(
                    Modifier.fillMaxWidth().padding(start = if (isCompact) 8.dp else 16.dp, end = 4.dp, top = topPadding, bottom = topPadding), 
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val title = if (view == CalView.D1) day.format(DateTimeFormatter.ofPattern("EEEE d MMM", loc)) else day.format(DateTimeFormatter.ofPattern("LLLL yyyy", loc))
                    val titleStyle = if (isCompact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
                    
                    Text(title.cap(), style = titleStyle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton({ searching = true }) { Icon(Icons.Filled.Search, contentDescription = null) }
                    IconButton({ val t = LocalDate.now(zone); day = if (view != CalView.D7 && view != CalView.LIST) settleDay(t) else t }) {
                        Icon(Icons.Filled.DateRange, contentDescription = tr("today")) }
                }

                // Adaptive Pills Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isCompact) 8.dp else 12.dp, vertical = topPadding)
                        // Als het scherm te smal is, maken we de pillen horizontaal scrollbaar ipv ze plat te drukken
                        .then(if (isCompact) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CalView.values().forEach { v ->
                        val isSelected = view == v
                        val bg by animateColorAsState(if (isSelected) cs.primary else cs.surfaceVariant, label = "pill_bg")
                        val fg by animateColorAsState(if (isSelected) cs.onPrimary else cs.onSurfaceVariant, label = "pill_fg")
                        
                        // Op compacte schermen met scroll geven we een minWidth ipv weight
                        val pillModifier = if (isCompact) Modifier.widthIn(min = 54.dp) else Modifier.weight(1f)

                        Surface(
                            onClick = { view = v; if (v != CalView.LIST && v != CalView.D7) day = settleDay(day) },
                            shape = RoundedCornerShape(20.dp),
                            color = bg,
                            modifier = pillModifier.height(if (isShort) 30.dp else 36.dp) // compacter in hoogte indien short
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text(
                                    text = viewName(v).cap(),
                                    color = fg,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                if (view == CalView.LIST) AgendaList(day, events, loc, tr("noevents"), isCompact) { selected = it }
                else PagedGrid(Modifier.weight(1f), view, day, events, scroll, hourDp.dp, loc, labelW,
                    onZoom = { z, fy -> zoom(z, fy) },
                    onZoomEnd = { ctx.prefs.edit().putFloat("hourDp", hourDp).apply() },
                    onShift = { shift(it) }, onOpen = { selected = it }, onDay = { day = it; view = CalView.D1 })
            }
        }
    }

    selected?.let { e ->
        val pr = e.parsed()
        val eColor = e.color()
        var showRaw by remember(e.uid) { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { selected = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(eColor)
                            .clickable { colorPickerEvent = e }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(e.title(), Modifier.weight(1f))
                    IconButton({ showRaw = !showRaw }, Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Code, contentDescription = "Raw", Modifier.size(20.dp),
                            tint = if (showRaw) cs.primary else cs.onSurfaceVariant)
                    }
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(e.startDt().format(DateTimeFormatter.ofPattern("EEEE d MMMM", loc)).cap() + "\n" + e.timeText())

                    if (showRaw) {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("SUMMARY" to e.summary, "LOCATION" to e.location, "DESCRIPTION" to e.desc).forEach { (k, v) ->
                                    Column {
                                        Text(k, style = MaterialTheme.typography.labelSmall, color = cs.primary)
                                        Text(rawClean(v).ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    } else {
                        if (pr.soort.isNotEmpty()) InfoRow(Icons.Filled.Info, pr.soort)
                        if (pr.groep.isNotEmpty()) InfoRow(Icons.Filled.Group, pr.groep)
                        if (pr.lesinfo.isNotEmpty()) InfoRow(Icons.Filled.Book, pr.lesinfo)
                        if (pr.rooms.isNotEmpty()) InfoRow(Icons.Filled.LocationOn, pr.rooms.joinToString("\n"))
                        if (pr.teachers.isNotEmpty()) InfoRow(Icons.Filled.Person, pr.teachers.joinToString(", "))
                        if (pr.overig.isNotEmpty()) {
                            Text(pr.overig, style = MaterialTheme.typography.labelSmall, color = cs.onSurface.copy(alpha = 0.45f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    Store.saveEvents(ctx, Store.events(ctx).map { if (it.uid == e.uid) it.copy(deleted = true, deletedAt = System.currentTimeMillis()) else it })
                    scheduleAlarms(ctx, Store.events(ctx)); AppState.version++; selected = null
                }) { Text(tr("delete")) }
            },
            dismissButton = { TextButton({ selected = null }) { Text(tr("cancel")) } }
        )
    }

    // Color Picker Dialog
    colorPickerEvent?.let { e ->
        val current = e.color()
        AlertDialog(
            onDismissRequest = { colorPickerEvent = null },
            title = { Text(t2("Kies een kleur", "Choose a color"), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(e.title(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        // heightIn voorkomt dat dialog knoppen buiten een klein scherm geduwd worden
                        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp) 
                    ) {
                        items(EXTENDED_PALETTE) { color ->
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (current == color) 3.dp else 0.dp,
                                        color = if (current == color) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        setCourseColor(ctx, e.courseId(), color)
                                        AppState.version++
                                        colorPickerEvent = null
                                    }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { colorPickerEvent = null }) { Text(tr("cancel")) }
            }
        )
    }
}

@Composable
private fun SearchResults(query: String, events: List<Event>, loc: Locale, isCompact: Boolean, onOpen: (Event) -> Unit) {
    val tokens = query.lowercase().split(" ").filter { it.isNotBlank() }
    if (tokens.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(t2("Typ om te zoeken", "Type to search"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val fmt = DateTimeFormatter.ofPattern("EEEE d MMMM", loc)
    val hits = remember(query, events) {
        events.filter { e ->
            val hay = listOf(e.summary, e.location, e.desc, e.title(), e.pin(), e.date().format(fmt)).joinToString(" ").lowercase()
            tokens.all { it in hay }
        }
    }
    AgendaList(null, hits, loc, t2("Geen resultaten", "No results"), isCompact, onOpen)
}

private fun Modifier.pinchZoom(onZoom: (Float, Float) -> Unit, onEnd: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoomed = false
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val pts = ev.changes.filter { it.pressed }
            if (pts.size >= 2) {
                val a = pts[0]; val b = pts[1]
                val prev = abs(a.previousPosition.y - b.previousPosition.y)
                val cur = abs(a.position.y - b.position.y)
                if (prev > 10f && cur > 10f) {
                    onZoom(cur / prev, (a.position.y + b.position.y) / 2f)
                    zoomed = true
                }
                ev.changes.forEach { it.consume() }
            }
        } while (ev.changes.any { it.pressed })
        if (zoomed) onEnd()
    }
}

@Composable
private fun PagedGrid(modifier: Modifier, view: CalView, day: LocalDate, events: List<Event>, scroll: ScrollState, hourH: Dp, loc: Locale, labelW: Dp,
                      onZoom: (Float, Float) -> Unit, onZoomEnd: () -> Unit, onShift: (Int) -> Unit,
                      onOpen: (Event) -> Unit, onDay: (LocalDate) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val today = LocalDate.now(zone)
    val off = remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val pages = (-1..1).map { pageDays(view, day, events, it) }
    val zoomCb = rememberUpdatedState(onZoom); val endCb = rememberUpdatedState(onZoomEnd); val shiftCb = rememberUpdatedState(onShift)
    val dragState = rememberDraggableState { d -> off.floatValue = (off.floatValue + d).coerceIn(-widthPx.toFloat(), widthPx.toFloat()) }

    Column(modifier.fillMaxWidth().draggable(dragState, Orientation.Horizontal,
        onDragStarted = { job?.cancel() },
        onDragStopped = { v ->
            job = scope.launch {
                val w = widthPx.toFloat(); val o = off.floatValue
                val dir = if (o > w * 0.3f || v > 1000f) 1 else if (o < -w * 0.3f || v < -1000f) -1 else 0
                animate(o, dir * w, v, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)) { value, _ -> off.floatValue = value }
                if (dir != 0) shiftCb.value(-dir)
                off.floatValue = 0f
            }
        })) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(labelW))
            Box(Modifier.weight(1f).clipToBounds().onSizeChanged { widthPx = it.width }) {
                for (i in -1..1) Row(Modifier.fillMaxWidth().offset { IntOffset(off.floatValue.roundToInt() + i * widthPx, 0) }) {
                    pages[i + 1].forEach { d -> DayHeader(d, today, loc, Modifier.weight(1f)) { onDay(d) } }
                }
            }
        }
        HorizontalDivider(color = cs.outlineVariant)
        Row(Modifier.fillMaxWidth().weight(1f).pinchZoom({ z, fy -> zoomCb.value(z, fy) }, { endCb.value() }).verticalScroll(scroll)) {
            Column(Modifier.width(labelW)) {
                for (h in 0 until 24) Box(Modifier.height(hourH)) {
                    // Op een smal scherm gebruiken we bijv '09' ipv '09:00'
                    val timeFormat = if (labelW < 40.dp) "%02d".format(h) else "%02d:00".format(h)
                    if (h > 0) Text(timeFormat, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.offset(y = (-8).dp).padding(start = 4.dp))
                }
            }
            Box(Modifier.weight(1f).height(hourH * 24).clipToBounds()) {
                for (i in -1..1) {
                    val ds = pages[i + 1]
                    Row(Modifier.fillMaxSize().offset { IntOffset(off.floatValue.roundToInt() + i * widthPx, 0) }) {
                        ds.forEach { d -> DayColumn(d, events, hourH, ds.size > 2, today, Modifier.weight(1f), onOpen) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(d: LocalDate, today: LocalDate, loc: Locale, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.clickable { onClick() }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, loc).uppercase(), style = MaterialTheme.typography.labelSmall, color = if (d == today) cs.primary else cs.onSurfaceVariant)
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (d == today) cs.primary else Color.Transparent), contentAlignment = Alignment.Center) {
            Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = if (d == today) cs.onPrimary else cs.onSurface)
        }
    }
}

@Composable
private fun DayColumn(d: LocalDate, events: List<Event>, hourH: Dp, compact: Boolean, today: LocalDate, modifier: Modifier, onOpen: (Event) -> Unit) {
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val w = maxWidth
        for (h in 0 until 24) HorizontalDivider(Modifier.offset(y = hourH * h), color = cs.outlineVariant.copy(alpha = 0.6f))
        Box(Modifier.width(1.dp).fillMaxHeight().background(cs.outlineVariant.copy(alpha = 0.6f)))
        val minMin = (MIN_H.value / hourH.value * 60f).toLong().coerceAtLeast(15L)
        
        layoutDay(events.filter { it.date() == d }, minMin).forEach { p ->
            val e = p.e
            val dur = ((e.end - e.start) / 60_000).toInt()
            val h = maxOf(hourH * dur / 60, MIN_H)
            val colW = w / p.cols
            val lineH = if (compact) 12f else 15f
            val lines = ((h.value - 4f) / (lineH + 1f)).toInt().coerceAtLeast(1)
            val eventColor = e.color()
            
            // Verberg tekst compleet als de kolom belachelijk smal wordt
            val showText = colW > 35.dp

            Column(Modifier.offset(x = colW * p.col + 1.dp, y = hourH * e.startMin() / 60 + 1.dp)
                .width(colW - 2.dp).height(h - 2.dp)
                .clip(RoundedCornerShape(6.dp)).background(eventColor)
                .clickable { onOpen(e) }.padding(horizontal = if (showText) 4.dp else 1.dp, vertical = 2.dp)) {
                
                if (showText) {
                    Text(e.title(), color = Color.White, fontWeight = FontWeight.Medium,
                        fontSize = if (compact) 10.sp else 13.sp, lineHeight = lineH.sp, maxLines = lines, overflow = TextOverflow.Ellipsis)
                    if (e.pin().isNotEmpty()) PinText(e.pin(), Color.White.copy(alpha = 0.9f), if (compact) 9f else 11f)
                }
            }
        }
        if (d == today) {
            val nowMin = LocalTime.now(zone).let { it.hour * 60 + it.minute }
            Box(Modifier.offset(y = hourH * nowMin / 60).fillMaxWidth().height(2.dp).background(Color.Red))
            Box(Modifier.offset(x = (-4).dp, y = hourH * nowMin / 60 - 4.dp).size(10.dp).clip(CircleShape).background(Color.Red))
        }
    }
}

@Composable
private fun AgendaList(from: LocalDate?, events: List<Event>, loc: Locale, empty: String, isCompact: Boolean, onOpen: (Event) -> Unit) {
    val grouped = events.sortedBy { it.start }.groupBy { it.date() }
    val state = rememberLazyListState()
    val cs = MaterialTheme.colorScheme
    val today = LocalDate.now(zone)
    val dates = remember(grouped) { grouped.keys.toList() }
    val targetDate = from ?: today
    LaunchedEffect(dates, targetDate) {
        val i = dates.indexOfFirst { it >= targetDate }
        if (i >= 0) state.scrollToItem(i)
    }
    if (grouped.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(empty) }; return }
    
    LazyColumn(state = state, contentPadding = PaddingValues(horizontal = if(isCompact) 8.dp else 12.dp, vertical = 8.dp)) {
        grouped.forEach { (d, list) ->
            item(key = d.toString()) {
                // Adaptive layout: Op een klein scherm zetten we de datum Bóven de events ipv ernaast
                if (isCompact) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)) {
                            Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, loc).uppercase(), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(28.dp).clip(CircleShape).background(if (d == today) cs.primary else Color.Transparent), contentAlignment = Alignment.Center) {
                                Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = if (d == today) cs.onPrimary else cs.onSurface)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            list.forEach { e -> EventCard(e, onOpen) }
                        }
                    }
                } else {
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, loc).uppercase(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            Box(Modifier.size(36.dp).clip(CircleShape).background(if (d == today) cs.primary else Color.Transparent), contentAlignment = Alignment.Center) {
                                Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = if (d == today) cs.onPrimary else cs.onSurface)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            list.forEach { e -> EventCard(e, onOpen) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCard(e: Event, onOpen: (Event) -> Unit) {
    val eColor = e.color()
    val pr = e.parsed()
    val grp = pr.groep.takeUnless { it.isEmpty() || pr.lesinfo.contains(it, ignoreCase = true) }.orEmpty()
    val subDetails = listOf(pr.soort, grp, pr.lesinfo).filter { it.isNotEmpty() }.joinToString(" • ")

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(eColor).clickable { onOpen(e) }.padding(10.dp)) {
        Text(e.timeText(), color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
        Text(e.title(), color = Color.White, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
        if (subDetails.isNotEmpty()) {
            Text(subDetails, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
        }
        if (e.pin().isNotEmpty()) PinText(e.pin(), Color.White.copy(alpha = 0.9f), 12f)
    }
}
