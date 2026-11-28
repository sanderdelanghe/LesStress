package be.lesstress.providers

import be.lesstress.Status
import be.lesstress.hhmm
import be.lesstress.s
import be.lesstress.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private const val ALMA_BASE = "https://www.alma.be"

internal fun almaOpen(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 10_000
    readTimeout = 15_000
    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LesStress")
    setRequestProperty("Accept-Language", "nl-BE,nl;q=0.9,en;q=0.8")
}

internal suspend fun almaGet(url: String): String = withContext(Dispatchers.IO) {
    almaOpen(url).inputStream.bufferedReader().use { it.readText() }
}

private fun cleanHtml(s: String) = s
    .replace(Regex("<[^>]+>"), " ")
    .replace("&nbsp;", " ").replace("&amp;", "&")
    .replace("&#039;", "'").replace("&quot;", "\"")
    .replace("&euro;", "€").replace("&lt;", "<").replace("&gt;", ">")
    .replace(Regex("\\s+"), " ").trim()

private fun lt(h: Int, m: Int) = if (h >= 24) LocalTime.of(23, 59) else LocalTime.of(h, m)

internal fun parseAlmaHours(html: String, today: LocalDate): List<HSection> {
    val sectionRe = Regex("(?s)<strong[^>]*class=\"[^\"]*uppercase[^\"]*\"[^>]*>(.*?)</strong>.*?<dl[^>]*>(.*?)</dl>")
    val dlOnlyRe = Regex("(?s)<dl[^>]*>(.*?)</dl>")
    val rowRe = Regex("(?s)<dt[^>]*>(.*?)</dt>\\s*<dd[^>]*>(.*?)</dd>")
    val dayRe = Regex("(\\d{1,2})/(\\d{1,2})")
    val timeRe = Regex("(\\d{1,2})[:.](\\d{2})\\s*[–—-]\\s*(\\d{1,2})[:.](\\d{2})")

    fun parseRows(dlBody: String) = rowRe.findAll(dlBody).mapNotNull { r ->
        val d = dayRe.find(cleanHtml(r.groupValues[1])) ?: return@mapNotNull null
        var date = LocalDate.of(today.year, d.groupValues[2].toInt(), d.groupValues[1].toInt())
        if (date.isBefore(today.minusMonths(6))) date = date.plusYears(1)
        val ranges = timeRe.findAll(cleanHtml(r.groupValues[2])).map {
            lt(it.groupValues[1].toInt(), it.groupValues[2].toInt()) to lt(it.groupValues[3].toInt(), it.groupValues[4].toInt())
        }.toList()
        HRow(date, ranges)
    }.toList()

    val sections = sectionRe.findAll(html).map { sec ->
        HSection(cleanHtml(sec.groupValues[1]), parseRows(sec.groupValues[2]))
    }.filter { it.rows.isNotEmpty() }.toList()

    if (sections.isNotEmpty()) return sections

    val rows = dlOnlyRe.findAll(html).flatMap { parseRows(it.groupValues[1]) }.toList()
    return if (rows.isNotEmpty()) listOf(HSection("Openingsuren", rows)) else emptyList()
}

internal fun parseAlmaMenu(html: String): MenuData {
    val dates = Regex("data-dates=\"([^\"]*)\"").find(html)?.groupValues?.get(1)?.let { raw ->
        Regex("\\d{4}-\\d{2}-\\d{2}").findAll(raw).map { LocalDate.parse(it.value) }.toList()
    } ?: emptyList()
    val shown = Regex("js-menu-date-picker[^>]*?value=\"(\\d{4}-\\d{2}-\\d{2})\"")
        .find(html)?.groupValues?.get(1)?.let { LocalDate.parse(it) }

    val catRe = Regex("(?s)<div id=\"\\d+\" class=\"mt-8 font-normal[^\"]*\">\\s*<div>\\s*(.*?)\\s*\\(\\d+\\)\\s*</div>")
    val tokenRe = Regex("(?s)<div class=\"py-4 md:py-6\">\\s*<div[^>]*>(.*?)</div>|<div id=\"\\d+\"\\s+class=\"relative block[^\"]*\">")
    val priceRe = Regex("(?s)data-type=\"kul\"(?:(?!data-type=).)*?&euro;(\\d+)(?:<span>|</span>)?\\s*<span[^>]*>\\.(\\d+)</span>")
    val anyPriceRe = Regex("(?s)&euro;(\\d+)(?:<span>|</span>)?\\s*<span[^>]*>\\.(\\d+)</span>")

    val cats = catRe.findAll(html).toList()
    val sections = mutableListOf<MenuSec>()
    cats.forEachIndexed { i, c ->
        val name = cleanHtml(c.groupValues[1])
        val soup = name.contains("soep", true)
        if (!soup && !name.contains("warm", true)) return@forEachIndexed
        val seg = html.substring(c.range.last + 1, cats.getOrNull(i + 1)?.range?.first ?: html.length)
        val toks = tokenRe.findAll(seg).toList()
        val dishes = mutableListOf<Dish>()
        toks.forEachIndexed { k, t ->
            if (t.groupValues[1].isNotEmpty()) return@forEachIndexed
            val body = seg.substring(t.range.last + 1, toks.getOrNull(k + 1)?.range?.first ?: seg.length)
            val dishName = Regex("(?s)<h4[^>]*>(.*?)</h4>").find(body)?.groupValues?.get(1)?.let { cleanHtml(it) } ?: return@forEachIndexed
            val pm = priceRe.find(body) ?: anyPriceRe.find(body)
            val price = pm?.let { "€ " + it.groupValues[1] + "," + it.groupValues[2].padEnd(2, '0') }
            val img = Regex("srcset=\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.split(", ")?.mapNotNull { e ->
                val p = e.trim().split(" "); val w = p.lastOrNull()?.removeSuffix("w")?.toIntOrNull()
                if (w == null || p.size < 2) null else p[0] to w
            }?.minByOrNull { kotlin.math.abs(it.second - 400) }?.first?.let { if (it.startsWith("/")) ALMA_BASE + it else it }
            dishes += Dish(dishName, price, img)
        }
        if (dishes.isNotEmpty()) sections += MenuSec(if (soup) s("Soep", "Soup") else s("Warm buffet", "Hot buffet"), soup, dishes)
    }
    return MenuData(shown, dates, sections)
}

internal fun computeDiningStatus(secs: List<HSection>, now: LocalDateTime, loc: Locale): Status? {
    if (secs.isEmpty()) return null
    val byDate = secs.flatMap { it.rows }.groupBy({ it.date }, { it.ranges }).mapValues { e -> e.value.flatten().sortedBy { it.first } }
    val today = now.toLocalDate()
    val t = now.toLocalTime()
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
