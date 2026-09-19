package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle

enum class Signal { BUY, SELL, HOLD }

/**
 * Значения индикаторов ровно в тот момент, когда принималось решение —
 * без них запись в журнале нельзя перепроверить задним числом.
 */
data class IndicatorSnapshot(
    val lastPrice: Double,
    val fastPeriod: Int,
    val slowPeriod: Int,
    val fastSmaPrevious: Double,
    val slowSmaPrevious: Double,
    val fastSmaCurrent: Double,
    val slowSmaCurrent: Double,
    val candlesAnalyzed: Int,
) {
    /** Насколько быстрая SMA выше (+) или ниже (-) медленной, в процентах. */
    val spreadPercent: Double
        get() = if (slowSmaCurrent == 0.0) 0.0 else (fastSmaCurrent - slowSmaCurrent) / slowSmaCurrent * 100.0
}

data class StrategyDecision(
    val signal: Signal,
    /** Пошаговое обоснование: что посчитали и почему вышел такой сигнал. */
    val reasoning: List<String>,
    val indicators: IndicatorSnapshot?,
)

interface Strategy {
    /** Свечи должны идти в хронологическом порядке (старая -> новая). */
    fun evaluate(candles: List<Candle>): StrategyDecision
}

/**
 * Пересечение скользящих средних: быстрая SMA пересекает медленную снизу
 * вверх -> BUY, сверху вниз -> SELL. Простая и объяснимая стратегия —
 * сознательный выбор: сложные модели (ML-сигналы, стакан заявок) требуют
 * данных и валидации, которых пока нет, и их решения нельзя показать
 * пользователю одной строкой.
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

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        if (candles.size < slowPeriod + 1) {
            return StrategyDecision(
                signal = Signal.HOLD,
                reasoning = listOf(
                    "Получено свечей: ${candles.size}, нужно минимум ${slowPeriod + 1} " +
                        "для расчёта SMA($slowPeriod).",
                    "Данных недостаточно — решение отложено до следующей проверки.",
                ),
                indicators = null,
            )
        }

        val closes = candles.map { it.close.toDouble() }
        val snapshot = IndicatorSnapshot(
            lastPrice = closes.last(),
            fastPeriod = fastPeriod,
            slowPeriod = slowPeriod,
            fastSmaPrevious = sma(closes, closes.size - 1, fastPeriod),
            slowSmaPrevious = sma(closes, closes.size - 1, slowPeriod),
            fastSmaCurrent = sma(closes, closes.size, fastPeriod),
            slowSmaCurrent = sma(closes, closes.size, slowPeriod),
            candlesAnalyzed = candles.size,
        )

        val crossedUp = snapshot.fastSmaPrevious <= snapshot.slowSmaPrevious &&
            snapshot.fastSmaCurrent > snapshot.slowSmaCurrent
        val crossedDown = snapshot.fastSmaPrevious >= snapshot.slowSmaPrevious &&
            snapshot.fastSmaCurrent < snapshot.slowSmaCurrent

        val reasoning = mutableListOf(
            "Проанализировано свечей: ${snapshot.candlesAnalyzed}, последняя цена ${fmt(snapshot.lastPrice)}.",
            "SMA($fastPeriod) на прошлом шаге ${fmt(snapshot.fastSmaPrevious)} -> сейчас ${fmt(snapshot.fastSmaCurrent)}.",
            "SMA($slowPeriod) на прошлом шаге ${fmt(snapshot.slowSmaPrevious)} -> сейчас ${fmt(snapshot.slowSmaCurrent)}.",
            "Быстрая SMA ${if (snapshot.spreadPercent >= 0) "выше" else "ниже"} медленной на " +
                "${fmt(kotlin.math.abs(snapshot.spreadPercent))}%.",
        )

        val signal = when {
            crossedUp -> {
                reasoning += "Зафиксировано пересечение снизу вверх — признак разворота тренда вверх."
                reasoning += "Сигнал: ПОКУПКА."
                Signal.BUY
            }
            crossedDown -> {
                reasoning += "Зафиксировано пересечение сверху вниз — признак разворота тренда вниз."
                reasoning += "Сигнал: ПРОДАЖА."
                Signal.SELL
            }
            else -> {
                val trend = if (snapshot.fastSmaCurrent > snapshot.slowSmaCurrent) "восходящий" else "нисходящий"
                reasoning += "Пересечения на этом шаге нет, тренд прежний ($trend) — точки входа нет."
                reasoning += "Сигнал: ЖДЁМ."
                Signal.HOLD
            }
        }

        return StrategyDecision(signal, reasoning, snapshot)
    }

    /** Среднее по последним [period] значениям, заканчивающимся индексом [endExclusive]. */
    private fun sma(values: List<Double>, endExclusive: Int, period: Int): Double {
        val start = endExclusive - period
        return values.subList(start, endExclusive).average()
    }
}

internal fun fmt(value: Double): String = String.format("%.2f", value)
