package be.lesstress.providers

import be.lesstress.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

object AlmaBroodjesbarProvider : MenuProvider {
    private const val BROODJES_URL = "https://www.alma.be/nl/restaurants/kulak-campus-hal-a-broodje-app"
    private const val ORDER_URL = "https://orderbilly.com/alma-kulak"

    override val id = "alma_kulak_broodjesbar"
    override fun displayName() = tr("broodjes")

    override suspend fun fetchHours(today: LocalDate): List<HSection> {
        val html = almaGet(BROODJES_URL)
        return withContext(Dispatchers.Default) { parseAlmaHours(html, today) }
    }

    override fun content(): MenuContent = MenuContent.OrderButton(
        orderUrl = ORDER_URL,
        buttonLabel = { tr("order") }
    )
}
