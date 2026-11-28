package be.lesstress.providers

import android.content.Context
import be.lesstress.prefs

/** Common contract for toggleable, reorderable data providers (dining, library, etc.). */
interface AppProvider {
    val id: String
    val defaultEnabled: Boolean get() = true
    fun displayName(): String
}

/**
 * Shared registry managing provider discovery, enable/disable state, and display order
 * persisted in SharedPreferences.
 */
abstract class ProviderRegistry<T : AppProvider>(private val keyPrefix: String) {
    abstract val all: List<T>

    private val orderKey get() = "${keyPrefix}_order"
    private fun enabledKey(id: String) = "${keyPrefix}_enabled_$id"

    fun isEnabled(c: Context, id: String): Boolean {
        val default = all.firstOrNull { it.id == id }?.defaultEnabled ?: true
        return c.prefs.getBoolean(enabledKey(id), default)
    }

    fun setEnabled(c: Context, id: String, enabled: Boolean) {
        c.prefs.edit().putBoolean(enabledKey(id), enabled).apply()
    }

    fun orderedAll(c: Context): List<T> {
        val savedIds = c.prefs.getString(orderKey, "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val byId = all.associateBy { it.id }
        val ordered = savedIds.mapNotNull { byId[it] }.toMutableList()
        for (p in all) {
            if (p !in ordered) ordered += p
        }
        return ordered
    }

    fun activeOrdered(c: Context): List<T> = orderedAll(c).filter { isEnabled(c, it.id) }

    fun move(c: Context, id: String, delta: Int) {
        val list = orderedAll(c).map { it.id }.toMutableList()
        val idx = list.indexOf(id)
        val target = idx + delta
        if (idx < 0 || target !in list.indices) return
        list[idx] = list[target].also { list[target] = list[idx] }
        c.prefs.edit().putString(orderKey, list.joinToString(",")).apply()
    }
}
