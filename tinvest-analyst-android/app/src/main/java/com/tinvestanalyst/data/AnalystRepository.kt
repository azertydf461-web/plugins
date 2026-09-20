package com.tinvestanalyst.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val BASE_URL = "https://invest-public-api.tbank.ru/rest/"

/** Потолок числа запросов на один прогон истории — защита от лимитов API. */
private const val MAX_HISTORY_CHUNKS = 14

class AnalystRepository(private val settings: AnalystSettingsStore) {

    private val json = Json { ignoreUnknownKeys = true }

    private val api: AnalystApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AnalystApi::class.java)
    }

    private fun auth(): String {
        val token = settings.apiToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Не задан токен. Откройте настройки и вставьте токен T-Инвестиций.")
        return "Bearer $token"
    }

    // Каталог меняется редко, а весит много (тысячи бумаг), поэтому держим
    // его в памяти до перезапуска приложения вместо повторных загрузок.
    private val catalogCache = ConcurrentHashMap<InstrumentCategory, List<Instrument>>()

    /**
     * Список инструментов выбранного типа, отфильтрованный до тех, которыми
     * реально можно торговать через API: остальное показывать бессмысленно —
     * по ним не будет ни заявок, ни части рыночных данных.
     */
    suspend fun loadCatalog(
        category: InstrumentCategory,
        forceRefresh: Boolean = false,
    ): List<Instrument> {
        if (!forceRefresh) catalogCache[category]?.let { return it }
        val instruments = api.getInstruments(
            "tinkoff.public.invest.api.contract.v1.InstrumentsService/${category.endpointPath}",
            auth(),
            InstrumentsRequest(),
        ).instruments
            .filter { it.apiTradeAvailableFlag && it.buyAvailableFlag && it.figi.isNotBlank() }
            .sortedBy { it.ticker }
        catalogCache[category] = instruments
        return instruments
    }

    suspend fun searchInstruments(query: String): List<Instrument> =
        api.findInstrument(auth(), FindInstrumentRequest(query = query))
            .instruments
            .filter { it.figi.isNotBlank() }
            .distinctBy { it.figi }
            .take(25)

    /**
     * Глубина истории подбирается под интервал: индикаторам нужно минимум
     * ~60 свечей, иначе MACD(26) и SMA(50) просто не посчитаются.
     */
    suspend fun loadCandles(figi: String, interval: String, barsWanted: Int = 120): List<Candle> {
        val minutesPerBar = when (interval) {
            "CANDLE_INTERVAL_1_MIN" -> 1L
            "CANDLE_INTERVAL_5_MIN" -> 5L
            "CANDLE_INTERVAL_15_MIN" -> 15L
            "CANDLE_INTERVAL_HOUR" -> 60L
            "CANDLE_INTERVAL_DAY" -> 60L * 24
            else -> 15L
        }
        // Биржа закрыта ночью и в выходные, поэтому запрашиваем запас по времени:
        // иначе на утреннем открытии история окажется почти пустой.
        val lookbackMinutes = minutesPerBar * barsWanted * 3
        val to = Instant.now()
        val from = to.minusSeconds(lookbackMinutes * 60)
        return api.getCandles(
            auth(),
            GetCandlesRequest(figi, from.toString(), to.toString(), interval),
        ).candles.takeLast(barsWanted)
    }

    /**
     * Длинная история для бэктеста. API ограничивает длину одного запроса по
     * свечам, поэтому период режется на куски и склеивается: иначе трёхлетний
     * запрос просто вернёт ошибку. Кусок, который не отдался, пропускается —
     * лучше проверка на неполной истории, чем никакой.
     */
    suspend fun loadHistory(figi: String, interval: String, daysBack: Long): List<Candle> {
        val chunkDays = when (interval) {
            "CANDLE_INTERVAL_DAY" -> 360L
            "CANDLE_INTERVAL_HOUR" -> 30L
            else -> 5L
        }
        val to = Instant.now()
        val collected = mutableListOf<Candle>()
        var offset = daysBack
        var guard = 0
        while (offset > 0 && guard < MAX_HISTORY_CHUNKS) {
            val chunkTo = to.minus(offset - minOf(offset, chunkDays), ChronoUnit.DAYS)
            val chunkFrom = to.minus(offset, ChronoUnit.DAYS)
            runCatching {
                api.getCandles(
                    auth(),
                    GetCandlesRequest(figi, chunkFrom.toString(), chunkTo.toString(), interval),
                ).candles
            }.onSuccess { collected += it }
            offset -= chunkDays
            guard++
        }
        return collected
            .filter { it.close.toDouble() > 0 }
            .distinctBy { it.time }
            .sortedBy { it.time }
    }

    /** Один запрос на весь список наблюдения — так не упираемся в лимиты API. */
    suspend fun loadLastPrices(figis: List<String>): Map<String, Double> {
        if (figis.isEmpty()) return emptyMap()
        return api.getLastPrices(auth(), GetLastPricesRequest(figis))
            .lastPrices
            .associate { it.figi to it.price.toDouble() }
    }

    private val fundamentalsCache = ConcurrentHashMap<String, AssetFundamental>()
    private val dividendsCache = ConcurrentHashMap<String, List<Dividend>>()
    private val reportsCache = ConcurrentHashMap<String, List<AssetReportEvent>>()

    /**
     * Показатели отчётности сразу по группе активов: API принимает список, и
     * один запрос на весь список наблюдения дешевле, чем запрос на бумагу.
     * Данные меняются раз в квартал, поэтому кешируются до перезапуска.
     */
    suspend fun loadFundamentals(assetUids: List<String>): Map<String, AssetFundamental> {
        val missing = assetUids.filter { it.isNotBlank() && !fundamentalsCache.containsKey(it) }
        if (missing.isNotEmpty()) {
            runCatching {
                api.getAssetFundamentals(auth(), GetAssetFundamentalsRequest(missing.distinct().take(100)))
                    .fundamentals
            }.onSuccess { list ->
                list.forEach { item -> fundamentalsCache[item.assetUid] = item }
            }
        }
        return assetUids.mapNotNull { uid -> fundamentalsCache[uid]?.let { uid to it } }.toMap()
    }

    /** История дивидендов за три года назад и год вперёд — вперёд, чтобы увидеть объявленную отсечку. */
    suspend fun loadDividends(instrumentId: String): List<Dividend> {
        dividendsCache[instrumentId]?.let { return it }
        val now = Instant.now()
        val dividends = runCatching {
            api.getDividends(
                auth(),
                GetDividendsRequest(
                    instrumentId = instrumentId,
                    from = now.minus(1095, ChronoUnit.DAYS).toString(),
                    to = now.plus(365, ChronoUnit.DAYS).toString(),
                ),
            ).dividends
        }.getOrDefault(emptyList())
        dividendsCache[instrumentId] = dividends
        return dividends
    }

    /** Ближайшие даты публикации отчётности — это событийный риск на горизонте сделки. */
    suspend fun loadReports(instrumentId: String): List<AssetReportEvent> {
        if (instrumentId.isBlank()) return emptyList()
        reportsCache[instrumentId]?.let { return it }
        val now = Instant.now()
        val events = runCatching {
            api.getAssetReports(
                auth(),
                GetAssetReportsRequest(
                    instrumentId = instrumentId,
                    from = now.minus(180, ChronoUnit.DAYS).toString(),
                    to = now.plus(180, ChronoUnit.DAYS).toString(),
                ),
            ).events
        }.getOrDefault(emptyList())
        reportsCache[instrumentId] = events
        return events
    }

    suspend fun loadOrderBook(figi: String, depth: Int = 10): GetOrderBookResponse =
        api.getOrderBook(auth(), GetOrderBookRequest(figi, depth))

    suspend fun loadTradingStatus(figi: String): GetTradingStatusResponse =
        api.getTradingStatus(auth(), GetTradingStatusRequest(figi))
}
