package com.tinvesttrader.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.UUID

private const val LIVE_BASE_URL = "https://invest-public-api.tbank.ru/rest/"
private const val SANDBOX_BASE_URL = "https://sandbox-invest-public-api.tbank.ru/rest/"

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

    suspend fun searchInstruments(query: String): List<Instrument> =
        currentApi().findInstrument(currentToken(), FindInstrumentRequest(query))
            .instruments
            .filter { it.figi.isNotBlank() && it.apiTradeAvailableFlag }
            .distinctBy { it.figi }
            .take(20)

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
}

enum class OrderDirection(val wireValue: String) {
    BUY("ORDER_DIRECTION_BUY"),
    SELL("ORDER_DIRECTION_SELL"),
}
