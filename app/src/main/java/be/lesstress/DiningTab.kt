package be.lesstress

// DiningTab.kt = KULAK Restaurant & Broodjesbar

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
import androidx.compose.material.icons.automirrored.filled.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.*
import java.time.format.TextStyle
import java.util.Locale

/* =====================  1. ADDRESSES & URLS  ===================== */

const val RESTAURANT_URL = "https://www.alma.be/nl/restaurants/kulak-restaurant"
const val ORDER_URL = "https://orderbilly.com/alma-kulak"
private const val ALMA = "https://www.alma.be"

private const val REST_HOURS_URL = "https://www.alma.be/index.php?p=actions/sprig-core/components/render&renderFull=true&sprig%3AsiteId=f917f933ee16d5a085aba64c8949a493baa01c526306bd394c2da00c06344eeb1&sprig%3Aid=0e50d261f2360e519c25e9a5d71e91ce58b0fe205e99804f1f4a192d4c3975f5heroTimes-121027&sprig%3Acomponent=1e9703ec58575ea02b01220099d79775ab5ba1b44b3fdafecd38fe3f531a1d3c&sprig%3Atemplate=41520ac5763104b9a86297981e0c494436a4c6dfe38242cc2af4dc9e1163b2cb_snippet%2F_calendar%2F_heroTimes.twig&sprig%3Avariables%5BentryId%5D=35f481f98b14734d4d5cda4ffb1db2484f82af9f5344b90131cd628a56a66beb121027"
private const val BROOD_HOURS_URL = "https://www.alma.be/index.php?p=actions/sprig-core/components/render&renderFull=true&sprig%3AsiteId=f917f933ee16d5a085aba64c8949a493baa01c526306bd394c2da00c06344eeb1&sprig%3Aid=53f9d9e14ab5147270b0485224202c755d3934473b82f594cf59f2461f9cbbffheroTimes-122556&sprig%3Acomponent=1e9703ec58575ea02b01220099d79775ab5ba1b44b3fdafecd38fe3f531a1d3c&sprig%3Atemplate=41520ac5763104b9a86297981e0c494436a4c6dfe38242cc2af4dc9e1163b2cb_snippet%2F_calendar%2F_heroTimes.twig&sprig%3Avariables%5BentryId%5D=0c207b9c96dcb16a65a6b395db9b04178a7ca0f56c6db13b44e09753240cb6bb122556"

private val brussels = ZoneId.of("Europe/Brussels")

/* =====================  2. DOWNLOADING  ===================== */

private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 10_000; readTimeout = 15_000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LesStress")
    setRequestProperty("Accept-Language", "nl-BE,nl;q=0.9,en;q=0.8")
}

private suspend fun almaGet(url: String): String = withContext(Dispatchers.IO) { open(url).inputStream.bufferedReader().use { it.readText() } }

private suspend fun sprigGet(url: String, targetId: String): String = withContext(Dispatchers.IO) {
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LesStress")
        setRequestProperty("Accept-Language", "nl-BE,nl;q=0.9,en;q=0.8")
        setRequestProperty("Accept", "*/*")
        setRequestProperty("Cache-Control", "no-cache")
        setRequestProperty("hx-current-url", RESTAURANT_URL)
        setRequestProperty("hx-request", "true")
        setRequestProperty("hx-target", targetId)
        setRequestProperty("hx-trigger", targetId)
        setRequestProperty("Referer", RESTAURANT_URL)
    }.inputStream.bufferedReader().use { it.readText() }
}

private val imageCache = LruCache<String, ImageBitmap>(48)

@Composable
private fun RemoteImage(url: String, modifier: Modifier, placeholder: @Composable () -> Unit) {
    var img by remember(url) { mutableStateOf(imageCache.get(url)) }
    LaunchedEffect(url) {
        if (img == null) {
            val bmp = withContext(Dispatchers.IO) { runCatching { open(url).inputStream.use { BitmapFactory.decodeStream(it) } }.getOrNull() }
            if (bmp != null) { val ib = bmp.asImageBitmap(); imageCache.put(url, ib); img = ib }
        }
    }
    val i = img
    if (i != null) Image(i, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit) else placeholder()
}

/* =====================  3. PARSING THE ALMA HTML  ===================== */

private fun clean(s: String) = s.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
    .replace("&#039;", "'").replace("&quot;", "\"").replace("&euro;", "€").replace("&lt;", "<").replace("&gt;", ">")
    .replace(Regex("\\s+"), " ").trim()

