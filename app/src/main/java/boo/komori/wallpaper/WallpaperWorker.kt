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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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

        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

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
        val server = prefs.getString(PREF_SERVER_URL, null)
            ?.trim()?.trimEnd('/')
            ?.takeIf { it.startsWith("http") }
            ?: run {
                Log.w(TAG, "Server URL not configured")
                return Result.failure()
            }

        val token = TokenStore.getToken(applicationContext)
            ?: run {
                Log.w(TAG, "API token not configured")
                return Result.failure()
            }

        val collection = prefs.getString(PREF_COLLECTION, null)
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "No collection selected")
                return Result.failure()
            }

        // Search for images in the collection
        val searchUrl = "$server/api/v1/images/search?q=collection:${Uri.encode(collection)}&sort=newest&limit=200"
        val response = try {
            fetchJson(searchUrl, token)
        } catch (e: IOException) {
            Log.w(TAG, "Search fetch failed", e)
            return Result.retry()
        }

        val images = response.optJSONArray("images") ?: run {
            Log.w(TAG, "No images array in response")
            return Result.failure()
        }

        if (images.length() == 0) {
            Log.w(TAG, "Collection '$collection' is empty")
            return Result.failure()
        }

        // Clean up old cached files
        val cacheDir = applicationContext.cacheDir
        cacheDir.listFiles()?.filter { it.name.startsWith("wallpaper_") }?.forEach { it.delete() }

        val artworks = ArrayList<Artwork>(images.length())
        for (i in 0 until images.length()) {
            val img = images.getJSONObject(i)
            val id = img.optInt("id", -1)
            if (id == -1) continue

            val fileType = img.optString("file_type", "jpg")
            val source = img.optString("source", "")
            val imgUrl = img.optString("url", "")

            // Download the actual image file
            val downloadUrl = "$server/api/v1/images/$id/file"
            val cachedFile = File(cacheDir, "wallpaper_${id}.${fileType}")
            try {
                downloadFile(downloadUrl, token, cachedFile)
            } catch (e: IOException) {
                Log.w(TAG, "Image download failed for id=$id", e)
                continue
            }

            // Create FileProvider URI for the cached file
            val fileUri = FileProvider.getUriForFile(applicationContext, FILE_PROVIDER_AUTHORITY, cachedFile)

            val title = if (source.isNotBlank()) source else "Wallpaper $id"
            val webUri = if (imgUrl.isNotBlank()) imgUrl.toUri() else null

            val builder = Artwork.Builder()
                .token(id.toString())
                .title(title)
                .byline(collection)
                .persistentUri(fileUri)

            if (webUri != null) builder.webUri(webUri)

            artworks += builder.build()
        }

        if (artworks.isEmpty()) {
            Log.w(TAG, "No images could be downloaded from collection '$collection'")
            return Result.retry()
        }

        ProviderContract.getProviderClient(applicationContext, AUTHORITY).setArtwork(artworks)
        Log.i(TAG, "Loaded ${artworks.size} wallpapers from collection '$collection'")
        return Result.success()
    }

    private fun fetchJson(url: String, token: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val body = resp.body?.string() ?: throw IOException("Empty body from $url")
            JSONObject(body)
        }
    }

    private fun downloadFile(url: String, token: String, dest: File) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IOException("Empty body from $url")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }
}
