package com.tinvesttrader.data.crypto

import com.tinvesttrader.data.Candle
import com.tinvesttrader.data.Quotation
import java.math.BigDecimal
import java.math.RoundingMode

/** Параметры спотовой пары, от которых зависит, какую заявку биржа примет. */
data class SpotInstrument(
    val symbol: String,
    val baseCoin: String,
    val quoteCoin: String,
    /** Шаг количества базовой монеты, например 0.000001 BTC. */
    val basePrecision: BigDecimal,
    /** Минимальное количество базовой монеты в заявке. */
    val minOrderQty: BigDecimal,
    /** Минимальная сумма заявки в валюте котировки. */
    val minOrderAmt: BigDecimal,
)

/**
 * Спотовая криптобиржа. Интерфейс нужен, чтобы торговый цикл проверялся
 * тестами без сети: тест подставляет свою биржу с заранее заданными ответами.
 */
interface CryptoExchange {
    /** "LIVE" — настоящие деньги, "TESTNET" — тестовая сеть биржи. */
    val modeLabel: String

    /** Только закрытые свечи, от старой к новой. Текущая незакрытая отбрасывается. */
    suspend fun closedCandles(symbol: String, interval: String, limit: Int): List<Candle>

    suspend fun instrument(symbol: String): SpotInstrument

    /** Доступный остаток (без заблокированного в заявках) по каждой монете. */
    suspend fun availableBalances(coins: List<String>): Map<String, BigDecimal>

    /** Рыночная покупка на сумму в валюте котировки. Возвращает номер заявки. */
    suspend fun marketBuy(symbol: String, quoteAmount: BigDecimal): String

    /** Рыночная продажа количества базовой монеты. Возвращает номер заявки. */
    suspend fun marketSell(symbol: String, baseQty: BigDecimal): String
}

/** Количество, округлённое вниз до шага биржи: округление вверх продало бы больше, чем есть. */
fun floorToStep(value: BigDecimal, step: BigDecimal): BigDecimal {
    if (step.signum() <= 0) return value
    val steps = value.divide(step, 0, RoundingMode.FLOOR)
    return steps.multiply(step).setScale(step.stripTrailingZeros().scale().coerceAtLeast(0), RoundingMode.FLOOR)
}

/** Цена из строки биржи в формат свечей приложения (целая часть и нано-доли). */
fun quotationOf(value: String): Quotation {
    val number = BigDecimal(value)
    val units = number.setScale(0, RoundingMode.FLOOR)
    val nano = number.subtract(units).movePointRight(9).setScale(0, RoundingMode.HALF_UP).toInt()
        .coerceIn(0, 999_999_999)
    return Quotation(units = units.toPlainString(), nano = nano)
}
