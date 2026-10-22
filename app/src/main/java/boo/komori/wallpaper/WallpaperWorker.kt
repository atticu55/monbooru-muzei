package boo.komori.wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.ProviderContract
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

class WallpaperWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "WallpaperWorker"
        const val AUTHORITY = "boo.komori.wallpaper"
        const val PREF_BASE_URL = "baseUrl"
        const val PREF_FOLDER = "folder"
        const val PREF_ONLY_WIFI = "onlyWifi"
        const val PREF_REQUIRES_CHARGING = "requiresCharging"
        const val PREF_BATTERY_NOT_LOW = "batteryNotLow"
        const val PREF_KNOWN_FOLDERS = "knownFolders"

        private val http = OkHttpClient()

        internal fun enqueueLoad(context: Context, ignoreRequirements: Boolean = false) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (!ignoreRequirements && prefs.getBoolean(PREF_ONLY_WIFI, false))
                        NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .setRequiresStorageNotLow(true)
                .apply {
                    if (!ignoreRequirements) {
                        if (prefs.getBoolean(PREF_REQUIRES_CHARGING, false)) setRequiresCharging(true)
                        if (prefs.getBoolean(PREF_BATTERY_NOT_LOW, false)) setRequiresBatteryNotLow(true)
                    }
                }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                TAG, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WallpaperWorker>()
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()
            )
        }
    }

    override fun doWork(): Result {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val base = prefs.getString(PREF_BASE_URL, null)
            ?.trim()?.trimEnd('/')
            ?.takeIf { it.startsWith("http") }
            ?: run {
                Log.w(TAG, "Base URL not configured")
                return Result.failure()
            }

        // keep the folder picker's cache fresh (non-fatal)
        try {
            val names = mutableSetOf<String>()
            val manifest = JSONArray(fetch("$base/manifest.json"))
            for (i in 0 until manifest.length()) {
                manifest.getJSONObject(i).optString("name").takeIf { it.isNotBlank() }?.let { names.add(it) }
            }
            if (names.isNotEmpty()) prefs.edit().putStringSet(PREF_KNOWN_FOLDERS, names).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Manifest refresh failed (non-fatal)", e)
        }

        val folder = prefs.getString(PREF_FOLDER, null)?.trim()?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "No folder selected")
                return Result.failure()
            }

        val index = try {
            JSONArray(fetch("$base/${Uri.encode(folder)}/index.json"))
        } catch (e: IOException) {
            Log.w(TAG, "Folder index fetch failed", e)
            return Result.retry()
        }

        val artworks = ArrayList<Artwork>(index.length())
        for (i in 0 until index.length()) {
            val o = index.getJSONObject(i)
            val downloadUrl = o.optString("downloadUrl", o.optString("url"))
            if (downloadUrl.isBlank()) continue
            artworks += Artwork.Builder()
                .token(o.optString("id", o.optString("name", downloadUrl)))
                .title(o.optString("name", "Untitled"))
                .byline(o.optString("artist", folder))
                .persistentUri(downloadUrl.toUri())
                .webUri(o.optString("url", downloadUrl).toUri())
                .build()
        }
        if (artworks.isEmpty()) {
            Log.w(TAG, "Empty index for folder $folder")
            return Result.failure()
        }

        ProviderContract.getProviderClient(applicationContext, AUTHORITY).setArtwork(artworks)
        return Result.success()
    }

    private fun fetch(url: String): String = http.newCall(
        Request.Builder().url(url).build()
    ).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
        resp.body?.string() ?: throw IOException("Empty body from $url")
    }
}
