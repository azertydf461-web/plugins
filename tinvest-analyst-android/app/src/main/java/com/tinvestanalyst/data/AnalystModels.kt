package com.tinvestanalyst.data

import kotlinx.serialization.Serializable

@Serializable
data class Quotation(val units: String = "0", val nano: Int = 0) {
    fun toDouble(): Double = units.toDouble() + nano / 1_000_000_000.0
}

@Serializable
data class Candle(
    val open: Quotation,
    val high: Quotation,
    val low: Quotation,
    val close: Quotation,
    val volume: String = "0",
    val time: String = "",
    val isComplete: Boolean = true,
) {
    val volumeAsDouble: Double get() = volume.toDoubleOrNull() ?: 0.0
}

@Serializable
data class GetCandlesResponse(val candles: List<Candle> = emptyList())

@Serializable
data class GetCandlesRequest(
    val instrumentId: String,
    val from: String,
    val to: String,
    val interval: String,
)

@Serializable
data class Instrument(
    val figi: String = "",
    val ticker: String = "",
    val classCode: String = "",
    val isin: String = "",
    val instrumentType: String = "",
    val name: String = "",
    val uid: String = "",
    val apiTradeAvailableFlag: Boolean = false,
)

@Serializable
data class FindInstrumentRequest(
    val query: String,
    val instrumentKind: String? = null,
    val apiTradeAvailableFlag: Boolean = true,
)

@Serializable
data class FindInstrumentResponse(val instruments: List<Instrument> = emptyList())

@Serializable
data class LastPrice(
    val figi: String = "",
    val price: Quotation = Quotation(),
    val time: String = "",
    val instrumentUid: String = "",
)

@Serializable
data class GetLastPricesRequest(val instrumentId: List<String>)

@Serializable
data class GetLastPricesResponse(val lastPrices: List<LastPrice> = emptyList())

@Serializable
data class OrderBookEntry(val price: Quotation = Quotation(), val quantity: String = "0")

@Serializable
data class GetOrderBookRequest(val instrumentId: String, val depth: Int = 10)

@Serializable
data class GetOrderBookResponse(
    val figi: String = "",
    val depth: Int = 0,
    val bids: List<OrderBookEntry> = emptyList(),
    val asks: List<OrderBookEntry> = emptyList(),
    val lastPrice: Quotation = Quotation(),
    val closePrice: Quotation = Quotation(),
)

@Serializable
data class GetTradingStatusRequest(val instrumentId: String)

@Serializable
data class GetTradingStatusResponse(
    val figi: String = "",
    val tradingStatus: String = "",
    val apiTradeAvailableFlag: Boolean = false,
)

/** Инструмент в списке наблюдения — хранится локально. */
@Serializable
data class WatchedInstrument(
    val figi: String,
    val ticker: String,
    val name: String,
)
