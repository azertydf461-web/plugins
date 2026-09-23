package com.tinvesttrader.crypto

import com.tinvesttrader.data.Candle
import com.tinvesttrader.data.crypto.BybitClient
import com.tinvesttrader.data.crypto.CryptoExchange
import com.tinvesttrader.data.crypto.SpotInstrument
import com.tinvesttrader.data.crypto.floorToStep
import com.tinvesttrader.data.crypto.quotationOf
import com.tinvesttrader.data.SecureTokenStore
import com.tinvesttrader.trading.CryptoBotSettings
import com.tinvesttrader.trading.CryptoTradingEngine
import com.tinvesttrader.trading.DecisionAction
import com.tinvesttrader.trading.DecisionRecord
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoBotTest {

    // ---- числа и подпись ----

    @Test
    fun signatureMatchesReferenceHmac() {
        // Эталон посчитан отдельно: hmac.new(b'secret123', payload, sha256).hexdigest()
        assertEquals(
            "c214a6f8e6c83640883bc9c5254cd27e24268c1d2c4e5f67f793a9fde7c5ed9c",
            BybitClient.sign("secret123", "1700000000000KEY10000{\"a\":1}"),
        )
    }

    @Test
    fun quantityIsRoundedDownToExchangeStep() {
        assertEquals("0.001234", floorToStep(BigDecimal("0.0012349"), BigDecimal("0.000001")).toPlainString())
        assertEquals("12", floorToStep(BigDecimal("12.9"), BigDecimal("1")).toPlainString())
    }

    @Test
    fun priceStringBecomesQuotation() {
        val q = quotationOf("64123.45")
        assertEquals("64123", q.units)
        assertEquals(450_000_000, q.nano)
        assertEquals(0.12345, quotationOf("0.12345").toDouble(), 1e-12)
    }

    @Test
    fun unfinishedKlineIsDroppedAndOrderIsChronological() {
        val day = 86_400_000L
        val now = 10 * day + day / 2 // середина одиннадцатого дня
        val raw = """[["${10 * day}","5","6","4","5.5","1","1"],["${9 * day}","4","5","3","4.5","1","1"],["${8 * day}","3","4","2","3.5","1","1"]]"""
        val candles = BybitClient.parseKlines(Json.parseToJsonElement(raw).jsonArray, day, now)
        assertEquals(2, candles.size)
        assertEquals(Instant.ofEpochMilli(8 * day).toString(), candles.first().time)
        assertEquals(4.5, candles.last().close.toDouble(), 1e-9)
    }

    // ---- запросы к бирже ----

    @Test
    fun marketBuyIsSignedAndSpendsQuoteCoin() = runBlocking {
        val seen = mutableListOf<Request>()
        val client = BybitClient("KEY", "SECRET", live = false, http = fakeHttp(seen), baseUrlOverride = "https://test.local")
        val id = client.marketBuy("BTCUSDT", BigDecimal("50.00"))
        assertEquals("ORD-1", id)

        val order = seen.last { it.url.encodedPath == "/v5/order/create" }
        val body = Buffer().also { order.body!!.writeTo(it) }.readUtf8()
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("Buy", json["side"]!!.jsonPrimitive.content)
        assertEquals("Market", json["orderType"]!!.jsonPrimitive.content)
        assertEquals("quoteCoin", json["marketUnit"]!!.jsonPrimitive.content)
        assertEquals("50.00", json["qty"]!!.jsonPrimitive.content)

        val ts = order.header("X-BAPI-TIMESTAMP")!!
        assertEquals(BybitClient.sign("SECRET", ts + "KEY" + "10000" + body), order.header("X-BAPI-SIGN"))
    }

    @Test
    fun walletBalanceSubtractsLockedAmount() = runBlocking {
        val client = BybitClient("KEY", "SECRET", live = false, http = fakeHttp(mutableListOf()), baseUrlOverride = "https://test.local")
        val balances = client.availableBalances(listOf("BTC", "USDT"))
        assertEquals(BigDecimal("0.5"), balances["BTC"]!!.stripTrailingZeros())
        assertEquals(BigDecimal("80"), balances["USDT"]!!.stripTrailingZeros())
    }

    private fun fakeHttp(seen: MutableList<Request>) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            seen += request
            val result = when (request.url.encodedPath) {
                "/v5/market/time" -> """{"timeSecond":"1700000000","timeNano":"1700000000000000000"}"""
                "/v5/order/create" -> """{"orderId":"ORD-1","orderLinkId":""}"""
                "/v5/account/wallet-balance" ->
                    """{"list":[{"coin":[{"coin":"BTC","walletBalance":"0.6","locked":"0.1"},{"coin":"USDT","walletBalance":"100","locked":"20"}]}]}"""
                else -> "{}"
            }
            Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"retCode":0,"retMsg":"OK","result":$result}""".toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()

    // ---- торговый цикл ----

    private class FakeExchange(var candles: List<Candle>) : CryptoExchange {
        var base = BigDecimal("0")
        var quote = BigDecimal("1000")
        val buys = mutableListOf<BigDecimal>()
        val sells = mutableListOf<BigDecimal>()
        override val modeLabel = "TESTNET"
        override suspend fun closedCandles(symbol: String, interval: String, limit: Int) = candles
        override suspend fun instrument(symbol: String) = SpotInstrument(
            symbol, "BTC", "USDT", BigDecimal("0.000001"), BigDecimal("0.000048"), BigDecimal("1"),
        )
        override suspend fun availableBalances(coins: List<String>) = mapOf("BTC" to base, "USDT" to quote)
        override suspend fun marketBuy(symbol: String, quoteAmount: BigDecimal): String {
            buys += quoteAmount
            quote -= quoteAmount
            base += quoteAmount.divide(BigDecimal(candles.last().close.toDouble().toString()), 6, java.math.RoundingMode.DOWN)
            return "B${buys.size}"
        }
        override suspend fun marketSell(symbol: String, baseQty: BigDecimal): String {
            sells += baseQty
            base -= baseQty
            return "S${sells.size}"
        }
    }

    private class MemorySettings : CryptoBotSettings {
        override val symbol = "BTCUSDT"
        override val interval = SecureTokenStore.INTERVAL_DAY
        override val quoteAmount = 100.0
        override var lastCandleTime: String? = null
        override var positionBaseline: String? = null
    }

    /** Дневные свечи с заданными закрытиями; максимум и минимум — вплотную к закрытию. */
    private fun series(closes: List<Double>): List<Candle> = closes.mapIndexed { i, c ->
        Candle(
            open = quotationOf(c.toString()), high = quotationOf((c * 1.001).toString()),
            low = quotationOf((c * 0.999).toString()), close = quotationOf(c.toString()),
            volume = "1", time = Instant.ofEpochMilli(i * 86_400_000L).toString(),
        )
    }

    private val flat = List(30) { 100.0 }

    @Test
    fun breakoutBuysFixedQuoteAmountAndRemembersBaseline() = runBlocking {
        val exchange = FakeExchange(series(flat + 110.0))
        exchange.base = BigDecimal("0.3") // монеты пользователя, лежавшие до бота
        val settings = MemorySettings()
        val records = mutableListOf<DecisionRecord>()
        val result = CryptoTradingEngine(exchange, settings, records::add).tick()

        assertEquals(DecisionAction.BUY, result.decisionAction)
        assertEquals(listOf(BigDecimal("100.00")), exchange.buys)
        assertEquals("0.3", settings.positionBaseline)
        assertEquals(1, records.size)
    }

    @Test
    fun breakdownSellsOnlyWhatTheBotBought() = runBlocking {
        val exchange = FakeExchange(series(flat + 110.0))
        exchange.base = BigDecimal("0.3")
        val settings = MemorySettings()
        CryptoTradingEngine(exchange, settings, {}).tick()
        val bought = exchange.base - BigDecimal("0.3")

        exchange.candles = series(flat + 110.0 + List(12) { 111.0 } + 90.0)
        val result = CryptoTradingEngine(exchange, settings, {}).tick()

        assertEquals(DecisionAction.SELL, result.decisionAction)
        assertEquals(0, bought.compareTo(exchange.sells.single()))
        assertEquals(0, BigDecimal("0.3").compareTo(exchange.base))
        assertNull(settings.positionBaseline)
    }

    @Test
    fun sellSignalWithoutBotPositionNeverTouchesUserCoins() = runBlocking {
        val exchange = FakeExchange(series(flat + 90.0))
        exchange.base = BigDecimal("0.3")
        val result = CryptoTradingEngine(exchange, MemorySettings(), {}).tick()
        assertTrue(exchange.sells.isEmpty())
        assertEquals(DecisionAction.HOLD, result.decisionAction)
    }

    @Test
    fun missingQuoteBalanceBlocksTheBuy() = runBlocking {
        val exchange = FakeExchange(series(flat + 110.0))
        exchange.quote = BigDecimal("20")
        val settings = MemorySettings()
        val result = CryptoTradingEngine(exchange, settings, {}).tick()
        assertEquals(DecisionAction.BLOCKED, result.decisionAction)
        assertTrue(exchange.buys.isEmpty())
        assertNull(settings.positionBaseline)
    }

    @Test
    fun secondBuySignalWhileHoldingDoesNotDoubleThePosition() = runBlocking {
        val exchange = FakeExchange(series(flat + 110.0))
        val settings = MemorySettings()
        CryptoTradingEngine(exchange, settings, {}).tick()
        CryptoTradingEngine(exchange, settings, {}).tick()
        assertEquals(1, exchange.buys.size)
    }
}
