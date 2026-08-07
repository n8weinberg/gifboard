package com.gifboard

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Fetches GIFs from Klipy API.
 */
class KlipyGifFetcher {

    companion object {
        private const val BASE_URL = "https://api.klipy.com/api/v1/%s/gifs/search"
    }

    data class KlipySearchRequest(
        val appKey: String,
        val query: String,
        val page: Int = 1,
        val perPage: Int = 24,
        val customerId: String? = null,
        val locale: String? = null,
        val contentFilter: String = "medium"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun fetchGifs(request: KlipySearchRequest): String {
        require(request.query.isNotBlank()) { "Query cannot be empty" }
        require(request.appKey.isNotBlank()) { "App key cannot be empty" }

        val url = String.format(BASE_URL, request.appKey)
        
        val params = mutableMapOf(
            "q" to request.query,
            "page" to request.page.toString(),
            "per_page" to request.perPage.toString(),
            "content_filter" to request.contentFilter
        )
        
        request.customerId?.let { params["customer_id"] = it }
        request.locale?.let { params["locale"] = it }

        return executeRequest(url, params)
    }

    private fun executeRequest(baseUrl: String, params: Map<String, String>): String {
        val queryString = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }

        val url = "$baseUrl?$queryString"
        android.util.Log.d("KlipyGifFetcher", "Fetching: $url")

        val httpRequest = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .get()
            .build()

        return try {
            val response = client.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(200) ?: "no body"
                android.util.Log.e("KlipyGifFetcher", "HTTP Error: ${response.code} ${response.message} - $errorBody")
                return ""
            }
            response.body?.string() ?: ""
        } catch (e: Exception) {
            android.util.Log.e("KlipyGifFetcher", "Fetch failed", e)
            ""
        }
    }
}
