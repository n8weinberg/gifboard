package com.gifboard

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Fetches GIFs from Google Image Search.
 */
class GoogleGifFetcher {

    companion object {
        private const val BASE_URL = "https://www.google.com/search"
        // Updated to a more recent Chrome version (122)
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    }

    data class GifSearchRequest(
        val query: String, 
        val pageIndex: Int = 0,
        val safeSearch: String = "active"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .cookieJar(WebviewCookieJar())
        .build()

    fun fetchGifs(request: GifSearchRequest): String {
        require(request.query.isNotBlank()) { "Query cannot be empty" }

        val params = mapOf(
            "q" to "${request.query} gif",
            "tbm" to "isch",
            "tbs" to "itp:animated",
            "safe" to request.safeSearch,
            "async" to "ijn:${request.pageIndex},_fmt:json"
        )

        return executeRequest(params)
    }

    fun fetchMemes(request: GifSearchRequest): String {
        require(request.query.isNotBlank()) { "Query cannot be empty" }

        val params = mapOf(
            "q" to "${request.query} meme",
            "tbm" to "isch",
            "tbs" to "itp:static",
            "safe" to request.safeSearch,
            "async" to "ijn:${request.pageIndex},_fmt:json"
        )

        return executeRequest(params)
    }

    private fun executeRequest(params: Map<String, String>): String {
        val queryString = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }

        val url = "$BASE_URL?$queryString"
        android.util.Log.d("GoogleGifFetcher", "Fetching: $url")

        val httpRequest = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://www.google.com/")
            // Updated Sec-CH-UA to match Chrome 122
            .header("sec-ch-ua", "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
            // Removed X-Requested-With and sec-fetch-site: same-origin as they can be red flags
            // when not accompanied by valid session cookies.
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-origin")
            .get()
            .build()

        return try {
            val response = client.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(200) ?: "no body"
                android.util.Log.e("GoogleGifFetcher", "HTTP Error: ${response.code} ${response.message} - $errorBody")
                return ""
            }
            var content = response.body?.string() ?: ""

            // Strip security prefix
            if (content.startsWith(")]}'")) {
                content = content.substring(4).trim()
            }

            content
        } catch (e: Exception) {
            android.util.Log.e("GoogleGifFetcher", "Fetch failed", e)
            ""
        }
    }
}
