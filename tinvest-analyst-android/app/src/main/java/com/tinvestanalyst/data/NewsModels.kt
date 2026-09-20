package com.tinvestanalyst.data

/** Одна новость из ленты СМИ. */
data class NewsItem(
    val title: String,
    val summary: String,
    val link: String,
    val source: String,
    val publishedAtMillis: Long,
) {
    /** Заголовок и краткий текст в одной строке — по ней идёт и поиск, и тональность. */
    val searchText: String get() = "$title. $summary"
}

/** Публичная RSS-лента: ключей не требует, поэтому работает без регистрации. */
data class NewsSource(val title: String, val url: String)

/**
 * Ленты выбраны по одному признаку: открытый RSS без ключа и авторизации.
 * Брокерский API новостей не отдаёт вовсе, поэтому источник внешний.
 */
val NEWS_SOURCES = listOf(
    NewsSource("Интерфакс", "https://www.interfax.ru/rss.asp"),
    NewsSource("РБК Инвестиции", "https://rssexport.rbc.ru/rbcnews/news/30/full.rss"),
    NewsSource("Прайм", "https://1prime.ru/export/rss2/index.xml"),
    NewsSource("Финам", "https://www.finam.ru/net/analysis/conews/rsspoint"),
)
