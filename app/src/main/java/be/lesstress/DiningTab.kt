package be.lesstress

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import be.lesstress.providers.AppProvider
import be.lesstress.providers.MenuProviderRegistry
import be.lesstress.providers.restaurants.almaOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

data class HRow(val date: LocalDate, val ranges: List<Pair<LocalTime, LocalTime>>, val note: String? = null)
data class HSection(val title: String, val rows: List<HRow>)

data class Dish(
    val name: String,
    val price: String?,
    val img: String?,
    val isVegan: Boolean = false,
    val isVegetarian: Boolean = false
)
data class MenuSec(val title: String, val soup: Boolean, val dishes: List<Dish>)
data class MenuData(
    val date: LocalDate?,
    val dates: List<LocalDate>,
    val sections: List<MenuSec>,
    val unavailable: Boolean = false
)

sealed class MenuContent {
    data class OrderButton(val orderUrl: String, val buttonLabel: () -> String) : MenuContent()
    data class FullMenu(
        val siteUrl: String,
        val fetchDates: suspend () -> List<LocalDate>,
        val fetchMenu: suspend (LocalDate?) -> MenuData
    ) : MenuContent()
    data class OnlineMenuLink(val menuUrl: String, val buttonLabel: () -> String) : MenuContent()
}

interface MenuProvider : AppProvider {
    suspend fun fetchHours(today: LocalDate): List<HSection>
    fun content(): MenuContent
}

private fun computeDiningStatus(secs: List<HSection>, now: LocalDateTime, loc: Locale): Status? {
    if (secs.isEmpty()) return null
    val allRows = secs.flatMap { it.rows }
    val today = now.toLocalDate()
    val todayRows = allRows.filter { it.date == today }
    val todays = todayRows.flatMap { it.ranges }.sortedBy { it.first }
    val t = now.toLocalTime()
    val todayNote = todayRows.firstOrNull { !it.note.isNullOrBlank() }?.note

    todays.firstOrNull { t >= it.first && t < it.second }?.let { r ->
        val mins = Duration.between(t, r.second).toMinutes()
        val noteSuffix = if (todayNote != null) " · $todayNote" else ""
        return if (mins <= 30) Status(1, tr("open"), s("Sluit over $mins min (om ${hhmm(r.second)})$noteSuffix", "Closes in $mins min (at ${hhmm(r.second)})$noteSuffix"))
        else Status(0, tr("open"), s("Sluit om ${hhmm(r.second)}$noteSuffix", "Closes at ${hhmm(r.second)}$noteSuffix"))
    }
    todays.firstOrNull { it.first > t }?.let {
        val noteSuffix = if (todayNote != null) " · $todayNote" else ""
        return Status(2, tr("closed"), s("Opent om ${hhmm(it.first)}$noteSuffix", "Opens at ${hhmm(it.first)}$noteSuffix"))
    }

    val byDate = allRows.groupBy({ it.date }, { it.ranges }).mapValues { e -> e.value.flatten().sortedBy { it.first } }
    val next = byDate.filter { it.key > today && it.value.isNotEmpty() }.minByOrNull { it.key }
    if (next == null) {
        val detail = todayNote ?: s("Vandaag gesloten", "Closed today")
        val label = if (todayNote?.contains("onzeker", ignoreCase = true) == true) s("Onzeker", "Uncertain") else tr("closed")
        val kind = if (label == tr("closed")) 2 else 1
        return Status(kind, label, detail)
    }
    val whenText = if (next.key == today.plusDays(1)) s("morgen", "tomorrow")
    else next.key.dayOfWeek.getDisplayName(TextStyle.SHORT, loc) + " " + next.key.dayOfMonth + "/" + next.key.monthValue
    val closedDetail = if (todayNote != null) "$todayNote · " + s("Opent $whenText om ${hhmm(next.value.first().first)}", "Opens $whenText at ${hhmm(next.value.first().first)}")
    else s("Opent $whenText om ${hhmm(next.value.first().first)}", "Opens $whenText at ${hhmm(next.value.first().first)}")
    val label = if (todayNote?.contains("onzeker", ignoreCase = true) == true) s("Onzeker", "Uncertain") else tr("closed")
    val kind = if (label == tr("closed")) 2 else 1
    return Status(kind, label, closedDetail)
}

