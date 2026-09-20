package com.tinvesttrader.data

import kotlinx.serialization.Serializable

// DTO для T-Invest REST API (invest-public-api.tbank.ru/rest/...).
// Поля сокращены до того, что реально используется прототипом —
// полная схема см. в официальной документации API брокера.

@Serializable
data class MoneyValue(
    val currency: String,
    val units: String = "0",
    val nano: Int = 0,
) {
    fun toDouble(): Double = units.toDouble() + nano / 1_000_000_000.0
}

@Serializable
data class Quotation(
    val units: String = "0",
    val nano: Int = 0,
) {
    fun toDouble(): Double = units.toDouble() + nano / 1_000_000_000.0
}

@Serializable
data class Account(
    val id: String,
    val name: String,
    val type: String,
)

@Serializable
data class GetAccountsResponse(
    val accounts: List<Account>,
)

@Serializable
data class Candle(
    val open: Quotation,
    val high: Quotation,
    val low: Quotation,
    val close: Quotation,
    val volume: String,
    val time: String,
    val isComplete: Boolean = true,
)

@Serializable
data class GetCandlesResponse(
    val candles: List<Candle>,
)

@Serializable
data class PostOrderRequest(
    val instrumentId: String,
    val quantity: String,
    val price: Quotation? = null,
    val direction: String, // ORDER_DIRECTION_BUY | ORDER_DIRECTION_SELL
    val accountId: String,
    val orderType: String, // ORDER_TYPE_MARKET | ORDER_TYPE_LIMIT
    val orderId: String,
)

@Serializable
data class PostOrderResponse(
    val orderId: String,
    val executionReportStatus: String,
    val lotsRequested: Long = 0,
    val lotsExecuted: Long = 0,
)

// --- Стоп-заявки на стороне брокера -----------------------------------
// Обычная заявка (PostOrder) исполняется сразу; стоп-заявка живёт на сервере
// брокера и срабатывает без участия приложения — именно это и нужно, чтобы
// стоп-лосс работал, пока телефон спит.

@Serializable
data class PostStopOrderRequest(
    val instrumentId: String,
    val quantity: String,
    /** Цена срабатывания. По достижении брокер выставляет заявку сам. */
    val stopPrice: Quotation,
    val direction: String, // STOP_ORDER_DIRECTION_SELL
    val accountId: String,
    val expirationType: String, // STOP_ORDER_EXPIRATION_TYPE_GOOD_TILL_CANCEL
    val stopOrderType: String, // STOP_ORDER_TYPE_STOP_LOSS
)

@Serializable
data class PostStopOrderResponse(val stopOrderId: String = "")

@Serializable
data class GetStopOrdersRequest(val accountId: String)

@Serializable
data class StopOrder(
    val stopOrderId: String = "",
    val figi: String = "",
    val lotsRequested: String = "0",
    val stopPrice: MoneyValue? = null,
    val direction: String = "",
)

@Serializable
data class GetStopOrdersResponse(val stopOrders: List<StopOrder> = emptyList())

@Serializable
data class CancelStopOrderRequest(
    val accountId: String,
    val stopOrderId: String,
)

@Serializable
data class CancelStopOrderResponse(val time: String = "")

@Serializable
data class Position(
    val figi: String,
    val quantity: Quotation,
    val averagePositionPrice: MoneyValue,
    val currentPrice: MoneyValue,
)

@Serializable
data class Instrument(
    val figi: String = "",
    val ticker: String = "",
    val name: String = "",
    val instrumentType: String = "",
    val currency: String = "",
    val lot: Int = 1,
    val apiTradeAvailableFlag: Boolean = false,
    val buyAvailableFlag: Boolean = false,
    val forQualInvestorFlag: Boolean = false,
)

/** Каталог инструментов брокера: один вид активов на запрос. */
enum class InstrumentCategory(val title: String, val endpointPath: String) {
    SHARES("Акции", "Shares"),
    BONDS("Облигации", "Bonds"),
    ETFS("Фонды", "Etfs"),
    CURRENCIES("Валюты", "Currencies"),
    FUTURES("Фьючерсы", "Futures"),
}

@Serializable
data class InstrumentsRequest(
    /** Только то, чем реально можно торговать через API. */
    val instrumentStatus: String = "INSTRUMENT_STATUS_BASE",
)

@Serializable
data class InstrumentsResponse(val instruments: List<Instrument> = emptyList())

@Serializable
data class PortfolioResponse(
    val totalAmountShares: MoneyValue,
    val totalAmountCurrencies: MoneyValue,
    val positions: List<Position> = emptyList(),
)
