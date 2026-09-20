package com.tinvesttrader.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val LIVE_BASE_URL = "https://invest-public-api.tbank.ru/rest/"
private const val SANDBOX_BASE_URL = "https://sandbox-invest-public-api.tbank.ru/rest/"

/** Потолок числа запросов на один прогон истории — защита от лимитов API. */
private const val MAX_HISTORY_CHUNKS = 16

class TInvestRepository(private val tokenStore: SecureTokenStore) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun buildApi(baseUrl: String): TInvestApi {
        val logging = HttpLoggingInterceptor().apply {
            // Тело запроса содержит торговые параметры — не логируем в проде,
            // BASIC оставляет только код ответа и таймы.
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(TInvestApi::class.java)
    }

    // Ленивая инициализация: клиенты создаются один раз и переиспользуются
    // между вызовами движка, чтобы не плодить OkHttp-пулы соединений.
    private val liveApi by lazy { buildApi(LIVE_BASE_URL) }
    private val sandboxApi by lazy { buildApi(SANDBOX_BASE_URL) }

    /** Единственная точка, откуда движок узнаёт, боевой сейчас режим или нет. */
    val isLiveMode: Boolean
        get() = tokenStore.liveTradingEnabled

    private fun currentApi(): TInvestApi = if (isLiveMode) liveApi else sandboxApi