private val brussels = ZoneId.of("Europe/Brussels")
private val imageCache = LruCache<String, ImageBitmap>(48)

@Composable
private fun RemoteImage(url: String, modifier: Modifier, placeholder: @Composable () -> Unit) {
    var img by remember(url) { mutableStateOf(imageCache.get(url)) }
    LaunchedEffect(url) {
        if (img == null) {
            val bmp = withContext(Dispatchers.IO) { runCatching { almaOpen(url).inputStream.use { BitmapFactory.decodeStream(it) } }.getOrNull() }
            if (bmp != null) { val ib = bmp.asImageBitmap(); imageCache.put(url, ib); img = ib }
        }
    }
    val i = img
    if (i != null) Image(i, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit) else placeholder()
}

@Composable
private fun PlateIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val r = size.minDimension / 2f
    drawCircle(color = color, radius = r * 0.92f, style = Stroke(r * 0.10f))
    drawCircle(color = color, radius = r * 0.58f, style = Stroke(r * 0.07f))
}

@Composable
private fun BowlIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    drawArc(color = color, startAngle = 0f, sweepAngle = 180f, useCenter = true, topLeft = Offset(w * 0.10f, h * 0.30f), size = Size(w * 0.80f, h * 0.56f))
    drawLine(color = color, start = Offset(w * 0.06f, h * 0.58f), end = Offset(w * 0.94f, h * 0.58f), strokeWidth = h * 0.07f, cap = StrokeCap.Round)
    listOf(0.34f, 0.5f, 0.66f).forEach { x ->
        val p = Path().apply { moveTo(w * x, h * 0.46f); cubicTo(w * (x - 0.06f), h * 0.38f, w * (x + 0.06f), h * 0.30f, w * x, h * 0.18f) }
        drawPath(p, color = color, style = Stroke(h * 0.05f, cap = StrokeCap.Round))
    }
}

@Composable
fun DiningTab() {
    val ctx = LocalContext.current
    val loc = appLocale()
    var reload by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(LocalDateTime.now(brussels)) }
    LaunchedEffect(Unit) { while (isActive) { delay(30_000); now = LocalDateTime.now(brussels) } }

    val providers = remember(AppState.version) { MenuProviderRegistry.activeOrdered(ctx) }
    fun launchUrl(url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("dining"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton({ reload++ }, Modifier.size(36.dp)) { Icon(Icons.Filled.Refresh, contentDescription = s("Vernieuwen", "Refresh"), Modifier.size(20.dp)) }
        }

        if (providers.isEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Text(
                    s("Geen eetgelegenheden ingeschakeld. Schakel aanbieders in via Instellingen.", "No dining providers enabled. Enable providers in Settings."),
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
                    MenuProviderBlock(provider, now, loc, reload, onRetry = { reload++ }, onLaunch = ::launchUrl)
                }
            }
        }
    }
}

