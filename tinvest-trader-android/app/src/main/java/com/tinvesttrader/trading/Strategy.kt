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
    /** Время свечи, на которой произошло пересечение, если оно найдено. */
    val signalCandleTime: String? = null,
    /** Сколько свечей назад случилось пересечение: 0 — на последней. */
    val barsSinceSignal: Int = 0,
    /**
     * Уверенность в сигнале, доля от полного размера позиции (0..1).
     * 1.0 — брать полный объём. Меньше — брать меньше, но всё равно войти:
     * так слабый сигнал снижает риск, а не отменяет сделку целиком.
     */
    val conviction: Double = 1.0,
)

interface Strategy {
    /**
     * Свечи должны идти в хронологическом порядке (старая -> новая).
     * [barsToScan] — сколько последних свечей проверять на пересечение:
     * бот просыпается по расписанию и между проверками пропускает свечи,
     * поэтому смотреть только на последнюю нельзя.
     */
    fun evaluate(candles: List<Candle>, barsToScan: Int = 1): StrategyDecision
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

    override fun evaluate(candles: List<Candle>, barsToScan: Int): StrategyDecision {
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

        // Ищем пересечение не только на последней свече, но и на всех, что
        // бот мог проспать между проверками. Раньше сигнал, случившийся в
        // пропущенный интервал, терялся навсегда: следующая проверка видела
        // уже установившийся тренд и считала, что входить поздно.
        val scan = findCrossover(candles, closes, barsToScan.coerceAtLeast(1))
        val crossedUp = scan?.up == true
        val crossedDown = scan?.up == false

        val reasoning = mutableListOf(
            "Проанализировано свечей: ${snapshot.candlesAnalyzed}, последняя цена ${fmt(snapshot.lastPrice)}.",
            "SMA($fastPeriod) на прошлом шаге ${fmt(snapshot.fastSmaPrevious)} -> сейчас ${fmt(snapshot.fastSmaCurrent)}.",
            "SMA($slowPeriod) на прошлом шаге ${fmt(snapshot.slowSmaPrevious)} -> сейчас ${fmt(snapshot.slowSmaCurrent)}.",
            "Быстрая SMA ${if (snapshot.spreadPercent >= 0) "выше" else "ниже"} медленной на " +
                "${fmt(kotlin.math.abs(snapshot.spreadPercent))}%.",
        )

        if (barsToScan > 1) {
            reasoning += "Проверено на пересечения последних $barsToScan свечей — " +
                "столько бот мог пропустить с прошлой проверки."
        }
        scan?.let {
            reasoning += if (it.barsAgo == 0) {
                "Пересечение произошло на последней свече."
            } else {
                "Пересечение произошло ${it.barsAgo} свеч(и) назад (${it.time}) — " +
                    "в интервал, когда бот не смотрел на рынок."
            }
        }

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

        return StrategyDecision(
            signal = signal,
            reasoning = reasoning,
            indicators = snapshot,
            signalCandleTime = scan?.time,
            barsSinceSignal = scan?.barsAgo ?: 0,
        )
    }

    private data class Crossover(val up: Boolean, val barsAgo: Int, val time: String?)

    /**
     * Самое свежее пересечение в пределах окна. Если за окно их было
     * несколько, берётся последнее: именно оно описывает текущее состояние
     * рынка, а отыгрывать отменённый более старый сигнал смысла нет.
     */
    private fun findCrossover(
        candles: List<Candle>,
        closes: List<Double>,
        barsToScan: Int,
    ): Crossover? {
        val maxOffset = minOf(barsToScan, closes.size - slowPeriod - 1)
        for (offset in 0 until maxOffset) {
            val end = closes.size - offset
            if (end - slowPeriod - 1 < 0) break
            val fastNow = sma(closes, end, fastPeriod)
            val slowNow = sma(closes, end, slowPeriod)
            val fastBefore = sma(closes, end - 1, fastPeriod)
            val slowBefore = sma(closes, end - 1, slowPeriod)
            val time = candles.getOrNull(end - 1)?.time?.take(16)?.replace('T', ' ')
            if (fastBefore <= slowBefore && fastNow > slowNow) return Crossover(true, offset, time)
            if (fastBefore >= slowBefore && fastNow < slowNow) return Crossover(false, offset, time)
        }
        return null
    }

    /** Среднее по последним [period] значениям, заканчивающимся индексом [endExclusive]. */
    private fun sma(values: List<Double>, endExclusive: Int, period: Int): Double {
        val start = endExclusive - period
        return values.subList(start, endExclusive).average()
    }
}

internal fun fmt(value: Double): String = String.format("%.2f", value)
