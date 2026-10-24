package boo.komori.wallpaper

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
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
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class WallpaperWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "WallpaperWorker"
        const val AUTHORITY = "boo.komori.wallpaper"
        const val FILE_PROVIDER_AUTHORITY = "boo.komori.wallpaper.fileprovider"
        const val PREF_SERVER_URL = "serverUrl"
        const val PREF_API_TOKEN = "apiToken"
        const val PREF_COLLECTION = "collection"
        const val PREF_ONLY_WIFI = "onlyWifi"
        const val PREF_REQUIRES_CHARGING = "requiresCharging"
        const val PREF_BATTERY_NOT_LOW = "batteryNotLow"

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

    /** Search response shape per monbooru's OpenAPI: {page, limit, total, results}. */
    private data class Page(val results: JSONArray, val total: Int)

    private fun fetchSearchPage(server: String, token: String, query: String, page: Int): Page {
        val body = MonbooruApi.search(server, token, query, "order", page)
        val results = body.optJSONArray("results")
            ?: throw IOException("Search response missing results array")
        return Page(results, body.optInt("total", 0))
    }

    override fun doWork(): Result {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val server = prefs.getString(PREF_SERVER_URL, null)
            ?.trim()?.trimEnd('/')
            ?.takeIf { it.startsWith("http") }
            ?: run {
                Log.w(TAG, "Server URL not configured")
                SyncStatus.mark(applicationContext, "error", "server URL not set")
                return Result.failure()
            }

        val token = TokenStore.getToken(applicationContext)
            ?: run {
                Log.w(TAG, "API token not configured")
                SyncStatus.mark(applicationContext, "error", "API token not set")
                return Result.failure()
            }

        val collection = prefs.getString(PREF_COLLECTION, null)
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "No collection selected")
                SyncStatus.mark(applicationContext, "error", "no collection chosen")
                return Result.failure()
            }
        SyncStatus.mark(applicationContext, "running", "")

        // collection:"name" is an exact match; sort=order returns the
        // collection in its own order (falls back to id order when unset).
        val query = "collection:\"$collection\""
        val cacheDir = applicationContext.cacheDir

        val artworks = ArrayList<Artwork>()
        var page = 1
        var total = 0
        try {
            while (page <= 50) { // hard cap: 50 * 200 = 10k images
                val p = fetchSearchPage(server, token, query, page)
                total = p.total
                if (p.results.length() == 0) break
                for (i in 0 until p.results.length()) {
                    val img = p.results.getJSONObject(i)
                    val id = img.optLong("id", -1)
                    if (id == -1L) continue

                    // Wallpapers only: skip videos (mp4/webm) and archives.
                    val ext = when (img.optString("file_type", "").lowercase()) {
                        "jpeg" -> "jpg"
                        "png", "webp", "gif", "avif", "jxl" -> img.optString("file_type").lowercase()
                        else -> null
                    } ?: continue

                    val cachedFile = File(cacheDir, "wallpaper_$id.$ext")
                    MonbooruApi.downloadImage(server, token, id, cachedFile)
                    val fileUri = FileProvider.getUriForFile(
                        applicationContext, FILE_PROVIDER_AUTHORITY, cachedFile
                    )

                    val source = img.optString("source", "")
                    val builder = Artwork.Builder()
                        .token(id.toString())
                        .title(if (source.isNotBlank()) source else "Wallpaper $id")
                        .byline(collection)
                        .persistentUri(fileUri)
                    img.optString("url", "").takeIf { it.isNotBlank() }?.let { builder.webUri(it.toUri()) }
                    artworks += builder.build()
                }
                page++
                if ((page - 1) * MonbooruApi.PAGE_LIMIT >= total) break
            }
        } catch (e: IOException) {
            Log.w(TAG, "Sync failed", e)
            if (artworks.isEmpty()) {
                SyncStatus.mark(applicationContext, "error", "search/download failed: ${e.message}")
                return Result.retry()
            }
            publish(artworks)
            SyncStatus.mark(applicationContext, "ok", "${artworks.size} of $total (partial: ${e.message})")
            return Result.success()
        }

        if (artworks.isEmpty()) {
            Log.w(TAG, "Collection '$collection' is empty or had no downloadable images")
            SyncStatus.mark(applicationContext, "error", "no usable images in '$collection' (are they all videos/archives?)")
            return Result.failure()
        }

        publish(artworks)
        SyncStatus.mark(applicationContext, "ok", "$total images loaded")
        Log.i(TAG, "Loaded ${artworks.size} of $total wallpapers from '$collection'")
        return Result.success()
    }

    private fun publish(artworks: List<Artwork>) {
        val cacheDir = applicationContext.cacheDir
        val keep = artworks.mapNotNull { it.persistentUri?.lastPathSegment?.substringAfterLast('/') }.toSet()
        cacheDir.listFiles()?.filter { it.name.startsWith("wallpaper_") && it.name !in keep }?.forEach { it.delete() }
        ProviderContract.getProviderClient(applicationContext, AUTHORITY).setArtwork(artworks)
    }
}