private data class HRow(val date: LocalDate, val ranges: List<Pair<LocalTime, LocalTime>>)
private data class HSection(val title: String, val rows: List<HRow>)

private fun lt(h: Int, m: Int) = if (h >= 24) LocalTime.of(23, 59) else LocalTime.of(h, m)

private fun parseHours(html: String, today: LocalDate): List<HSection> {
    val sectionRe = Regex("(?s)<strong[^>]*class=\"[^\"]*uppercase[^\"]*\"[^>]*>(.*?)</strong>.*?<dl[^>]*>(.*?)</dl>")
    val dlOnlyRe = Regex("(?s)<dl[^>]*>(.*?)</dl>")
    val rowRe = Regex("(?s)<dt[^>]*>(.*?)</dt>\\s*<dd[^>]*>(.*?)</dd>")
    val dayRe = Regex("(\\d{1,2})/(\\d{1,2})")
    val timeRe = Regex("(\\d{1,2})[:.](\\d{2})\\s*[–—-]\\s*(\\d{1,2})[:.](\\d{2})")

    val sections = sectionRe.findAll(html).map { sec ->
        val title = clean(sec.groupValues[1])
        val rows = rowRe.findAll(sec.groupValues[2]).mapNotNull { r ->
            val d = dayRe.find(clean(r.groupValues[1])) ?: return@mapNotNull null
            var date = LocalDate.of(today.year, d.groupValues[2].toInt(), d.groupValues[1].toInt())
            if (date.isBefore(today.minusMonths(6))) date = date.plusYears(1)
            val ranges = timeRe.findAll(clean(r.groupValues[2])).map {
                lt(it.groupValues[1].toInt(), it.groupValues[2].toInt()) to lt(it.groupValues[3].toInt(), it.groupValues[4].toInt())
            }.toList()
            HRow(date, ranges)
        }.toList()
        HSection(title, rows)
    }.filter { it.rows.isNotEmpty() }.toList()

    if (sections.isNotEmpty()) return sections

    val dlMatches = dlOnlyRe.findAll(html).toList()
    if (dlMatches.isNotEmpty()) {
        val rows = dlMatches.flatMap { sec ->
            rowRe.findAll(sec.groupValues[1]).mapNotNull { r ->
                val d = dayRe.find(clean(r.groupValues[1])) ?: return@mapNotNull null
                var date = LocalDate.of(today.year, d.groupValues[2].toInt(), d.groupValues[1].toInt())
                if (date.isBefore(today.minusMonths(6))) date = date.plusYears(1)
                val ranges = timeRe.findAll(clean(r.groupValues[2])).map {
                    lt(it.groupValues[1].toInt(), it.groupValues[2].toInt()) to lt(it.groupValues[3].toInt(), it.groupValues[4].toInt())
                }.toList()
                HRow(date, ranges)
            }
        }.toList()
        if (rows.isNotEmpty()) return listOf(HSection("Openingsuren", rows))
    }

    return emptyList()
}

private fun computeDiningStatus(secs: List<HSection>, now: LocalDateTime, loc: Locale): Status? {
    if (secs.isEmpty()) return null
    val byDate = secs.flatMap { it.rows }.groupBy({ it.date }, { it.ranges }).mapValues { e -> e.value.flatten().sortedBy { it.first } }
    val today = now.toLocalDate(); val t = now.toLocalTime()
    val todays = byDate[today].orEmpty()
    todays.firstOrNull { t >= it.first && t < it.second }?.let { r ->
        val mins = Duration.between(t, r.second).toMinutes()
        return if (mins <= 30) Status(1, tr("open"), s("Sluit over $mins min (om ${hhmm(r.second)})", "Closes in $mins min (at ${hhmm(r.second)})"))
        else Status(0, tr("open"), s("Sluit om ${hhmm(r.second)}", "Closes at ${hhmm(r.second)}"))
    }
    todays.firstOrNull { it.first > t }?.let { return Status(2, tr("closed"), s("Opent om ${hhmm(it.first)}", "Opens at ${hhmm(it.first)}")) }
    val next = byDate.filter { it.key > today && it.value.isNotEmpty() }.minByOrNull { it.key }
        ?: return Status(2, tr("closed"), s("Vandaag gesloten", "Closed today"))
    val whenText = if (next.key == today.plusDays(1)) s("morgen", "tomorrow")
    else next.key.dayOfWeek.getDisplayName(TextStyle.SHORT, loc) + " " + next.key.dayOfMonth + "/" + next.key.monthValue
    return Status(2, tr("closed"), s("Opent $whenText om ${hhmm(next.value.first().first)}", "Opens $whenText at ${hhmm(next.value.first().first)}"))
}

