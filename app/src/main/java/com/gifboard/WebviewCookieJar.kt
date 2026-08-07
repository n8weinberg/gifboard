package com.gifboard

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * A CookieJar that uses Android's WebView CookieManager to store and retrieve cookies.
 * This ensures that session cookies obtained in a WebView (like during a Cloudflare challenge)
 * are shared with OkHttp clients.
 */
class WebviewCookieJar : CookieJar {
    private val cookieManager = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()
        for (cookie in cookies) {
            cookieManager.setCookie(urlString, cookie.toString())
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookiesString = cookieManager.getCookie(url.toString())
        if (cookiesString != null && cookiesString.isNotEmpty()) {
            // Split by ';' which is the delimiter used by CookieManager.getCookie()
            return cookiesString.split(";").mapNotNull {
                Cookie.parse(url, it.trim())
            }
        }
        return emptyList()
    }
}
