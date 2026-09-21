package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import kotlin.math.abs
import kotlin.math.max

/**
 * ADX — сила тренда безотносительно его направления. Нужен потому, что
 * пересечение средних само по себе не отличает начало движения от метания
 * цены в боковике: в боковике средние пересекаются постоянно, и каждое такое
 * пересечение оборачивается убытком на комиссии и спреде.
 */
object TrendStrength {

    /** Значение от 0 до 100. Ниже 20 — выраженного тренда нет. */
    fun adx(candles: List<Candle>, period: Int = 14): Double? {
        if (candles.size < period * 2 + 1) return null

        val plusDm = mutableListOf<Double>()
        val minusDm = mutableListOf<Double>()
        val trueRanges = mutableListOf<Double>()

        for (index in 1 until candles.size) {
            val high = candles[index].high.toDouble()
            val low = candles[index].low.toDouble()
            val previousHigh = candles[index - 1].high.toDouble()
            val previousLow = candles[index - 1].low.toDouble()
            val previousClose = candles[index - 1].close.toDouble()

            val upMove = high - previousHigh
            val downMove = previousLow - low
            plusDm += if (upMove > downMove && upMove > 0) upMove else 0.0
            minusDm += if (downMove > upMove && downMove > 0) downMove else 0.0
            trueRanges += max(high - low, max(abs(high - previousClose), abs(low - previousClose)))
        }

        val dx = mutableListOf<Double>()
        for (end in period..trueRanges.size) {
            val tr = trueRanges.subList(end - period, end).sum()
            if (tr <= 0) continue
            val plus = plusDm.subList(end - period, end).sum() / tr * 100
            val minus = minusDm.subList(end - period, end).sum() / tr * 100
            val sum = plus + minus
            if (sum > 0) dx += abs(plus - minus) / sum * 100
        }

        if (dx.size < period) return null
        return dx.takeLast(period).average()
    }

    /**
     * Наклон длинной средней в процентах за [lookback] свечей: показывает,
     * куда смотрит рынок в целом. Покупать против общего направления —
     * заведомо худшая ставка, чем по нему.
     */
    fun slopePercent(closes: List<Double>, period: Int = 50, lookback: Int = 10): Double? {
        if (closes.size < period + lookback) return null
        val now = closes.takeLast(period).average()
        val before = closes.subList(closes.size - period - lookback, closes.size - lookback).average()
        if (before <= 0) return null
        return (now - before) / before * 100
    }
}
