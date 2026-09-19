package com.tinvestanalyst.analysis

import com.tinvestanalyst.data.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class MacdResult(
    val macdLine: Double,
    val signalLine: Double,
    val histogram: Double,
    val previousHistogram: Double,
)

data class BollingerBands(
    val upper: Double,
    val middle: Double,
    val lower: Double,
) {
    /** Положение цены внутри полос: 0 — на нижней, 1 — на верхней. */
    fun percentB(price: Double): Double {
        val width = upper - lower
        return if (width <= 0) 0.5 else (price - lower) / width
    }
}

/**
 * Классические индикаторы теханализа. Реализованы вручную, а не взяты
 * библиотекой, чтобы каждое число в рекомендации можно было проследить до
 * формулы — приложение обязано объяснять свои выводы, а не выдавать их.
 */
object Indicators {

    fun sma(values: List<Double>, period: Int): Double? {
        if (period <= 0 || values.size < period) return null
        return values.takeLast(period).average()
    }

    /** Значение SMA для каждой точки; null там, где данных ещё не хватает. */
    fun smaSeries(values: List<Double>, period: Int): List<Double?> =
        values.indices.map { index ->
            if (index + 1 < period) null else values.subList(index + 1 - period, index + 1).average()
        }

    fun emaSeries(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty() || values.size < period) return emptyList()
        val multiplier = 2.0 / (period + 1)
        val result = mutableListOf<Double>()
        var previous = values.take(period).average()
        result += previous
        for (index in period until values.size) {
            previous = (values[index] - previous) * multiplier + previous
            result += previous
        }
        return result
    }

    /** RSI по Уайлдеру: сглаживание, а не простое среднее, иначе значения дёргаются. */
    fun rsi(values: List<Double>, period: Int = 14): Double? {
        if (values.size < period + 1) return null
        var avgGain = 0.0
        var avgLoss = 0.0
        for (index in 1..period) {
            val change = values[index] - values[index - 1]
            if (change >= 0) avgGain += change else avgLoss -= change
        }
        avgGain /= period
        avgLoss /= period
        for (index in period + 1 until values.size) {
            val change = values[index] - values[index - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) -change else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1 + rs)
    }

    fun macd(
        values: List<Double>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9,
    ): MacdResult? {
        if (values.size < slowPeriod + signalPeriod + 1) return null
        val fastEma = emaSeries(values, fastPeriod)
        val slowEma = emaSeries(values, slowPeriod)
        // Серии разной длины: выравниваем по хвосту, где обе определены.
        val aligned = minOf(fastEma.size, slowEma.size)
        val macdSeries = (0 until aligned).map { offset ->
            fastEma[fastEma.size - aligned + offset] - slowEma[slowEma.size - aligned + offset]
        }
        if (macdSeries.size < signalPeriod + 1) return null
        val signalSeries = emaSeries(macdSeries, signalPeriod)
        if (signalSeries.size < 2) return null

        val macdNow = macdSeries.last()
        val signalNow = signalSeries.last()
        val macdPrev = macdSeries[macdSeries.size - 2]
        val signalPrev = signalSeries[signalSeries.size - 2]
        return MacdResult(
            macdLine = macdNow,
            signalLine = signalNow,
            histogram = macdNow - signalNow,
            previousHistogram = macdPrev - signalPrev,
        )
    }

    fun bollinger(values: List<Double>, period: Int = 20, deviations: Double = 2.0): BollingerBands? {
        if (values.size < period) return null
        val window = values.takeLast(period)
        val mean = window.average()
        val variance = window.sumOf { (it - mean) * (it - mean) } / period
        val stdDev = sqrt(variance)
        return BollingerBands(
            upper = mean + deviations * stdDev,
            middle = mean,
            lower = mean - deviations * stdDev,
        )
    }

    /** Средний истинный диапазон — мера волатильности, база для стопа. */
    fun atr(candles: List<Candle>, period: Int = 14): Double? {
        if (candles.size < period + 1) return null
        val trueRanges = (1 until candles.size).map { index ->
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
        var atr = trueRanges.take(period).average()
        for (index in period until trueRanges.size) {
            atr = (atr * (period - 1) + trueRanges[index]) / period
        }
        return atr
    }

    /** Отношение последнего объёма к среднему за период: подтверждение движения. */
    fun volumeRatio(candles: List<Candle>, period: Int = 20): Double? {
        if (candles.size < period + 1) return null
        val average = candles.takeLast(period + 1).dropLast(1).map { it.volumeAsDouble }.average()
        if (average <= 0) return null
        return candles.last().volumeAsDouble / average
    }
}
