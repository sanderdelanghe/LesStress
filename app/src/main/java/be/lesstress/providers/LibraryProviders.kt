package be.lesstress.providers

import java.time.LocalDate
import java.time.LocalTime

data class LibRow(val date: LocalDate, val open: LocalTime?, val close: LocalTime?)
data class LibraryOccupancy(val occupied: Int, val capacity: Int)
data class LibraryData(val hours: List<LibRow>, val occupancy: LibraryOccupancy? = null)
data class LibraryBookAction(val url: String, val label: () -> String)
data class LibraryCheckInAction(
    val seatOffset: Int,
    val urlPrefix: String = "https://kuleuven.be/kurtqr?id="
) {
    fun urlForSeat(seat: Int): String = "$urlPrefix${seat + seatOffset}"
}

/**
 * Contract for library providers.
 * Must return opening hours via [fetchData], and may optionally provide live occupancy,
 * a booking link with provider-defined label, and a seat check-in action with offset.
 */
interface LibraryProvider : AppProvider {
    suspend fun fetchData(): LibraryData
    val bookAction: LibraryBookAction? get() = null
    val checkInAction: LibraryCheckInAction? get() = null
}

object LibraryProviderRegistry : ProviderRegistry<LibraryProvider>("lib_provider") {
    override val all: List<LibraryProvider> = listOf(KulakKbibProvider)
}
