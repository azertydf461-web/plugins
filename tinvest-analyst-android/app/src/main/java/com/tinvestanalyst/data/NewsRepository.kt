package com.tinvestanalyst.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Загрузка новостной ленты. Каждый источник качается отдельно и независимо:
 * упавшая лента не должна лишать анализ остальных новостей, поэтому ошибки
 * по одному источнику проглатываются, а не рушат всю выборку.
 */
class NewsRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var cache: List<NewsItem> = emptyList()
    private var cachedAtMillis: Long = 0
    private var lastErrors: List<String> = emptyList()

    /** Сколько источников ответило при последней загрузке. */
    var loadedSources: Int = 0
        private set

    val failures: List<String> get() = lastErrors

    suspend fun load(forceRefresh: Boolean = false): List<NewsItem> {
        val fresh = System.currentTimeMillis() - cachedAtMillis < CACHE_TTL_MILLIS
        if (!forceRefresh && fresh && cache.isNotEmpty()) return cache

        val errors = mutableListOf<String>()
        val results = coroutineScope {
            NEWS_SOURCES.map { source ->
                async(Dispatchers.IO) {
                    runCatching { fetch(source) }
                        .onFailure { errors += "${source.title}: ${it.message ?: "нет связи"}" }
                        .getOrDefault(emptyList())
                }
            }.map { it.await() }
        }

        val merged = results.flatten()
            .distinctBy { it.title.trim().lowercase() }
            .sortedByDescending { it.publishedAtMillis }
            .take(MAX_ITEMS)

        loadedSources = results.count { it.isNotEmpty() }
        lastErrors = errors
        if (merged.isNotEmpty()) {
            cache = merged
            cachedAtMillis = System.currentTimeMillis()
        }
        return merged
    }

    private suspend fun fetch(source: NewsSource): List<NewsItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(source.url)
            // Часть изданий отдаёт 403 клиенту без привычного им заголовка.
            .header("User-Agent", "Mozilla/5.0 (Android) TInvestAnalyst/1.0")
            .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("код ${response.code}")
            val body = response.body?.string().orEmpty()
            parse(body, source.title)
        }
    }

    private fun parse(xml: String, sourceTitle: String): List<NewsItem> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val items = mutableListOf<NewsItem>()
        var title = StringBuilder()
        var summary = StringBuilder()
        var link = StringBuilder()
        var date = StringBuilder()
        var current: StringBuilder? = null
        var insideItem = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase()
                    if (tag == "item" || tag == "entry") {
                        insideItem = true
                        title = StringBuilder()
                        summary = StringBuilder()
                        link = StringBuilder()
                        date = StringBuilder()
                    } else if (insideItem) {
                        current = when (tag) {
                            "title" -> title
                            "description", "summary", "content" -> summary
                            "link" -> link
                            "pubdate", "published", "updated", "date" -> date
                            else -> null
                        }
                        // В Atom ссылка лежит в атрибуте, а не в тексте тега.
                        if (tag == "link" && link.isEmpty()) {
                            parser.getAttributeValue(null, "href")?.let { link.append(it) }
                        }
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> current?.append(parser.text)

                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase()
                    if (tag == "item" || tag == "entry") {
                        insideItem = false
                        current = null
                        val cleanTitle = clean(title.toString())
                        if (cleanTitle.isNotBlank()) {
                            items += NewsItem(
                                title = cleanTitle,
                                summary = clean(summary.toString()).take(400),
                                link = link.toString().trim(),
                                source = sourceTitle,
                                publishedAtMillis = parseDate(date.toString().trim()),
                            )
                        }
                    } else {
                        current = null
                    }
                }
            }
            event = parser.next()
        }
        return items
    }

    /** Из описания убираются остатки HTML — иначе теги попадают в словарь тональности. */
    private fun clean(raw: String): String = raw
        .replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&laquo;", "«")
        .replace("&raquo;", "»")
        .replace("&mdash;", "—")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return System.currentTimeMillis()
        return runCatching {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.recoverCatching {
            ZonedDateTime.parse(raw).toInstant().toEpochMilli()
        }.recoverCatching {
            Instant.parse(raw).toEpochMilli()
        }.getOrDefault(System.currentTimeMillis())
    }

    private companion object {
        const val CACHE_TTL_MILLIS = 15 * 60 * 1000L
        const val MAX_ITEMS = 600
    }
}
