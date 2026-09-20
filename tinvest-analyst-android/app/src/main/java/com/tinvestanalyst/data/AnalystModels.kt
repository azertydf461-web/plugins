package com.tinvestanalyst.data

import kotlinx.serialization.Serializable

@Serializable
data class Quotation(val units: String = "0", val nano: Int = 0) {
    fun toDouble(): Double = (units.toDoubleOrNull() ?: 0.0) + nano / 1_000_000_000.0
}

/** Денежная величина: то же число, что Quotation, но с валютой. */
@Serializable
data class MoneyValue(
    val currency: String = "rub",
    val units: String = "0",
    val nano: Int = 0,
) {
    fun toDouble(): Double = (units.toDoubleOrNull() ?: 0.0) + nano / 1_000_000_000.0
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
    val assetUid: String = "",
    val currency: String = "",
    val exchange: String = "",
    val sector: String = "",
    val lot: Int = 1,
    /** Ставки риска брокера: из них считается доступное плечо (1 / dlong). */
    val dlong: Quotation = Quotation(),
    val dshort: Quotation = Quotation(),
    val apiTradeAvailableFlag: Boolean = false,
    val buyAvailableFlag: Boolean = false,
    val sellAvailableFlag: Boolean = false,
    val shortEnabledFlag: Boolean = false,
    val forQualInvestorFlag: Boolean = false,
)

// --- Дивиденды ---------------------------------------------------------

@Serializable
data class Dividend(
    val dividendNet: MoneyValue = MoneyValue(),
    val paymentDate: String = "",
    val declaredDate: String = "",
    val lastBuyDate: String = "",
    val recordDate: String = "",
    val dividendType: String = "",
    val closePrice: MoneyValue = MoneyValue(),
    val yieldValue: Quotation = Quotation(),
)

@Serializable
data class GetDividendsRequest(
    val instrumentId: String,
    val from: String,
    val to: String,
)

@Serializable
data class GetDividendsResponse(val dividends: List<Dividend> = emptyList())

// --- Фундаментальные показатели ----------------------------------------

/**
 * Показатели отчётности эмитента. Поля приходят не для всех бумаг (особенно
 * для облигаций и малоликвидных акций), поэтому все они nullable: отсутствие
 * данных должно честно показываться как «нет данных», а не как ноль.
 */
@Serializable
data class AssetFundamental(
    val assetUid: String = "",
    val currency: String = "",
    val marketCapitalization: Double? = null,
    val highPriceLast52Weeks: Double? = null,
    val lowPriceLast52Weeks: Double? = null,
    val averageDailyVolumeLast10Days: Double? = null,
    val beta: Double? = null,
    val freeFloat: Double? = null,
    val forwardAnnualDividendYield: Double? = null,
    val sharesOutstanding: Double? = null,
    val revenueTtm: Double? = null,
    val ebitdaTtm: Double? = null,
    val netIncomeTtm: Double? = null,
    val epsTtm: Double? = null,
    val freeCashFlowTtm: Double? = null,
    val peRatioTtm: Double? = null,
    val priceToSalesTtm: Double? = null,
    val priceToBookTtm: Double? = null,
    val evToEbitdaMrq: Double? = null,
    val netMarginMrq: Double? = null,
    val roe: Double? = null,
    val roa: Double? = null,
    val roic: Double? = null,
    val totalDebtMrq: Double? = null,
    val totalDebtToEquityMrq: Double? = null,
    val totalDebtToEbitdaMrq: Double? = null,
    val netDebtToEbitda: Double? = null,
    val currentRatioMrq: Double? = null,
    val dividendYieldDailyTtm: Double? = null,
    val dividendPayoutRatioFy: Double? = null,
    val fiveYearsAverageDividendYield: Double? = null,
    val oneYearAnnualRevenueGrowthRate: Double? = null,
    val threeYearAnnualRevenueGrowthRate: Double? = null,
    val fiveYearAnnualRevenueGrowthRate: Double? = null,
    val exDividendDate: String? = null,
    val numberOfEmployees: Double? = null,
    val domicileIndicatorCode: String? = null,
)

@Serializable
data class GetAssetFundamentalsRequest(val assets: List<String>)

@Serializable
data class GetAssetFundamentalsResponse(val fundamentals: List<AssetFundamental> = emptyList())

// --- Календарь отчётности ----------------------------------------------

@Serializable
data class AssetReportEvent(
    val instrumentId: String = "",
    val reportDate: String = "",
    val periodYear: Int = 0,
    val periodNum: Int = 0,
    val periodType: String = "",
)

@Serializable
data class GetAssetReportsRequest(
    val instrumentId: String,
    val from: String,
    val to: String,
)

@Serializable
data class GetAssetReportsResponse(val events: List<AssetReportEvent> = emptyList())

/** Каталог инструментов брокера: один тип активов на запрос. */
enum class InstrumentCategory(val title: String, val endpointPath: String) {
    SHARES("Акции", "Shares"),
    BONDS("Облигации", "Bonds"),
    ETFS("Фонды", "Etfs"),
    CURRENCIES("Валюты", "Currencies"),
    FUTURES("Фьючерсы", "Futures"),
}

@Serializable
data class InstrumentsRequest(
    /**
     * INSTRUMENT_STATUS_BASE — только инструменты, доступные для торговли
     * через API. Именно этот фильтр отсекает всё, что брокер показывает, но
     * торговать не даёт.
     */
    val instrumentStatus: String = "INSTRUMENT_STATUS_BASE",
)

@Serializable
data class InstrumentsResponse(val instruments: List<Instrument> = emptyList())

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

/**
 * Инструмент в списке наблюдения — хранится локально вместе с данными,
 * которые нужны расчётам: идентификатор актива для отчётности, размер лота
 * и ставка риска для расчёта плеча.
 */
@Serializable
data class WatchedInstrument(
    val figi: String,
    val ticker: String,
    val name: String,
    val uid: String = "",
    val assetUid: String = "",
    val lot: Int = 1,
    val currency: String = "rub",
    /** Ставка риска лонг, доля: 0.2 означает максимальное плечо 5x. */
    val riskRateLong: Double = 0.0,
    val shortEnabled: Boolean = false,
    val instrumentType: String = "",
)
