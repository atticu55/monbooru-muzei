package boo.komori.wallpaper

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
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
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class WallpaperWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "WallpaperWorker"
        const val AUTHORITY = "boo.komori.wallpaper"
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
        val body = MonbooruApi.search(server, token, query, sort = "order", page = page)
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
        // NOTE: MonbooruApi.search takes (query, sort, page) in that order —
        // an earlier version passed them swapped (page got "order").
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
                    if (img.optBoolean("is_missing", false)) continue
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

                    val source = img.optString("source", "")
                    val builder = Artwork.Builder()
                        .token(id.toString())
                        .title(if (source.isNotBlank()) source else "Wallpaper $id")
                        .byline(collection)
                        // Plain file path, same as the pixiv/wallhaven providers.
                        // FileProvider content:// URIs break Muzei's own load path:
                        // MuzeiArtProvider stores DATA from the URI path, and a
                        // FileProvider path (/cache/...) is neither openable nor
                        // writable as a file, so Muzei's preload fails and shows nothing.
                        .persistentUri(Uri.fromFile(cachedFile))
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
            val problem = verifyStoredArtwork(artworks.size)
            if (problem != null) SyncStatus.mark(applicationContext, "error", problem)
            else SyncStatus.mark(applicationContext, "ok", "${artworks.size} of $total (partial: ${e.message})")
            return Result.success()
        }

        if (artworks.isEmpty()) {
            Log.w(TAG, "Collection '$collection' is empty or had no downloadable images")
            SyncStatus.mark(applicationContext, "error", "no usable images in '$collection' (are they all videos/archives?)")
            return Result.failure()
        }

        publish(artworks)
        val problem = verifyStoredArtwork(artworks.size)
        if (problem != null) {
            SyncStatus.mark(applicationContext, "error", problem)
            return Result.success()
        }
        SyncStatus.mark(applicationContext, "ok", "${artworks.size} of $total loaded, verified")
        Log.i(TAG, "Loaded ${artworks.size} of $total wallpapers from '$collection'")
        return Result.success()
    }

    /**
     * Muzei's own ArtworkLoadWorker openInputStream()s every artwork and rejects
     * undecodable images before it will show anything. Mirror that exact check
     * here so the status line reports the real failure instead of a fake "ok".
     * Returns null when every stored row opens and decodes, else a reason.
     */
    private fun verifyStoredArtwork(count: Int): String? {
        val contentUri = ProviderContract.getContentUri(AUTHORITY)
        val resolver = applicationContext.contentResolver
        var verified = 0
        resolver.query(contentUri, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                val uri = ContentUris.withAppendedId(contentUri, id)
                try {
                    resolver.openInputStream(uri)?.use { stream ->
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(stream, null, opts)
                        if (opts.outWidth != 0 && opts.outHeight != 0) {
                            verified++
                        } else {
                            return "self-check: image $id not decodable ($verified/$count readable)"
                        }
                    } ?: return "self-check: image $id could not be opened ($verified/$count readable)"
                } catch (e: Exception) {
                    return "self-check: image $id failed (${e.javaClass.simpleName}: ${e.message})"
                }
            }
        }
        Log.i(TAG, "Self-check passed: $verified/$count images readable")
        return null
    }

    private fun publish(artworks: List<Artwork>) {
        val cacheDir = applicationContext.cacheDir
        val keep = artworks.mapNotNull { it.persistentUri?.lastPathSegment?.substringAfterLast('/') }.toSet()
        cacheDir.listFiles()?.filter { it.name.startsWith("wallpaper_") && it.name !in keep }?.forEach { it.delete() }
        val client = ProviderContract.getProviderClient(applicationContext, AUTHORITY)
        val uris = client.setArtwork(artworks)
        if (uris.isEmpty()) {
            // setArtwork swallowed the failure — probe the provider DB for the truth
            val stored = client.contentUri?.let { uri ->
                applicationContext.contentResolver.query(uri, null, null, null, null)?.use { it.count } ?: 0
            } ?: 0
            if (stored > 0) {
                Log.w(TAG, "setArtwork returned empty but $stored rows present")
            } else {
                throw IOException("provider rejected all artwork (setArtwork failed, 0 rows stored)")
            }
        }
    }
}
