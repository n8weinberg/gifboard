package com.gifboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class YarnClip(
    val id: String,
    val gifUrl: String,
    val thumbnailUrl: String,
    val title: String? = null,
    val subtitle: String? = null
)

class GetYarnScraper(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val uuidRegex = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    
    // aa[index] = [payload matches]
    private val primaryBlockRegex = Pattern.compile("aa\\[\\d+\\]\\s*=\\s*\\[([\\s\\S]*?)\\]\\s*;")
    
    // Direct asset reference checks
    private val fallbackUrlRegex = Pattern.compile("https://y\\.yarn\\.co/([0-9a-fA-F-]{36})")
    
    // Document layout standard elements
    private val hrefClipRegex = Pattern.compile("/yarn-clip/([0-9a-fA-F-]{36})")

    private var webView: WebView? = null
    private var parentView: ViewGroup? = null
    
    private var currentListener: OnSearchListener? = null
    private var isSearching = false
    private var attempts = 0
    private var maxAttempts = 20 

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .cookieJar(WebviewCookieJar())
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
                    .header("Referer", "https://yarn.co/")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Ch-Ua", "\"Chromium\";v=\"116\", \"Not)A;Brand\";v=\"24\", \"Google Chrome\";v=\"116\"")
                    .header("Sec-Ch-Ua-Mobile", "?1")
                    .header("Sec-Ch-Ua-Platform", "\"Android\"")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-User", "?1")
                    .header("Upgrade-Insecure-Requests", "1")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    interface OnSearchListener {
        fun onSearchSuccess(clips: List<YarnClip>, hasMore: Boolean)
        fun onSearchError(error: String)
    }

    fun attach(parent: ViewGroup?) {
        this.parentView = parent
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            parent?.addView(wv, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "InlinedApi")
    private fun ensureWebView(): WebView {
        if (webView == null) {
            webView = WebView(context).apply {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                alpha = 1.0f
                visibility = View.GONE
                
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d("GetYarnScraper", "Page finished: $url")
                        
                        view?.evaluateJavascript("""
                            (function() {
                                try {
                                    Object.defineProperty(navigator, 'webdriver', { get: () => undefined, configurable: true });
                                } catch (e) {}
                                if (!window.chrome) window.chrome = { runtime: {} };
                                try {
                                    Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'], configurable: true });
                                } catch (e) {}
                                try {
                                    Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5], configurable: true });
                                } catch (e) {}
                            })();
                        """.trimIndent(), null)

                        val delay = if (url?.contains("yarn.co") == true) 2000L else 4000L
                        handler.postDelayed({ requestHtml() }, delay)
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        Log.e("GetYarnScraper", "Renderer process gone")
                        destroy()
                        if (isSearching) {
                            handler.post { currentListener?.onSearchError("Connection lost") }
                            isSearching = false
                        }
                        return true
                    }
                }
            }
            
            parentView?.let { parent ->
                (webView?.parent as? ViewGroup)?.removeView(webView)
                parent.addView(webView)
            }
            
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
        return webView!!
    }

    fun cancelSearch() {
        isSearching = false
        currentListener = null
        handler.removeCallbacksAndMessages(null)
        webView?.visibility = View.GONE
    }

    fun searchGifs(query: String, page: Int = 0, listener: OnSearchListener) {
        handler.post {
            isSearching = false 
            handler.removeCallbacksAndMessages(null)
            
            isSearching = true
            currentListener = listener
            attempts = 0

            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = if (page > 0) {
                "https://yarn.co/yarn-find?text=$encodedQuery&p=$page"
            } else {
                "https://yarn.co/yarn-find?text=$encodedQuery"
            }

            // Try direct fetch first
            Thread {
                try {
                    // Sync cookies from WebView to ensure we have the latest session
                    CookieManager.getInstance().flush()
                    
                    val request = Request.Builder().url(url).build()
                    val response = httpClient.newCall(request).execute()
                    val html = response.body?.string() ?: ""
                    
                    val clips = parseClips(html)
                    val hasChallenge = html.contains("Turnstile", ignoreCase = true) || 
                                       html.contains("cloudflare", ignoreCase = true) || 
                                       html.contains("Ray ID", ignoreCase = true) ||
                                       html.contains("Checking your browser", ignoreCase = true)

                    if (clips.isNotEmpty() && !hasChallenge) {
                        Log.d("GetYarnScraper", "Direct fetch successful: Found ${clips.size} clips.")
                        handler.post {
                            if (isSearching) {
                                webView?.visibility = View.GONE
                                listener.onSearchSuccess(clips, false)
                                isSearching = false
                            }
                        }
                        return@Thread
                    }
                    Log.d("GetYarnScraper", "Direct fetch failed or challenge detected. Falling back to WebView.")
                } catch (e: Exception) {
                    Log.d("GetYarnScraper", "Direct fetch error: ${e.message}. Falling back to WebView.")
                }

                // Fallback to WebView
                handler.post {
                    if (!isSearching) return@post
                    
                    val wv = try {
                        ensureWebView()
                    } catch (e: Exception) {
                        listener.onSearchError("Initialization failed")
                        return@post
                    }

                    // Keep hidden initially; only show if a challenge is detected
                    wv.visibility = View.INVISIBLE
                    wv.alpha = 0.0f
                    wv.bringToFront()
                    
                    Log.d("GetYarnScraper", "Loading URL in WebView: $url")
                    wv.loadUrl(url)
                }
            }.start()
        }
    }

    private fun requestHtml() {
        if (!isSearching) return
        handler.post {
            webView?.evaluateJavascript(
                "(function() { return document.documentElement.outerHTML; })();"
            ) { html ->
                if (html != null && html != "null") {
                    val unquoted = unescapeJson(html)
                    processHtml(unquoted)
                } else if (isSearching) {
                    handler.postDelayed({ requestHtml() }, 1000)
                }
            }
        }
    }

    private fun unescapeJson(json: String): String {
        if (!json.startsWith("\"") || !json.endsWith("\"")) return json
        val sb = StringBuilder()
        var i = 1
        val len = json.length - 1
        while (i < len) {
            var c = json[i]
            if (c == '\\') {
                i++
                if (i >= len) break
                c = json[i]
                when (c) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        if (i + 4 < len) {
                            try {
                                val hex = json.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            } catch (e: Exception) {
                                sb.append('u')
                            }
                        }
                    }
                    else -> sb.append(c)
                }
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun processHtml(html: String) {
        if (!isSearching) return
        
        val clips = parseClips(html)
        val hasChallenge = html.contains("Turnstile", ignoreCase = true) || 
                           html.contains("cloudflare", ignoreCase = true) || 
                           html.contains("Ray ID", ignoreCase = true) ||
                           html.contains("Checking your browser", ignoreCase = true)

        if (hasChallenge) {
            webView?.visibility = View.VISIBLE
            webView?.alpha = 1.0f
        }

        if (clips.isNotEmpty()) {
            val hasMore = false // Only load 1 page
            Log.d("GetYarnScraper", "Found ${clips.size} clips. (hasMore forced to false)")
            
            // Ensure cookies are synchronized before notifying success
            CookieManager.getInstance().flush()
            
            webView?.visibility = View.GONE
            currentListener?.onSearchSuccess(clips, hasMore)
            isSearching = false
            return
        }

        val hasResultsIndicator = html.contains("class=\"card\"", ignoreCase = true) ||
                                  html.contains("yarn-clip", ignoreCase = true)

        if (hasResultsIndicator && !hasChallenge && attempts >= 3) {
            Log.w("GetYarnScraper", "Results visible but parsing failed. Hiding. Attempt: $attempts")
            webView?.visibility = View.GONE
            currentListener?.onSearchError("No clips extracted from visible results")
            isSearching = false
            return
        }

        attempts++
        if (attempts >= maxAttempts) {
            webView?.visibility = View.GONE
            if (hasChallenge) {
                currentListener?.onSearchError("Blocked by Cloudflare")
            } else {
                currentListener?.onSearchError("No results found")
            }
            isSearching = false
        } else {
            val delay = if (hasChallenge) 5000L else if (hasResultsIndicator) 2000L else 1500L
            Log.d("GetYarnScraper", "Waiting... attempt $attempts. HasChallenge: $hasChallenge. HasResultsIndicator: $hasResultsIndicator")
            
            if (webView?.visibility == View.VISIBLE) {
                webView?.bringToFront()
            }
            handler.postDelayed({ requestHtml() }, delay)
        }
    }

    fun destroy() {
        cancelSearch()
        handler.post {
            webView?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
            webView = null
            parentView = null
        }
    }

    private fun parseClips(html: String): List<YarnClip> {
        val primaryClips = mutableListOf<YarnClip>()
        
        // 1. Primary blocks (JS payload)
        val matcher = primaryBlockRegex.matcher(html)
        while (matcher.find()) {
            val blockContent = matcher.group(1) ?: continue
            val tokens = blockContent.split(",").map { 
                it.trim().removeSurrounding("\"").removeSurrounding("'") 
            }
            val uuid = tokens.firstOrNull { uuidRegex.matcher(it).matches() } ?: continue
            
            primaryClips.add(YarnClip(
                id = uuid,
                gifUrl = "https://y.yarn.co/${uuid}_text.gif",
                thumbnailUrl = "https://y.yarn.co/${uuid}_screenshot.jpg",
                title = tokens.getOrNull(1)?.takeIf { it.isNotBlank() && !uuidRegex.matcher(it).matches() },
                subtitle = tokens.getOrNull(2)?.takeIf { it.isNotBlank() && it.length > 3 }
            ))
        }

        // 2. Fallback UUIDs from asset URLs
        val fallbackUuids = linkedSetOf<String>()
        val urlMatcher = fallbackUrlRegex.matcher(html)
        while (urlMatcher.find()) {
            val id = urlMatcher.group(1) ?: continue
            if (uuidRegex.matcher(id).matches()) fallbackUuids.add(id)
        }
        
        // Global UUID scavenger
        val globalUuidMatcher = uuidRegex.matcher(html)
        while (globalUuidMatcher.find()) {
            val id = globalUuidMatcher.group()
            if (html.contains("/yarn-clip/$id") || html.contains("${id}_text.gif")) {
                fallbackUuids.add(id)
            }
        }

        // 3. DOM Metadata
        val domMetadataMap = mutableMapOf<String, Pair<String, String>>()
        try {
            val doc = Jsoup.parse(html)
            val links = doc.select("a[href*=/yarn-clip/]")
            for (link in links) {
                val href = link.attr("href")
                val hrefMatcher = hrefClipRegex.matcher(href)
                if (hrefMatcher.find()) {
                    val uuid = hrefMatcher.group(1) ?: continue
                    val card = link.closest(".card") ?: link.parent()
                    val title = card?.select(".title, .video-title, .subtitle")?.firstOrNull()?.text()?.trim() ?: ""
                    val transcript = card?.select(".transcript, .caption")?.firstOrNull()?.text()?.trim() ?: ""
                    if (title.isNotEmpty() || transcript.isNotEmpty()) {
                        domMetadataMap[uuid] = Pair(title, transcript)
                    }
                }
            }
        } catch (ignored: Exception) {}

        val finalOrderedIds = linkedSetOf<String>()
        finalOrderedIds.addAll(primaryClips.map { it.id })
        finalOrderedIds.addAll(fallbackUuids)

        val primaryMap = primaryClips.associateBy { it.id }
        
        return finalOrderedIds.map { uuid ->
            val primaryData = primaryMap[uuid]
            val domData = domMetadataMap[uuid]

            YarnClip(
                id = uuid,
                gifUrl = primaryData?.gifUrl ?: "https://y.yarn.co/${uuid}_text.gif",
                thumbnailUrl = primaryData?.thumbnailUrl ?: "https://y.yarn.co/${uuid}_screenshot.jpg",
                title = primaryData?.title ?: domData?.first ?: "GetYarn Clip",
                subtitle = primaryData?.subtitle ?: domData?.second ?: "Movies/TV"
            )
        }
    }
}
