package be.lesstress.providers

import java.time.LocalDate
import java.time.LocalTime

data class HRow(val date: LocalDate, val ranges: List<Pair<LocalTime, LocalTime>>)
data class HSection(val title: String, val rows: List<HRow>)

data class Dish(val name: String, val price: String?, val img: String?)
data class MenuSec(val title: String, val soup: Boolean, val dishes: List<Dish>)
data class MenuData(
    val date: LocalDate?,
    val dates: List<LocalDate>,
    val sections: List<MenuSec>,
    val unavailable: Boolean = false
)

/**
 * Supported menu provider return modes:
 * - [OrderButton]: opening hours + an order action button with provider-defined label
 * - [FullMenu]: opening hours + full daily menu pager
 * - [OnlineMenuLink]: opening hours + a link button to an external online menu
 */
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

object MenuProviderRegistry : ProviderRegistry<MenuProvider>("menu_provider") {
    override val all: List<MenuProvider> = listOf(
        AlmaRestaurantProvider,
        AlmaBroodjesbarProvider
    )
}
