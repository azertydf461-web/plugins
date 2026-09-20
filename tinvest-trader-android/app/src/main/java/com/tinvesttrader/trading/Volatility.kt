package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import kotlin.math.abs
import kotlin.math.max

/**
 * ATR — средний размах свечи. Нужен, чтобы стоп ставился по волатильности
 * бумаги, а не по одинаковому для всех проценту: три процента для спокойной
 * облигации — это далеко, а для второго эшелона — обычный дневной шум,
 * который выбьет позицию в первый же час.
 */
object Volatility {

    fun atr(candles: List<Candle>, period: Int = 14): Double? {
        if (candles.size < period + 1) return null
        val ranges = (1 until candles.size).map { index ->
            val current = candles[index]
            val previousClose = candles[index - 1].close.toDouble()
            max(
                current.high.toDouble() - current.low.toDouble(),
                max(
                    abs(current.high.toDouble() - previousClose),
                    abs(current.low.toDouble() - previousClose),
                ),
            )
        }
        return ranges.takeLast(period).average().takeIf { it > 0 }
    }
}