@Composable
private fun MenuProviderBlock(
    provider: MenuProvider,
    now: LocalDateTime,
    loc: Locale,
    reload: Int,
    onRetry: () -> Unit,
    onLaunch: (String) -> Unit
) {
    var hours by remember(provider.id) { mutableStateOf<List<HSection>?>(null) }
    LaunchedEffect(provider.id, AppState.version, reload) {
        hours = runCatching { provider.fetchHours(now.toLocalDate()) }.getOrDefault(emptyList())
    }

    when (val content = remember(provider.id) { provider.content() }) {
        is MenuContent.OrderButton -> {
            VenueCard(provider.displayName(), hours, now, loc) {
                OutlinedButton({ onLaunch(content.orderUrl) }, Modifier.fillMaxWidth().height(44.dp)) {
                    Icon(Icons.Filled.ShoppingCart, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(content.buttonLabel(), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        is MenuContent.OnlineMenuLink -> {
            VenueCard(provider.displayName(), hours, now, loc) {
                OutlinedButton({ onLaunch(content.menuUrl) }, Modifier.fillMaxWidth().height(44.dp)) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(content.buttonLabel(), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        is MenuContent.FullMenu -> {
            var dates by remember(provider.id, reload) { mutableStateOf<List<LocalDate>?>(null) }
            var datesError by remember(provider.id, reload) { mutableStateOf<String?>(null) }
            val cache = remember(provider.id) { HashMap<String, MenuData>() }
            LaunchedEffect(provider.id, AppState.version, reload) {
                datesError = null
                runCatching { content.fetchDates() }
                    .onSuccess { dates = it }
                    .onFailure { e ->
                        datesError = e.message ?: "offline"
                        dates = emptyList()
                    }
            }
            VenueCard(provider.displayName(), hours, now, loc)
            // Only show menu pager when the restaurant isn't definitively closed today
            val status = hours?.let { computeDiningStatus(it, now, loc) }
            val closedToday = status != null && status.kind == 2 && hours?.flatMap { sec -> sec.rows.filter { it.date == now.toLocalDate() }.flatMap { it.ranges } }.isNullOrEmpty()
            if (!closedToday) {
                MenuPagerBlock(
                    dates = dates?.filter { it >= now.toLocalDate() },
                    datesError = datesError,
                    today = now.toLocalDate(),
                    loc = loc,
                    cache = cache,
                    reload = reload,
                    fetchMenu = content.fetchMenu,
                    onRetry = onRetry,
                    onSite = { onLaunch(content.siteUrl) }
                )
            }
        }
    }
}

@Composable
private fun VenueCard(
    name: String,
    hours: List<HSection>?,
    now: LocalDateTime,
    loc: Locale,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val status = hours?.let { computeDiningStatus(it, now, loc) }
    Card(Modifier.fillMaxWidth().animateContentSize(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (status != null) StatusPill(status) else if (hours == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (status != null) Text(status.detail, color = fg(status.kind), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            else if (hours != null) Text(s("Openingsuren niet beschikbaar", "Opening hours unavailable"), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            extra()
            if (!hours.isNullOrEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tr("hours"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, Modifier.size(18.dp))
                }
                if (expanded) HoursList(hours, now.toLocalDate(), loc)
            }
        }
    }
}

@Composable
private fun HoursList(sections: List<HSection>, today: LocalDate, loc: Locale) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        sections.forEach { sec ->
            if (sections.size > 1) Text(sec.title, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            sec.rows.forEach { r ->
                val isToday = r.date == today
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (isToday) cs.secondaryContainer else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        r.date.dayOfWeek.getDisplayName(TextStyle.SHORT, loc).capf() + " " + r.date.dayOfMonth + "/" + r.date.monthValue,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                    )
                    if (!r.note.isNullOrBlank()) {
                        val color = if (r.ranges.isEmpty()) RED else AMBER
                        val text = if (r.ranges.isEmpty()) r.note else "${r.ranges.joinToString(" · ") { hhmm(it.first) + "–" + hhmm(it.second) }} (${r.note})"
                        Text(text, color = color, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    } else if (r.ranges.isEmpty()) {
                        Text(tr("closed"), color = RED, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    } else {
                        Text(
                            r.ranges.joinToString(" · ") { hhmm(it.first) + "–" + hhmm(it.second) },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuPagerBlock(
    dates: List<LocalDate>?,
    datesError: String?,
    today: LocalDate,
    loc: Locale,
    cache: HashMap<String, MenuData>,
    reload: Int,
    fetchMenu: suspend (LocalDate?) -> MenuData,
    onRetry: () -> Unit,
    onSite: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val coroutineScope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s("Op het menu", "On the menu"), style = MaterialTheme.typography.titleMedium)

        when {
            dates == null -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            datesError != null && dates.isEmpty() -> {
                InfoCard(
                    s("Menu kon niet geladen worden", "Could not load menu"),
                    datesError,
                    s("Opnieuw", "Retry"),
                    onRetry,
                    warning = true
                )
            }
            dates.isNotEmpty() -> {
                val pagerState = rememberPagerState(initialPage = 0) { dates.size }
                val activeDate = dates.getOrNull(pagerState.currentPage) ?: today

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                            enabled = pagerState.currentPage > 0
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = if (pagerState.currentPage > 0) cs.primary else cs.outlineVariant)
                        }

                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            val dayText = when (activeDate) {
                                today -> tr("today").capf()
                                today.plusDays(1) -> s("Morgen", "Tomorrow")
                                else -> activeDate.dayOfWeek.getDisplayName(TextStyle.FULL, loc).capf()
                            }
                            Text(dayText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.primary)
                            Text("${activeDate.dayOfMonth} ${activeDate.month.getDisplayName(TextStyle.FULL, loc)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }

                        IconButton(
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                            enabled = pagerState.currentPage < dates.size - 1
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = if (pagerState.currentPage < dates.size - 1) cs.primary else cs.outlineVariant)
                        }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().animateContentSize(),
                    pageSpacing = 12.dp,
                    verticalAlignment = Alignment.Top
                ) { page ->
                    val pageDate = dates[page]
                    var pageMenu by remember(pageDate, reload) { mutableStateOf<MenuData?>(cache["${AppState.version}-$reload-$pageDate"]) }
                    var loading by remember(pageDate, reload) { mutableStateOf(pageMenu == null) }
                    var error by remember(pageDate, reload) { mutableStateOf<String?>(null) }

                    LaunchedEffect(pageDate, reload) {
                        if (pageMenu == null) {
                            loading = true; error = null
                            try {
                                val m = fetchMenu(if (page == 0) null else pageDate)
                                cache["${AppState.version}-$reload-$pageDate"] = m
                                pageMenu = m
                            } catch (e: Exception) { error = e.message ?: "offline" }
                            loading = false
                        }
                    }

                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        when {
                            error != null -> InfoCard(s("Menu kon niet geladen worden", "Could not load menu"), error, s("Opnieuw", "Retry"), onRetry, warning = true)
                            pageMenu != null && (pageMenu!!.unavailable || pageMenu!!.sections.isEmpty()) ->
                                InfoCard(s("Geen menu beschikbaar", "No menu available"), s("Het restaurant is mogelijk gesloten of het menu staat nog niet online.", "The restaurant may be closed or the menu isn't online yet."), tr("menu"), onSite)
                            pageMenu != null -> {
                                pageMenu!!.sections.forEach { sec ->
                                    Text(sec.title, style = MaterialTheme.typography.labelLarge, color = cs.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                                        Column(Modifier.padding(vertical = 4.dp)) {
                                            sec.dishes.forEachIndexed { index, dish ->
                                                DishRow(dish, sec.soup)
                                                if (index < sec.dishes.lastIndex) HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp, color = cs.outlineVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                InfoCard(s("Geen aankomende menu's gevonden", "No upcoming menus found"), s("Bekijk de volledige kalender op de Alma-website.", "Check the full calendar on the Alma website."), tr("menu"), onSite)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, detail: String?, action: String, onAction: () -> Unit, warning: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (warning) Icon(Icons.Filled.Warning, contentDescription = null, tint = RED) else PlateIcon(cs.outline, Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onAction) { Text(action) }
        }
    }
}

@Composable
private fun DishRow(d: Dish, soup: Boolean) {
    val cs = MaterialTheme.colorScheme
    val vegBg = statusBg(0)
    val vegFg = statusFg(0)
    Row(
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(if (d.img == null) Modifier.background(cs.surfaceContainerHighest) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            val placeholder: @Composable () -> Unit = {
                if (soup) BowlIcon(cs.outline, Modifier.size(32.dp)) else PlateIcon(cs.outline, Modifier.size(32.dp))
            }
            if (d.img != null) RemoteImage(d.img, Modifier.fillMaxSize(), placeholder) else placeholder()
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (d.price != null) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(cs.primaryContainer).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(d.price, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                    }
                }
                if (d.isVegan) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(vegBg).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text(s("Vegan", "Vegan"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = vegFg)
                    }
                } else if (d.isVegetarian) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(vegBg).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text(s("Veggie", "Veggie"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = vegFg)
                    }
                }
            }
        }
    }
}
