package com.tinvesttrader.data.crypto

import com.tinvesttrader.data.Candle
import com.tinvesttrader.data.SecureTokenStore
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Спот Bybit через открытый программный интерфейс версии 5.
 *
 * Ключ создаётся в кабинете биржи с правом только на спотовую торговлю: без
 * вывода средств и переводов. Тогда даже утёкший ключ не позволит увести
 * деньги со счёта. Ключ и секрет хранятся в зашифрованном хранилище приложения.
 *
 * Тестовая сеть (api-testnet.bybit.com) — отдельные счета с ненастоящими
 * деньгами, аналог песочницы Т-Инвестиций. По умолчанию бот работает там.
 */
class BybitClient(
    private val apiKey: String,
    private val apiSecret: String,
    private val live: Boolean,
    private val http: OkHttpClient = defaultHttp,
    baseUrlOverride: String? = null,
) : CryptoExchange {

    private val baseUrl = baseUrlOverride ?: if (live) MAINNET else TESTNET
    private val json = Json { ignoreUnknownKeys = true }

    /** Разница часов телефона и биржи: подпись с чужим временем биржа отвергает. */
    private var clockOffsetMillis: Long? = null

    override val modeLabel: String = if (live) "LIVE" else "TESTNET"

    override suspend fun closedCandles(symbol: String, interval: String, limit: Int): List<Candle> {
        val code = intervalCode(interval)
        val result = publicGet(
            "/v5/market/kline",
            "category=spot&symbol=$symbol&interval=$code&limit=${limit.coerceIn(1, 1000)}",
        )
        val now = serverNowMillis()
        return parseKlines(result.getValue("list").jsonArray, intervalMillis(interval), now)
    }

    override suspend fun instrument(symbol: String): SpotInstrument {
        val result = publicGet("/v5/market/instruments-info", "category=spot&symbol=$symbol")
        val item = result.getValue("list").jsonArray.firstOrNull()?.jsonObject
            ?: error("Биржа не знает пару $symbol")
        val lot = item.getValue("lotSizeFilter").jsonObject
        return SpotInstrument(
            symbol = symbol,
            baseCoin = item.string("baseCoin"),
            quoteCoin = item.string("quoteCoin"),
            basePrecision = BigDecimal(lot.string("basePrecision")),
            minOrderQty = BigDecimal(lot.string("minOrderQty")),
            minOrderAmt = BigDecimal(lot.string("minOrderAmt")),
        )
    }

    override suspend fun availableBalances(coins: List<String>): Map<String, BigDecimal> {
        val result = signedGet(
            "/v5/account/wallet-balance",
            "accountType=UNIFIED&coin=${coins.joinToString(",")}",
        )
        val account = result.getValue("list").jsonArray.firstOrNull()?.jsonObject
            ?: return coins.associateWith { BigDecimal.ZERO }
        val rows = account["coin"]?.jsonArray ?: JsonArray(emptyList())
        val found = rows.associate { row ->
            val obj = row.jsonObject
            val total = obj.decimal("walletBalance")
            val locked = obj.decimal("locked")
            obj.string("coin") to (total - locked).max(BigDecimal.ZERO)
        }
        return coins.associateWith { found[it] ?: BigDecimal.ZERO }
    }

    override suspend fun marketBuy(symbol: String, quoteAmount: BigDecimal): String =
        createOrder(symbol, "Buy", quoteAmount.toPlainString(), "quoteCoin")

    override suspend fun marketSell(symbol: String, baseQty: BigDecimal): String =
        createOrder(symbol, "Sell", baseQty.toPlainString(), "baseCoin")

    private suspend fun createOrder(symbol: String, side: String, qty: String, unit: String): String {
        val body = buildJsonObject {
            put("category", "spot")
            put("symbol", symbol)
            put("side", side)
            put("orderType", "Market")
            put("qty", qty)
            put("marketUnit", unit)
        }.toString()
        val result = signedPost("/v5/order/create", body)
        return result.string("orderId")
    }

    // ---- транспорт ----

    private suspend fun publicGet(path: String, query: String): JsonObject =
        execute(Request.Builder().url("$baseUrl$path?$query").get().build())

    private suspend fun signedGet(path: String, query: String): JsonObject {
        val headers = authHeaders(query)
        val request = Request.Builder().url("$baseUrl$path?$query").get()
        headers.forEach { (k, v) -> request.header(k, v) }
        return execute(request.build())
    }

    private suspend fun signedPost(path: String, body: String): JsonObject {
        val headers = authHeaders(body)
        val request = Request.Builder().url("$baseUrl$path")
            .post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> request.header(k, v) }
        return execute(request.build())
    }

    private suspend fun authHeaders(payload: String): Map<String, String> {
        require(apiKey.isNotBlank() && apiSecret.isNotBlank()) { "Ключ или секрет биржи не заданы" }
        val timestamp = serverNowMillis().toString()
        return mapOf(
            "X-BAPI-API-KEY" to apiKey,
            "X-BAPI-TIMESTAMP" to timestamp,
            "X-BAPI-RECV-WINDOW" to RECV_WINDOW,
            "X-BAPI-SIGN" to sign(apiSecret, timestamp + apiKey + RECV_WINDOW + payload),
        )
    }

    private suspend fun serverNowMillis(): Long {
        val offset = clockOffsetMillis ?: runCatching {
            val result = execute(Request.Builder().url("$baseUrl/v5/market/time").get().build())
            val serverMillis = result.string("timeNano").toBigDecimal().movePointLeft(6).toLong()
            serverMillis - System.currentTimeMillis()
        }.getOrDefault(0L).also { clockOffsetMillis = it }
        return System.currentTimeMillis() + offset
    }

    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Биржа ответила HTTP ${response.code}: ${text.take(200)}")
            }
            val root = json.parseToJsonElement(text).jsonObject
            val code = root["retCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            if (code != 0) {
                val message = root["retMsg"]?.jsonPrimitive?.content.orEmpty()
                error("Биржа отклонила запрос (код $code): $message")
            }
            root["result"]?.jsonObject ?: JsonObject(emptyMap())
        }
    }

    companion object {
        const val MAINNET = "https://api.bybit.com"
        const val TESTNET = "https://api-testnet.bybit.com"
        private const val RECV_WINDOW = "10000"

        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Подпись запроса: HMAC-SHA256 в шестнадцатеричном виде, как требует биржа. */
        fun sign(secret: String, payload: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        fun intervalCode(interval: String): String = when (interval) {
            SecureTokenStore.INTERVAL_HOUR -> "60"
            else -> "D"
        }

        fun intervalMillis(interval: String): Long = when (interval) {
            SecureTokenStore.INTERVAL_HOUR -> 3_600_000L
            else -> 86_400_000L
        }

        /**
         * Биржа отдаёт свечи от новой к старой, и первая — ещё не закрытая.
         * Разворачиваем и выбрасываем всё, что закроется позже текущего момента.
         */
        fun parseKlines(list: JsonArray, intervalMillis: Long, nowMillis: Long): List<Candle> =
            list.map { it.jsonArray }
                .mapNotNull { row ->
                    val start = row[0].jsonPrimitive.content.toLongOrNull() ?: return@mapNotNull null
                    if (start + intervalMillis > nowMillis) return@mapNotNull null
                    Candle(
                        open = quotationOf(row[1].jsonPrimitive.content),
                        high = quotationOf(row[2].jsonPrimitive.content),
                        low = quotationOf(row[3].jsonPrimitive.content),
                        close = quotationOf(row[4].jsonPrimitive.content),
                        volume = row[5].jsonPrimitive.content,
                        time = Instant.ofEpochMilli(start).toString(),
                        isComplete = true,
                    )
                }
                .sortedBy { it.time }
    }
}

private fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.content ?: error("В ответе биржи нет поля $key")

private fun JsonObject.decimal(key: String): BigDecimal =
    this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull() ?: BigDecimal.ZERO
