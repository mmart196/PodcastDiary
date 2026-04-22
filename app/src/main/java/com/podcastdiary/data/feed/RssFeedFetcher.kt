package com.podcastdiary.data.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class RssFeedFetcher(
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PodcastDiary/0.1 (Android)")
            .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
            .build()
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (resp.code == 404) return@withContext ""
                        throw IOException("Feed HTTP ${resp.code}")
                    }
                    return@withContext resp.body?.string()
                        ?: throw IOException("Empty feed body")
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt == 0) Thread.sleep(1500)
            }
        }
        throw lastError ?: IOException("Feed fetch failed")
    }

    /**
     * Build the WordPress-style pagination URL for the given page.
     * /feed/ → /feed/?paged=2, /feed/?paged=3, ...
     */
    fun pageUrl(baseUrl: String, page: Int): String {
        if (page <= 1) return baseUrl
        val separator = if (baseUrl.contains('?')) "&" else "?"
        return baseUrl + separator + "paged=" + page
    }

    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
    }
}
