package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import kotlin.math.sqrt

/**
 * Возврат к среднему: покупка, когда цена закрылась ниже нижней полосы
 * Боллинджера, выход — когда она вернулась к средней.
 *
 * Это противоположность пробою канала. Пробой ставит на продолжение движения
 * и выигрывает редко, но крупно; возврат ставит на его затухание и выигрывает
 * часто, но помалу. На дневных свечах первый подход обошёл второй по итогу
 * во всех прогонах, но внутри дня движение чаще затухает, чем продолжается —
 * это и проверяется.
 *
 * Стоп здесь обязателен и должен быть дальше полосы: вход на отклонении в две
 * сигмы — это вход в момент, когда рынок уже решил, что «дёшево» ещё не
 * значит «дно».
 */
class MeanReversionStrategy(
    private val period: Int = 20,
    private val bandWidth: Double = 2.0,
) : Strategy {

    init {
        require(period > 2) { "Период полосы должен быть больше двух свечей" }
    }

    override fun evaluate(candles: List<Candle>, barsToScan: Int): StrategyDecision {
        val needed = period + 2
        if (candles.size < needed) {
            return StrategyDecision(
                signal = Signal.HOLD,
                reasoning = listOf(
                    "Получено свечей: ${candles.size}, нужно минимум $needed для полосы $period.",
                    "Данных недостаточно — решение отложено.",
                ),
                indicators = null,
            )
        }

        val closes = candles.map { it.close.toDouble() }
        val last = closes.size - 1
        val lastPrice = closes[last]
        val middle = mean(closes, last)
        val lower = middle - bandWidth * deviation(closes, last, middle)

        val snapshot = IndicatorSnapshot(
            lastPrice = lastPrice,
            fastPeriod = period,
            slowPeriod = period,
            fastSmaPrevious = lower,
            slowSmaPrevious = middle,
            fastSmaCurrent = lower,
            slowSmaCurrent = middle,
            candlesAnalyzed = candles.size,
        )
        val reasoning = mutableListOf(
            "Проанализировано свечей: ${candles.size}, последняя цена ${fmt(lastPrice)}.",
            "Средняя за $period свечей: ${fmt(middle)}, нижняя полоса: ${fmt(lower)}.",
        )

        // Выход первым: цена вернулась к средней — цель достигнута.
        if (lastPrice >= middle) {
            reasoning += "Цена на средней или выше — движение отыграно."
            reasoning += "Сигнал: ПРОДАЖА."
            return StrategyDecision(Signal.SELL, reasoning, snapshot)
        }

        val scan = barsToScan.coerceIn(1, closes.size - period)
        val dip = (0 until scan).firstOrNull { offset ->
            val index = last - offset
            val m = mean(closes, index)
            closes[index] < m - bandWidth * deviation(closes, index, m)
        }
        // Отклонение засчитывается, пока цена ещё ниже средней: покупать
        // после того, как она вернулась, уже не за чем.
        if (dip != null) {
            reasoning += "Цена закрылась ниже нижней полосы" +
                if (dip > 0) " $dip свеч(и) назад и ещё не вернулась к средней." else " на последней свече."
            reasoning += "Сигнал: ПОКУПКА."
            return StrategyDecision(Signal.BUY, reasoning, snapshot, barsSinceSignal = dip)
        }

        reasoning += "Цена между нижней полосой и средней — ждём отклонения."
        reasoning += "Сигнал: ЖДЁМ."
        return StrategyDecision(Signal.HOLD, reasoning, snapshot)
    }

    /** Среднее [period] закрытий, заканчивая указанным индексом включительно. */
    private fun mean(closes: List<Double>, index: Int): Double =
        closes.subList(index - period + 1, index + 1).average()

    private fun deviation(closes: List<Double>, index: Int, mean: Double): Double {
        val window = closes.subList(index - period + 1, index + 1)
        return sqrt(window.sumOf { (it - mean) * (it - mean) } / window.size)
    }
}