private data class Dish(val name: String, val price: String?, val img: String?)
private data class MenuSec(val title: String, val soup: Boolean, val dishes: List<Dish>)
private data class Menu(val date: LocalDate?, val dates: List<LocalDate>, val sections: List<MenuSec>, val unavailable: Boolean = false)

private fun parseMenu(html: String): Menu {
    val dates = Regex("data-dates=\"([^\"]*)\"").find(html)?.groupValues?.get(1)?.let { raw ->
        Regex("\\d{4}-\\d{2}-\\d{2}").findAll(raw).map { LocalDate.parse(it.value) }.toList() } ?: emptyList()
    val shown = Regex("js-menu-date-picker[^>]*?value=\"(\\d{4}-\\d{2}-\\d{2})\"").find(html)?.groupValues?.get(1)?.let { LocalDate.parse(it) }

    val catRe = Regex("(?s)<div id=\"\\d+\" class=\"mt-8 font-normal[^\"]*\">\\s*<div>\\s*(.*?)\\s*\\(\\d+\\)\\s*</div>")
    val tokenRe = Regex("(?s)<div class=\"py-4 md:py-6\">\\s*<div[^>]*>(.*?)</div>|<div id=\"\\d+\"\\s+class=\"relative block[^\"]*\">")
    val priceRe = Regex("(?s)data-type=\"kul\"(?:(?!data-type=).)*?&euro;(\\d+)(?:<span>|</span>)?\\s*<span[^>]*>\\.(\\d+)</span>")
    val anyPriceRe = Regex("(?s)&euro;(\\d+)(?:<span>|</span>)?\\s*<span[^>]*>\\.(\\d+)</span>")

    val cats = catRe.findAll(html).toList()
    val sections = mutableListOf<MenuSec>()
    cats.forEachIndexed { i, c ->
        val name = clean(c.groupValues[1])
        val soup = name.contains("soep", true)
        if (!soup && !name.contains("warm", true)) return@forEachIndexed
        val seg = html.substring(c.range.last + 1, cats.getOrNull(i + 1)?.range?.first ?: html.length)
        val toks = tokenRe.findAll(seg).toList()
        val dishes = mutableListOf<Dish>()
        toks.forEachIndexed { k, t ->
            if (t.groupValues[1].isNotEmpty()) return@forEachIndexed
            val body = seg.substring(t.range.last + 1, toks.getOrNull(k + 1)?.range?.first ?: seg.length)
            val dishName = Regex("(?s)<h4[^>]*>(.*?)</h4>").find(body)?.groupValues?.get(1)?.let { clean(it) } ?: return@forEachIndexed
            val pm = priceRe.find(body) ?: anyPriceRe.find(body)
            val price = pm?.let { "€ " + it.groupValues[1] + "," + it.groupValues[2].padEnd(2, '0') }
            val img = Regex("srcset=\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.split(", ")?.mapNotNull { e ->
                val p = e.trim().split(" "); val w = p.lastOrNull()?.removeSuffix("w")?.toIntOrNull()
                if (w == null || p.size < 2) null else p[0] to w
            }?.minByOrNull { kotlin.math.abs(it.second - 400) }?.first?.let { if (it.startsWith("/")) ALMA + it else it }
            dishes += Dish(dishName, price, img)
        }
        if (dishes.isNotEmpty()) sections += MenuSec(if (soup) s("Soep", "Soup") else s("Warm buffet", "Hot buffet"), soup, dishes)
    }
    return Menu(shown, dates, sections)
}

/* =====================  4. SMALL DRAWN ICONS  ===================== */

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

/* =====================  5. UI  ===================== */

