package boo.komori.wallpaper

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-prefs mirror of the last sync attempt, so the settings screen can
 * show the outcome without touching the worker's private logs.
 */
object SyncStatus {

    private const val PREFS = "sync_status"
    const val KEY_STATE = "state" // idle|running|ok|error
    const val KEY_DETAIL = "detail"
    const val KEY_TIME = "time"

    fun mark(context: Context, state: String, detail: String) {
        prefs(context).edit()
            .putString(KEY_STATE, state)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    fun read(context: Context): String {
        val p = prefs(context)
        val state = p.getString(KEY_STATE, "idle") ?: "idle"
        val detail = p.getString(KEY_DETAIL, "") ?: ""
        val time = p.getLong(KEY_TIME, 0L)
        val timeStr = if (time > 0) {
            SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(time))
        } else "never"
        return when (state) {
            "running" -> "Syncing\u2026"
            "ok" -> "Last sync $timeStr: OK \u2014 $detail"
            "error" -> "Last sync $timeStr FAILED \u2014 $detail"
            else -> "Not synced yet"
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
