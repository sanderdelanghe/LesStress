package be.lesstress

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CalendarSyncManager {

    private const val CAL_NAME        = "LesStress"
    private const val PREFS_ENABLED   = "cal_sync_enabled"
    private const val PREFS_ACCOUNT   = "cal_sync_account"
    private const val PREFS_CAL_ID    = "cal_sync_id"
    private const val PREFS_UID_PRE   = "cal_event_"

    // ── Prefs helpers ────────────────────────────────────────────────────────

    fun isSyncEnabled(ctx: Context): Boolean =
        ctx.prefs.getBoolean(PREFS_ENABLED, false)

    fun setSyncEnabled(ctx: Context, v: Boolean) =
        ctx.prefs.edit().putBoolean(PREFS_ENABLED, v).apply()

    fun getSyncAccount(ctx: Context): String? =
        ctx.prefs.getString(PREFS_ACCOUNT, null)

    fun setSyncAccount(ctx: Context, account: String) =
        ctx.prefs.edit().putString(PREFS_ACCOUNT, account).apply()

    // ── Account discovery ────────────────────────────────────────────────────

    /**
     * Returns all Google account names that already have a calendar on the
     * device (queried through CalendarContract — no GET_ACCOUNTS needed).
     */
    fun availableAccounts(ctx: Context): List<String> {
        val seen = mutableSetOf<String>()
        val proj = arrayOf(
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )
        runCatching {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj,
                "${CalendarContract.Calendars.ACCOUNT_TYPE}=?",
                arrayOf("com.google"), null
            )?.use { c ->
                while (c.moveToNext()) seen += c.getString(0)
            }
        }
        return seen.sorted()
    }

    // ── Calendar management ──────────────────────────────────────────────────

    private fun findOrCreateCalendar(ctx: Context, accountName: String): Long {
        // 1. Try cached ID first
        val cached = ctx.prefs.getLong(PREFS_CAL_ID, -1L)
        if (cached != -1L) {
            val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, cached)
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(CalendarContract.Calendars._ID), null, null, null)
                    ?.use { if (it.moveToFirst()) return cached }
            }
        }

        // 2. Search for existing calendar with our name under this account
        val proj  = arrayOf(CalendarContract.Calendars._ID)
        val sel   = "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND " +
                    "${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND " +
                    "${CalendarContract.Calendars.NAME}=?"
        runCatching {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj, sel,
                arrayOf(accountName, "com.google", CAL_NAME), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    ctx.prefs.edit().putLong(PREFS_CAL_ID, id).apply()
                    return id
                }
            }
        }

        // 3. Create a new local calendar
        val cv = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME,          accountName)
            put(CalendarContract.Calendars.ACCOUNT_TYPE,          "com.google")
            put(CalendarContract.Calendars.NAME,                   CAL_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,  CAL_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR,         0xFF1183D5.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,  CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT,          accountName)
            put(CalendarContract.Calendars.SYNC_EVENTS,            1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE,     "Europe/Brussels")
            put(CalendarContract.Calendars.VISIBLE,                1)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER,       "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME,       accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,       "com.google")
            .build()
        return runCatching {
            val result = ctx.contentResolver.insert(uri, cv) ?: return -1L
            val id = ContentUris.parseId(result)
            ctx.prefs.edit().putLong(PREFS_CAL_ID, id).apply()
            id
        }.getOrDefault(-1L)
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    suspend fun syncEvents(ctx: Context, events: List<Event>) = withContext(Dispatchers.IO) {
        val account = getSyncAccount(ctx) ?: return@withContext
        val calId   = findOrCreateCalendar(ctx, account)
        if (calId == -1L) return@withContext

        // Only sync KU Leuven events (not the user's own Extra Calendar ICS feed)
        val toSync = events.filter { it.source != "extra" && it.source != "ics2" }

        for (e in toSync) {
            val storedId = ctx.prefs.getLong("$PREFS_UID_PRE${e.uid}", -1L)
            if (e.deleted) {
                if (storedId != -1L) {
                    runCatching {
                        ctx.contentResolver.delete(
                            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, storedId),
                            null, null
                        )
                    }
                    ctx.prefs.edit().remove("$PREFS_UID_PRE${e.uid}").apply()
                }
                continue
            }

            val cv = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID,    calId)
                put(CalendarContract.Events.TITLE,          e.summary.substringBefore(",").trim())
                put(CalendarContract.Events.DTSTART,        e.start)
                put(CalendarContract.Events.DTEND,          e.end)
                put(CalendarContract.Events.EVENT_TIMEZONE, "Europe/Brussels")
                put(CalendarContract.Events.DESCRIPTION,    e.desc.trim())
                // Location intentionally omitted per user request
            }

            if (storedId != -1L) {
                runCatching {
                    ctx.contentResolver.update(
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, storedId),
                        cv, null, null
                    )
                }
            } else {
                runCatching {
                    val newUri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv)
                    if (newUri != null) {
                        ctx.prefs.edit()
                            .putLong("$PREFS_UID_PRE${e.uid}", ContentUris.parseId(newUri))
                            .apply()
                    }
                }
            }
        }
    }

    // ── Disconnect ───────────────────────────────────────────────────────────

    /**
     * Removes all synced events from the dedicated calendar and clears all
     * stored IDs. The calendar itself is left intact (user can delete it in
     * Google Calendar if desired).
     */
    fun clearAllSyncedEvents(ctx: Context) {
        val calId = ctx.prefs.getLong(PREFS_CAL_ID, -1L)
        if (calId != -1L) {
            runCatching {
                ctx.contentResolver.delete(
                    CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events.CALENDAR_ID}=?",
                    arrayOf(calId.toString())
                )
            }
        }
        ctx.prefs.edit().apply {
            remove(PREFS_CAL_ID)
            ctx.prefs.all.keys
                .filter { it.startsWith(PREFS_UID_PRE) }
                .forEach { remove(it) }
        }.apply()
    }
}
