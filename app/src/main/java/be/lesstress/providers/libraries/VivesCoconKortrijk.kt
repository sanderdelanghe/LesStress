package be.lesstress.providers.libraries

import be.lesstress.LibRow
import be.lesstress.LibraryBookAction
import be.lesstress.LibraryData
import be.lesstress.LibraryProvider
import be.lesstress.s
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.regex.Pattern

object VivesCoconKortrijk : LibraryProvider {
    const val COCON_URL = "https://www.vives.be/nl/studeren/cocon"

    override val id = "vives_cocon_kortrijk"
    override fun displayName() = s("CoCon Kortrijk (VIVES)", "CoCon Kortrijk (VIVES)")

    override val bookAction = LibraryBookAction(
        url = COCON_URL,
        label = { s("Info CoCon", "Info CoCon") }
    )

    override suspend fun fetchData(): LibraryData {
        val html = withContext(Dispatchers.IO) {
            val conn = (URL(COCON_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LesStress")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        }
        val today = LocalDate.now(java.time.ZoneId.of("Europe/Brussels"))
        return withContext(Dispatchers.Default) {
            LibraryData(hours = parseCoconHours(html, today))
        }
    }

    private val timeRegex = Pattern.compile("(\\d{1,2})[.:](\\d{2})\\s*(?:u\\.?|uur)?\\s*(?:tot|tot en met|–|-)\\s*(\\d{1,2})[.:](\\d{2})")
    private val dateRegex = Pattern.compile("(?i)(\\d{1,2})\\s+([a-zA-Z]+)\\s+(\\d{4})")

    private val months = mapOf(
        "januari" to 1, "februari" to 2, "maart" to 3, "april" to 4, "mei" to 5, "juni" to 6,
        "juli" to 7, "augustus" to 8, "september" to 9, "oktober" to 10, "november" to 11, "december" to 12,
        "jan" to 1, "feb" to 2, "mrt" to 3, "apr" to 4, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "okt" to 10, "nov" to 11, "dec" to 12
    )

    private val dayTokenMap = mapOf(
        "ma" to DayOfWeek.MONDAY,
        "di" to DayOfWeek.TUESDAY,
        "wo" to DayOfWeek.WEDNESDAY,
        "do" to DayOfWeek.THURSDAY,
        "vr" to DayOfWeek.FRIDAY,
        "za" to DayOfWeek.SATURDAY,
        "zo" to DayOfWeek.SUNDAY
    )

    data class DayHours(val open: LocalTime, val close: LocalTime)
    data class ExceptionInfo(val isClosed: Boolean, val note: String)

    internal fun parseStandardHours(html: String): Map<DayOfWeek, DayHours> {
        val schedule = mutableMapOf<DayOfWeek, DayHours>()
        val rowMatcher = Pattern.compile("(?is)<tr>\\s*<td><strong>CoCon Kortrijk</strong></td>\\s*<td>(.*?)</td>").matcher(html)
        if (rowMatcher.find()) {
            val cell = rowMatcher.group(1) ?: ""
            val lines = cell.split(Regex("(?i)<br\\s*/?>|\\n"))
            for (line in lines) {
                val clean = line.replace(Regex("<[^>]+>"), " ").trim().lowercase()
                val tm = timeRegex.matcher(clean)
                if (tm.find()) {
                    val op = LocalTime.of(tm.group(1)!!.toInt(), tm.group(2)!!.toInt())
                    val cl = LocalTime.of(tm.group(3)!!.toInt(), tm.group(4)!!.toInt())
                    val beforeTime = clean.substring(0, tm.start())
                    val tokens = Regex("[a-z]+").findAll(beforeTime).map { it.value }
                    for (tok in tokens) {
                        dayTokenMap[tok]?.let { dow -> schedule[dow] = DayHours(op, cl) }
                    }
                }
            }
        }

        // Fallbacks if not found on page
        if (schedule.isEmpty()) {
            val monToThu = DayHours(LocalTime.of(8, 0), LocalTime.of(17, 30))
            schedule[DayOfWeek.MONDAY] = monToThu
            schedule[DayOfWeek.TUESDAY] = monToThu
            schedule[DayOfWeek.WEDNESDAY] = monToThu
            schedule[DayOfWeek.THURSDAY] = monToThu
            schedule[DayOfWeek.FRIDAY] = DayHours(LocalTime.of(8, 0), LocalTime.of(17, 0))
        }
        return schedule
    }

    internal fun parseExceptions(html: String): Map<LocalDate, ExceptionInfo> {
        val exceptions = mutableMapOf<LocalDate, ExceptionInfo>()
        val excMatcher = Pattern.compile("(?is)Afwijkende openingstijden.*?(?=Ontdek alles|<h2|$)").matcher(html)
        if (excMatcher.find()) {
            val excBlock = excMatcher.group(0) ?: ""
            val kortrijkMatcher = Pattern.compile("(?is)<strong>\\s*Kortrijk:?\\s*</strong>\\s*<ul>(.*?)</ul>").matcher(excBlock)
            if (kortrijkMatcher.find()) {
                val listHtml = kortrijkMatcher.group(1) ?: ""
                val itemMatcher = Pattern.compile("(?is)<li>(.*?)</li>").matcher(listHtml)
                while (itemMatcher.find()) {
                    val raw = itemMatcher.group(1) ?: continue
                    val clean = raw.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()
                    val dm = dateRegex.matcher(clean)
                    if (dm.find()) {
                        val day = dm.group(1)?.toIntOrNull() ?: continue
                        val monthStr = dm.group(2)?.lowercase() ?: continue
                        val year = dm.group(3)?.toIntOrNull() ?: continue
                        val month = months[monthStr] ?: continue
                        runCatching {
                            val dt = LocalDate.of(year, month, day)
                            val isClosed = clean.contains("gesloten", ignoreCase = true)
                            exceptions[dt] = ExceptionInfo(isClosed, clean)
                        }
                    }
                }
            }
        }
        return exceptions
    }

    internal fun parseCoconHours(html: String, today: LocalDate): List<LibRow> {
        val standard = parseStandardHours(html)
        val exceptions = parseExceptions(html)

        return (0L..13L).map { offset ->
            val date = today.plusDays(offset)
            val dow = date.dayOfWeek
            val exc = exceptions[date]

            when {
                dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY -> {
                    LibRow(date, null, null)
                }
                exc != null -> {
                    if (exc.isClosed) {
                        LibRow(date, null, null, note = exc.note)
                    } else {
                        val std = standard[dow]
                        LibRow(date, std?.open, std?.close, note = exc.note)
                    }
                }
                else -> {
                    val std = standard[dow]
                    LibRow(date, std?.open, std?.close, null)
                }
            }
        }
    }
}
