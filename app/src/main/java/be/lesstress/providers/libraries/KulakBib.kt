package be.lesstress.providers.libraries

import be.lesstress.LibRow
import be.lesstress.LibraryBookAction
import be.lesstress.LibraryCheckInAction
import be.lesstress.LibraryData
import be.lesstress.LibraryOccupancy
import be.lesstress.LibraryProvider
import be.lesstress.s
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object KulakBib : LibraryProvider {
    private const val LIB_API = "https://bib.kuleuven.be/apps/ub/blokkeninleuven/api/libraries/kbib"
    private const val KURT_URL = "https://kurt3.ghum.kuleuven.be/locations/15"

    override val id = "kulak_kbib"
    override fun displayName() = s("Bibliotheek KBIB", "Library KBIB")

    override val bookAction = LibraryBookAction(
        url = KURT_URL,
        label = { s("Reserveren via KURT", "Reserve via KURT") }
    )

    override val checkInAction = LibraryCheckInAction(seatOffset = 401629)

    override suspend fun fetchData(): LibraryData {
        val jsonStr = withContext(Dispatchers.IO) {
            (URL(LIB_API).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LesStress")
            }.inputStream.bufferedReader().use { it.readText() }
        }
        val j = JSONObject(jsonStr)
        val occ = if (j.has("occupancy") && j.has("zitjes")) {
            LibraryOccupancy(j.optInt("occupancy"), j.optInt("zitjes"))
        } else null
        return LibraryData(hours = parseLibHours(j), occupancy = occ)
    }

    private fun parseTime(str: String): LocalTime? {
        if (str.isBlank()) return null
        val p = str.replace(':', '.').split('.')
        return if (p.size == 2) LocalTime.of(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null) else null
    }

    private fun parseLibHours(j: JSONObject): List<LibRow> {
        val hrs = j.optJSONObject("hours") ?: return emptyList()
        val dtf = DateTimeFormatter.BASIC_ISO_DATE
        return hrs.keys().asSequence().mapNotNull { k ->
            val obj = hrs.optJSONObject(k) ?: return@mapNotNull null
            val dStr = obj.optString("date")
            if (dStr.length == 8) LibRow(LocalDate.parse(dStr, dtf), parseTime(obj.optString("begin")), parseTime(obj.optString("einde"))) else null
        }.sortedBy { it.date }.toList()
    }
}
