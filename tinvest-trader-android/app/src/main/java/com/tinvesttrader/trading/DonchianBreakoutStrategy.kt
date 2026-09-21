package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle

/**
 * Пробой канала Дончиана: покупка, когда цена закрылась выше максимума за
 * последние [entryPeriod] свечей, выход — когда она уходит ниже минимума за
 * [exitPeriod].
 *
 * Это прямое лекарство от того, на чём провалилось пересечение средних.
 * Средние подтверждают движение задним числом: к моменту пересечения большая
 * часть хода уже позади, а фильтры отказывались пускать в сделку именно тогда,
 * когда движение только начиналось. Пробой максимума — событие начала хода:
 * система входит на новом максимуме, то есть там, где тренд ещё впереди.
 *
 * Фильтров входа здесь нет намеренно: прошлый эксперимент показал, что они
 * стоят дороже, чем сэкономленный ими риск. Риск ограничивают стоп и размер
 * позиции, а не отказ от входа.
 *
 * Выход короче входа (10 против 20) сознательно: из позиции нужно выходить
 * быстрее, чем входить, иначе отдаёшь рынку слишком много накопленной прибыли.
 */
class DonchianBreakoutStrategy(
    private val entryPeriod: Int = 20,
    private val exitPeriod: Int = 10,
) : Strategy {

    init {
        require(entryPeriod > 1 && exitPeriod > 1) { "Периоды канала должны быть больше одной свечи" }
    }

    override fun evaluate(candles: List<Candle>, barsToScan: Int): StrategyDecision {
        val needed = entryPeriod + 2
        if (candles.size < needed) {
            return StrategyDecision(
                signal = Signal.HOLD,
                reasoning = listOf(
                    "Получено свечей: ${candles.size}, нужно минимум $needed для канала $entryPeriod.",
                    "Данных недостаточно — решение отложено.",
                ),
                indicators = null,
            )
        }

        val lastPrice = candles.last().close.toDouble()
        val scan = barsToScan.coerceIn(1, candles.size - entryPeriod - 1)
        val entryLevel = channelHigh(candles, candles.size - 1)
        val exitLevel = channelLow(candles, candles.size - 1)

        val snapshot = IndicatorSnapshot(
            lastPrice = lastPrice,
            fastPeriod = exitPeriod,
            slowPeriod = entryPeriod,
            fastSmaPrevious = exitLevel,
            slowSmaPrevious = entryLevel,
            fastSmaCurrent = exitLevel,
            slowSmaCurrent = entryLevel,
            candlesAnalyzed = candles.size,
        )

        val reasoning = mutableListOf(
            "Проанализировано свечей: ${candles.size}, последняя цена ${fmt(lastPrice)}.",
            "Верхняя граница канала за $entryPeriod свечей: ${fmt(entryLevel)}.",
            "Нижняя граница канала за $exitPeriod свечей: ${fmt(exitLevel)}.",
        )
        if (scan > 1) {
            reasoning += "Проверено $scan последних свечей — столько бот мог пропустить."
        }

        // Выход проверяется первым: не выпустить из позиции дороже, чем не впустить.
        val breakdown = (0 until scan).firstOrNull { offset ->
            val index = candles.size - 1 - offset
            index > exitPeriod && candles[index].close.toDouble() < channelLow(candles, index)
        }
        if (breakdown != null) {
            reasoning += "Цена ушла ниже нижней границы канала" +
                if (breakdown > 0) " $breakdown свеч(и) назад." else " на последней свече."
            reasoning += "Сигнал: ПРОДАЖА."
            return StrategyDecision(Signal.SELL, reasoning, snapshot)
        }

        val breakout = (0 until scan).firstOrNull { offset ->
            val index = candles.size - 1 - offset
            index > entryPeriod && candles[index].close.toDouble() > channelHigh(candles, index)
        }
        // Пробой засчитывается, только если цена и сейчас держится выше уровня:
        // вход в уже отменившийся пробой — это покупка на откате вниз.
        if (breakout != null && lastPrice > channelHigh(candles, candles.size - 1 - breakout)) {
            reasoning += "Цена пробила максимум канала" +
                if (breakout > 0) " $breakout свеч(и) назад и держится выше." else " на последней свече."
            reasoning += "Сигнал: ПОКУПКА."
            return StrategyDecision(
                signal = Signal.BUY,
                reasoning = reasoning,
                indicators = snapshot,
                barsSinceSignal = breakout,
            )
        }

        reasoning += "Цена внутри канала — ни пробоя вверх, ни ухода вниз."
        reasoning += "Сигнал: ЖДЁМ."
        return StrategyDecision(Signal.HOLD, reasoning, snapshot)
    }

    /** Максимум [entryPeriod] свечей ДО указанной: сама свеча в границу не входит. */
    private fun channelHigh(candles: List<Candle>, index: Int): Double =
        candles.subList(index - entryPeriod, index).maxOf { it.high.toDouble() }

    private fun channelLow(candles: List<Candle>, index: Int): Double =
        candles.subList(index - exitPeriod, index).minOf { it.low.toDouble() }
}
