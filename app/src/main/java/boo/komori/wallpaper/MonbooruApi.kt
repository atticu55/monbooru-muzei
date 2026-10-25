package boo.komori.wallpaper

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thin client for the monbooru REST API (/api/v1), bearer-token auth. */
object MonbooruApi {

    // The server clamps limit to 200; walking pages is the only way to
    // enumerate a bigger collection.
    const val PAGE_LIMIT = 200

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun request(url: String, token: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .build()

    /** One page of search results. Sort "order" keeps a collection's own order. */
    fun search(server: String, token: String, query: String, sort: String, page: Int): JSONObject {
        val url = "${server.trimEnd('/')}/api/v1/images/search" +
            "?q=${Uri.encode(query)}&sort=$sort&page=$page&limit=$PAGE_LIMIT"
        return http.newCall(request(url, token)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for search")
            JSONObject(resp.body?.string() ?: throw IOException("Empty search body"))
        }
    }

    /**
     * Distinct collection names. The REST API has no "list collections"
     * endpoint, so walk collection:any and read each image's `collection`
     * field (a single string per image).
     */
    fun listCollections(server: String, token: String): List<String> {
        val names = LinkedHashSet<String>()
        var page = 1
        while (page <= 50) { // hard cap: 50 * 200 = 10k images
            val body = search(server, token, "collection:any", "newest", page)
            val results = body.optJSONArray("results") ?: break
            if (results.length() == 0) break
            for (i in 0 until results.length()) {
                val img = results.getJSONObject(i)
                img.optString("collection", "").takeIf { it.isNotBlank() }?.let { names += it }
            }
            val total = body.optInt("total", 0)
            page++
            if ((page - 1) * PAGE_LIMIT >= total) break
        }
        return names.sorted()
    }

    fun downloadImage(server: String, token: String, id: Long, dest: File) {
        val url = "${server.trimEnd('/')}/api/v1/images/$id/file"
        http.newCall(request(url, token)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for image $id")
            val body = resp.body ?: throw IOException("Empty body for image $id")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }
}