@Composable
fun DiningTab() {
    val ctx = LocalContext.current
    val loc = appLocale()
    var reload by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(LocalDateTime.now(brussels)) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalDateTime.now(brussels) } }

    var restHours by remember { mutableStateOf<List<HSection>?>(null) }
    var broodHours by remember { mutableStateOf<List<HSection>?>(null) }
    LaunchedEffect(AppState.version, reload) {
        restHours = runCatching { parseHours(sprigGet(REST_HOURS_URL, "heroTimes-121027"), now.toLocalDate()) }.getOrDefault(emptyList())
    }
    LaunchedEffect(AppState.version, reload) {
        broodHours = runCatching { parseHours(sprigGet(BROOD_HOURS_URL, "heroTimes-122556"), now.toLocalDate()) }.getOrDefault(emptyList())
    }

    var dates by remember(reload) { mutableStateOf(listOf<LocalDate>()) }
    val cache = remember { HashMap<String, Menu>() }

    LaunchedEffect(AppState.version, reload) {
        val key = "${AppState.version}-$reload-null"
        val m = cache[key] ?: withContext(Dispatchers.Default) { parseMenu(almaGet(RESTAURANT_URL)) }.also { cache[key] = it }
        if (m.dates.isNotEmpty()) dates = m.dates
    }

    fun launch(intent: Intent) { runCatching { ctx.startActivity(intent) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("dining"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton({ reload++ }, Modifier.size(36.dp)) { Icon(Icons.Filled.Refresh, contentDescription = s("Vernieuwen", "Refresh"), Modifier.size(20.dp)) }
        }

        VenueCard(tr("restaurant"), restHours, now, loc)

        MenuPagerBlock(
            dates = dates.filter { it >= now.toLocalDate() },
            today = now.toLocalDate(),
            loc = loc,
            cache = cache,
            reload = reload,
            onRetry = { reload++ },
            onSite = { launch(Intent(Intent.ACTION_VIEW, Uri.parse(RESTAURANT_URL))) }
        )

        VenueCard(tr("broodjes"), broodHours, now, loc) {
            Button({ launch(Intent(Intent.ACTION_VIEW, Uri.parse(ORDER_URL))) }, Modifier.fillMaxWidth().height(44.dp)) {
                Icon(Icons.Filled.ShoppingCart, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(tr("order"), style = MaterialTheme.typography.titleSmall)
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
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (isToday) cs.secondaryContainer else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(r.date.dayOfWeek.getDisplayName(TextStyle.SHORT, loc).capf() + " " + r.date.dayOfMonth + "/" + r.date.monthValue,
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
                    if (r.ranges.isEmpty()) Text(tr("closed"), color = RED, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    else Text(r.ranges.joinToString(" · ") { hhmm(it.first) + "–" + hhmm(it.second) }, style = MaterialTheme.typography.bodySmall, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun MenuPagerBlock(
    dates: List<LocalDate>,
    today: LocalDate,
    loc: Locale,
    cache: HashMap<String, Menu>,
    reload: Int,
    onRetry: () -> Unit,
    onSite: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val coroutineScope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s("Op het menu", "On the menu"), style = MaterialTheme.typography.titleMedium)

        if (dates.isNotEmpty()) {
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
                var pageMenu by remember(pageDate, reload) { mutableStateOf<Menu?>(cache["${AppState.version}-$reload-$pageDate"]) }
                var loading by remember(pageDate, reload) { mutableStateOf(pageMenu == null) }
                var error by remember(pageDate, reload) { mutableStateOf<String?>(null) }

                LaunchedEffect(pageDate, reload) {
                    if (pageMenu == null) {
                        loading = true; error = null
                        try {
                            val m = withContext(Dispatchers.Default) {
                                parseMenu(almaGet(if (page == 0) RESTAURANT_URL else "$RESTAURANT_URL?date=$pageDate"))
                            }
                            cache["${AppState.version}-$reload-$pageDate"] = m
                            pageMenu = m
                        } catch (e: Exception) { error = e.message ?: "offline" }
                        loading = false
                    }
                }

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

                    val m = pageMenu
                    when {
                        error != null && m == null -> InfoCard(s("Menu laden mislukt", "Could not load the menu"), error, s("Opnieuw", "Retry"), onRetry, warning = true)
                        m == null -> Unit
                        m.unavailable || m.sections.isEmpty() -> if (!loading) InfoCard(s("Geen menu beschikbaar voor deze dag", "No menu available for this day"), null, "alma.be", onSite)
                        else -> m.sections.forEach { sec ->
                            Text(sec.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = cs.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                                Column {
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
            if (d.price != null) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(cs.primaryContainer).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(d.price, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                }
            }
        }
    }
}