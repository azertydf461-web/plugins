package com.tinvestanalyst.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val BASE_URL = "https://invest-public-api.tbank.ru/rest/"

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

    /** Один запрос на весь список наблюдения — так не упираемся в лимиты API. */
    suspend fun loadLastPrices(figis: List<String>): Map<String, Double> {
        if (figis.isEmpty()) return emptyMap()
        return api.getLastPrices(auth(), GetLastPricesRequest(figis))
            .lastPrices
            .associate { it.figi to it.price.toDouble() }
    }

    suspend fun loadOrderBook(figi: String, depth: Int = 10): GetOrderBookResponse =
        api.getOrderBook(auth(), GetOrderBookRequest(figi, depth))

    suspend fun loadTradingStatus(figi: String): GetTradingStatusResponse =
        api.getTradingStatus(auth(), GetTradingStatusRequest(figi))
}
