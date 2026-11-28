package be.lesstress.providers

import be.lesstress.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

object AlmaRestaurantProvider : MenuProvider {
    private const val RESTAURANT_URL = "https://www.alma.be/nl/restaurants/kulak-restaurant"

    override val id = "alma_kulak_restaurant"
    override fun displayName() = tr("restaurant")

    override suspend fun fetchHours(today: LocalDate): List<HSection> {
        val html = almaGet(RESTAURANT_URL)
        return withContext(Dispatchers.Default) { parseAlmaHours(html, today) }
    }

    override fun content(): MenuContent = MenuContent.FullMenu(
        siteUrl = RESTAURANT_URL,
        fetchDates = {
            val html = almaGet(RESTAURANT_URL)
            withContext(Dispatchers.Default) { parseAlmaMenu(html).dates }
        },
        fetchMenu = { date ->
            val url = if (date == null) RESTAURANT_URL else "$RESTAURANT_URL?date=$date"
            val html = almaGet(url)
            withContext(Dispatchers.Default) { parseAlmaMenu(html) }
        }
    )
}
