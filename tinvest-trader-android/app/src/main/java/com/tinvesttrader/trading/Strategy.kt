package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle

enum class Signal { BUY, SELL, HOLD }

interface Strategy {
    /** Свечи должны идти в хронологическом порядке (старая -> новая). */
    fun evaluate(candles: List<Candle>): Signal
}

/**
 * Классический пересечение скользящих средних: быстрая SMA пересекает
 * медленную снизу вверх -> BUY, сверху вниз -> SELL. Простая и объяснимая
 * стратегия — сознательный выбор для прототипа: сложные модели (ML-сигналы,
 * стакан заявок) требуют данных и валидации, которых пока нет.
 */
class SmaCrossoverStrategy(
    private val fastPeriod: Int = 9,
    private val slowPeriod: Int = 21,
) : Strategy {

    init {
        require(fastPeriod > 0 && slowPeriod > fastPeriod) {
            "fastPeriod должен быть > 0 и меньше slowPeriod"
        }
    }

    override fun evaluate(candles: List<Candle>): Signal {
        if (candles.size < slowPeriod + 1) return Signal.HOLD

        val closes = candles.map { it.close.toDouble() }

        val fastPrev = sma(closes, closes.size - 1, fastPeriod)
        val slowPrev = sma(closes, closes.size - 1, slowPeriod)
        val fastNow = sma(closes, closes.size, fastPeriod)
        val slowNow = sma(closes, closes.size, slowPeriod)

        val crossedUp = fastPrev <= slowPrev && fastNow > slowNow
        val crossedDown = fastPrev >= slowPrev && fastNow < slowNow

        return when {
            crossedUp -> Signal.BUY
            crossedDown -> Signal.SELL
            else -> Signal.HOLD
        }
    }

    /** Среднее по последним [period] значениям, заканчивающимся индексом [endExclusive]. */
    private fun sma(values: List<Double>, endExclusive: Int, period: Int): Double {
        val start = endExclusive - period
        return values.subList(start, endExclusive).average()
    }
}
