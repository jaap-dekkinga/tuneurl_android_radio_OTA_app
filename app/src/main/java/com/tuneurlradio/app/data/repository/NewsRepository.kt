package com.tuneurlradio.app.data.repository

import com.tuneurlradio.app.data.remote.RssFeedParser
import com.tuneurlradio.app.domain.model.NewsArticle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val rssFeedParser: RssFeedParser
) {
    suspend fun fetchNews(): Result<Map<String, List<NewsArticle>>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(FEED_URL)
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to load feed: ${response.code}"))
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("No data from RSS feed"))

            val articles = rssFeedParser.parse(body)
            val articlesByCategory = groupByCategory(articles)

            Result.success(articlesByCategory)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun groupByCategory(articles: List<NewsArticle>): Map<String, List<NewsArticle>> {
        val map = mutableMapOf<String, MutableList<NewsArticle>>()

        for (article in articles) {
            for (category in article.categories) {
                map.getOrPut(category) { mutableListOf() }.add(article)
            }
        }

        return map.mapValues { (_, list) ->
            list.sortedByDescending { it.pubDate }
        }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    companion object {
        private const val FEED_URL = "https://www.ksal.com/feed/"
    }
}