    private fun currentToken(): String {
        val token = if (isLiveMode) tokenStore.liveToken else tokenStore.sandboxToken
        return "Bearer ${token ?: throw IllegalStateException(
            "Токен не задан для режима ${if (isLiveMode) "LIVE" else "SANDBOX"}. Откройте Settings.",
        )}"
    }

    suspend fun getAccounts(): List<Account> =
        currentApi().getAccounts(currentToken()).accounts

    /**
     * В песочнице счёта не существует, пока его не откроют через API, поэтому
     * без этого вызова боту нечего указывать в настройках. На боевом контуре
     * метод недоступен — счета там заводит сам брокер.
     */
    suspend fun openSandboxAccount(): String {
        check(!isLiveMode) { "Счёт в песочнице создаётся только в SANDBOX-режиме." }
        return sandboxApi.openSandboxAccount(currentToken()).accountId
    }

    /** Пополнение виртуального счёта: без денег песочница не даст купить ничего. */
    suspend fun payInSandbox(accountId: String, rubles: Long): Double {
        check(!isLiveMode) { "Пополнение доступно только в SANDBOX-режиме." }
        return sandboxApi.sandboxPayIn(
            currentToken(),
            SandboxPayInRequest(accountId, MoneyValue(currency = "rub", units = rubles.toString())),
        ).balance.toDouble()
    }

    // Каталог меняется редко и весит много, поэтому держим его в памяти.
    private val catalogCache = ConcurrentHashMap<InstrumentCategory, List<Instrument>>()

    /**
     * Инструменты выбранного вида, доступные к торгам через API. Каталог
     * отдаёт только боевой контур, поэтому запрос всегда идёт туда: в
     * песочнице торгуются те же бумаги.
     */
    suspend fun loadCatalog(category: InstrumentCategory): List<Instrument> {
        catalogCache[category]?.let { return it }
        val token = tokenStore.liveToken?.takeIf { it.isNotBlank() }
            ?: tokenStore.sandboxToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Сначала сохраните токен.")
        val instruments = liveApi.getInstruments(
            "tinkoff.public.invest.api.contract.v1.InstrumentsService/${category.endpointPath}",
            "Bearer $token",
            InstrumentsRequest(),
        ).instruments
            .filter { it.apiTradeAvailableFlag && it.buyAvailableFlag && it.figi.isNotBlank() }
            .sortedBy { it.ticker }
        catalogCache[category] = instruments
        return instruments
    }

    suspend fun getPortfolio(accountId: String): PortfolioResponse =
        currentApi().getPortfolio(currentToken(), GetPortfolioRequest(accountId))

    suspend fun getRecentCandles(
        figi: String,
        interval: String = "CANDLE_INTERVAL_5_MIN",
        lookbackMinutes: Long = 60 * 12,
    ): List<Candle> {
        val to = Instant.now()
        val from = to.minusSeconds(lookbackMinutes * 60)
        return currentApi().getCandles(
            currentToken(),
            GetCandlesRequest(
                instrumentId = figi,
                from = from.toString(),
                to = to.toString(),
                interval = interval,
            ),
        ).candles
    }

    /**
     * Длинная история для прогона стратегии. API ограничивает длину одного
     * запроса свечей, поэтому период режется на куски: иначе двухнедельный
     * запрос пятиминутных свечей просто вернёт ошибку. Не отдавшийся кусок
     * пропускается — проверка на неполной истории полезнее, чем никакой.
     *
     * История всегда берётся из боевого контура: в песочнице своей рыночной
     * истории нет, а проверять стратегию надо на настоящих ценах.
     */
    suspend fun getHistory(figi: String, interval: String, daysBack: Long): List<Candle> {
        val chunkDays = when (interval) {
            "CANDLE_INTERVAL_DAY" -> 360L
            "CANDLE_INTERVAL_HOUR" -> 30L
            "CANDLE_INTERVAL_15_MIN" -> 3L
            else -> 1L
        }
        val token = tokenStore.liveToken?.takeIf { it.isNotBlank() }
            ?.let { "Bearer $it" }
            ?: currentToken()
        val api = if (tokenStore.liveToken.isNullOrBlank()) currentApi() else liveApi

        val to = Instant.now()
        val collected = mutableListOf<Candle>()
        var offset = daysBack
        var guard = 0
        while (offset > 0 && guard < MAX_HISTORY_CHUNKS) {
            val chunkFrom = to.minusSeconds(offset * 86_400)
            val chunkTo = to.minusSeconds((offset - minOf(offset, chunkDays)) * 86_400)
            runCatching {
                api.getCandles(
                    token,
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

    suspend fun placeOrder(
        accountId: String,
        figi: String,
        lots: Long,
        direction: OrderDirection,
    ): PostOrderResponse {
        require(lots > 0) { "Количество лотов должно быть положительным" }
        return currentApi().postOrder(
            currentToken(),
            PostOrderRequest(
                instrumentId = figi,
                quantity = lots.toString(),
                direction = direction.wireValue,
                accountId = accountId,
                orderType = "ORDER_TYPE_MARKET",
                orderId = UUID.randomUUID().toString(),
            ),
        )
    }

    /**
     * Защитная стоп-заявка на продажу: живёт на сервере брокера и срабатывает
     * сама, без участия приложения. Без неё стоп-лосс работает только когда
     * бот проснулся, а на разрыве цены это значит убыток заметно больше
     * задуманного.
     */
    suspend fun placeProtectiveStop(
        accountId: String,
        figi: String,
        lots: Long,
        stopPrice: Double,
    ): String {
        require(lots > 0) { "Количество лотов должно быть положительным" }
        require(stopPrice > 0) { "Цена стопа должна быть положительной" }
        return currentApi().postStopOrder(
            currentToken(),
            PostStopOrderRequest(
                instrumentId = figi,
                quantity = lots.toString(),
                stopPrice = stopPrice.toQuotation(),
                direction = "STOP_ORDER_DIRECTION_SELL",
                accountId = accountId,
                // Заявка не должна истекать сама: позиция может держаться
                // дольше торгового дня, а незащищённая позиция недопустима.
                expirationType = "STOP_ORDER_EXPIRATION_TYPE_GOOD_TILL_CANCEL",
                stopOrderType = "STOP_ORDER_TYPE_STOP_LOSS",
            ),
        ).stopOrderId
    }

    suspend fun getStopOrders(accountId: String): List<StopOrder> =
        currentApi().getStopOrders(currentToken(), GetStopOrdersRequest(accountId)).stopOrders

    suspend fun cancelStopOrder(accountId: String, stopOrderId: String) {
        currentApi().cancelStopOrder(
            currentToken(),
            CancelStopOrderRequest(accountId = accountId, stopOrderId = stopOrderId),
        )
    }
}

/** Цена в формате API: целая часть и миллиардные доли отдельно. */
internal fun Double.toQuotation(): Quotation {
    val units = kotlin.math.floor(this).toLong()
    val nano = ((this - units) * 1_000_000_000).toInt()
    return Quotation(units = units.toString(), nano = nano)
}

enum class OrderDirection(val wireValue: String) {
    BUY("ORDER_DIRECTION_BUY"),
    SELL("ORDER_DIRECTION_SELL"),
}
