package be.lesstress.providers.restaurants

import be.lesstress.HRow
import be.lesstress.HSection
import be.lesstress.MenuContent
import be.lesstress.MenuProvider
import be.lesstress.s
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.regex.Pattern

object VivesCube : MenuProvider {
    const val CUBE_URL = "https://www.vives.be/nl/over-vives/campussen/vives-kortrijk/studentenrestaurant-eat-cube"

    override val id = "vives_cube"
    override fun displayName() = s("Eat @ Cube (VIVES)", "Eat @ Cube (VIVES)")

    override suspend fun fetchHours(today: LocalDate): List<HSection> {
        val html = withContext(Dispatchers.IO) {
            val conn = (URL(CUBE_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LesStress")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        }
        return withContext(Dispatchers.Default) { parseCubeHours(html, today) }
    }

    override fun content(): MenuContent = MenuContent.OnlineMenuLink(
        menuUrl = CUBE_URL,
        buttonLabel = { s("Bekijk weekmenu", "View weekly menu") }
    )

    private val dateRegex = Pattern.compile("(?i)([A-Za-z]+dag)?\\s*(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?")
    private val openFrom = LocalTime.of(11, 30)
    private val openTo = LocalTime.of(14, 0)

    internal fun parseCubeHours(html: String, today: LocalDate): List<HSection> {
        val matcher = dateRegex.matcher(html)
        data class MatchedDate(val date: LocalDate, val endPos: Int)
        val matches = mutableListOf<MatchedDate>()

        while (matcher.find()) {
            val day = matcher.group(2)?.toIntOrNull() ?: continue
            val month = matcher.group(3)?.toIntOrNull() ?: continue
            val yearGroup = matcher.group(4)
            var year = yearGroup?.toIntOrNull() ?: today.year
            if (year < 100) year += 2000
            runCatching {
                var d = LocalDate.of(year, month, day)
                if (d.isBefore(today.minusMonths(6))) d = d.plusYears(1)
                matches.add(MatchedDate(d, matcher.end()))
            }
        }

        data class DayStatus(val isOpen: Boolean, val isClosed: Boolean)
        val parsed = mutableMapOf<LocalDate, DayStatus>()

        for (i in matches.indices) {
            val current = matches[i]
            val nextStart = if (i + 1 < matches.size) matches[i + 1].endPos else (current.endPos + 3500).coerceAtMost(html.length)
            val sub = html.substring(current.endPos, nextStart).lowercase()
            val closed = sub.contains("gesloten")
            val hasItems = sub.contains("<li>") || sub.contains("soup") || sub.contains("daily") ||
                    sub.contains("vegetarian") || sub.contains("pasta") || sub.contains("extra") || sub.contains("pop up")
            parsed[current.date] = DayStatus(isOpen = !closed && hasItems, isClosed = closed)
        }

        val monday = today.minusDays(today.dayOfWeek.value - 1L)
        val currentWeekDays = (0L..4L).map { monday.plusDays(it) }
        val allDates = (currentWeekDays + parsed.keys).distinct().sorted()

        val rows = allDates.map { date ->
            val dow = date.dayOfWeek
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                HRow(date, emptyList(), s("Weekend gesloten", "Closed on weekends"))
            } else {
                val st = parsed[date]
                when {
                    st != null && st.isOpen -> HRow(date, listOf(openFrom to openTo))
                    st != null && st.isClosed -> HRow(date, emptyList(), s("Gesloten", "Closed"))
                    st != null -> HRow(date, emptyList(), s("Gesloten / Onzeker", "Closed / Uncertain"))
                    else -> HRow(date, emptyList(), s("Geen menu / Onzeker", "No menu / Uncertain"))
                }
            }
        }

        return listOf(HSection("Eat @ Cube", rows))
    }
}
