package com.codeonthego.xkcdrandom.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The whole xkcd network surface, in one file so the example reads
 * top-to-bottom. Two endpoints:
 *   - GET https://xkcd.com/info.0.json           → latest comic
 *   - GET https://xkcd.com/<num>/info.0.json     → specific comic
 *
 * No auth, no rate-limit headers, no pagination. We keep this client
 * dependency-light: OkHttp + the org.json reader that ships with Android.
 */
class XkcdApiClient(
    private val client: OkHttpClient = defaultClient(),
) {
    /**
     * Fetch a random comic. Picks a number in [1, latestNum], retries
     * exactly once if the picked number happens to be 404 (xkcd's
     * famous "page not found" comic that returns HTTP 404 on the JSON
     * endpoint), then gives up and returns null. We don't go further
     * because elaborate retry/backoff would obscure the example.
     */
    fun fetchRandom(): XkcdComic? {
        val latest = fetchLatest() ?: return null
        // Pick a number != 404. We try once; if we land on 404 again
        // (effectively impossible) we just return the latest comic.
        repeat(2) {
            val pick = Random.nextInt(1, latest.num + 1)
            if (pick == 404) return@repeat
            fetchByNumber(pick)?.let { return it }
        }
        return latest
    }

    fun fetchLatest(): XkcdComic? = getJson("https://xkcd.com/info.0.json")?.let(::parseComic)

    fun fetchByNumber(num: Int): XkcdComic? =
        getJson("https://xkcd.com/$num/info.0.json")?.let(::parseComic)

    /**
     * Stream the comic's PNG. Caller must close the returned stream.
     * Returns null if the request failed or the response body was empty.
     * The size cap is enforced by the caller — see [XkcdPanelFragment].
     */
    fun openImageStream(imageUrl: String): InputStream? {
        val response = client.newCall(Request.Builder().url(imageUrl).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        // body() can be null on 204 etc. — for xkcd it shouldn't, but
        // handle the case explicitly.
        return response.body?.byteStream()
    }

    private fun getJson(url: String): JSONObject? = try {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) null
            else it.body?.string()?.let(::JSONObject)
        }
    } catch (_: IOException) {
        // Network / DNS / TLS failure → behave the same as "comic
        // unavailable". Caller falls back to the offline empty state.
        null
    }

    private fun parseComic(obj: JSONObject): XkcdComic? = try {
        XkcdComic(
            num = obj.getInt("num"),
            title = obj.optString("safe_title", obj.optString("title", "")),
            alt = obj.optString("alt", ""),
            imageUrl = obj.getString("img"),
        )
    } catch (_: Exception) {
        // Malformed payload → null, treated as a fetch failure.
        null
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
