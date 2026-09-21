package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle

/** Результат одного фильтра: пропустил он сделку или нет и почему. */
private data class FilterCheck(val name: String, val passed: Boolean, val reading: String)

/** Что фильтры делают с сигналом: запрещают вход или уменьшают объём. */
enum class FilterMode { BLOCK, SIZE }

/**
 * Пересечение средних с фильтрами входа.
 *
 * Само по себе пересечение — слабый сигнал: в боковике средние пересекаются
 * постоянно, и каждое такое пересечение стоит комиссии и спреда, а движения,
 * которое их окупит, не происходит. Поэтому сигнал на покупку пропускается
 * только тогда, когда рынок вообще куда-то идёт, движение не выдохлось и
 * размах свечей окупает издержки.
 *
 * Выходы фильтрами НЕ ограничиваются никогда: фильтр может ошибочно не дать
 * войти — это упущенная прибыль, а ошибочно не дать выйти — это убыток,
 * который некому остановить.
 */
class TrendFollowingStrategy(
    private val base: SmaCrossoverStrategy = SmaCrossoverStrategy(),
    /**
     * BLOCK — непройденный фильтр отменяет покупку. SIZE — покупка
     * происходит всегда, но объём режется пропорционально числу пройденных
     * фильтров: трендовая система зарабатывает одной-двумя крупными
     * сделками, и фильтр, не пустивший в такую сделку, стоит дороже, чем
     * весь сэкономленный им риск.
     */
    private val mode: FilterMode = FilterMode.BLOCK,
    /** Минимальная доля объёма: даже на слабом сигнале сделка не исчезает. */
    private val minConviction: Double = 0.34,
    private val trendPeriod: Int = 50,
    private val adxThreshold: Double = 20.0,
    private val rsiOverbought: Double = 70.0,
    /** Минимальный размах свечи в процентах от цены: ниже него издержки съедают сделку. */
    private val minAtrPercent: Double = 0.25,
) : Strategy {

    override fun evaluate(candles: List<Candle>, barsToScan: Int): StrategyDecision {
        val decision = base.evaluate(candles, barsToScan)
        if (decision.signal != Signal.BUY) return decision

        val closes = candles.map { it.close.toDouble() }
        val lastPrice = closes.lastOrNull() ?: return decision
        val checks = buildChecks(candles, closes, lastPrice)
        val blocking = checks.filterNot { it.passed }

        val reasoning = decision.reasoning.toMutableList()
        reasoning += "Фильтры входа:"
        checks.forEach { check ->
            reasoning += "  ${if (check.passed) "прошёл" else "НЕ прошёл"} — ${check.name}: ${check.reading}"
        }

        if (blocking.isEmpty()) {
            reasoning += "Все фильтры пройдены — сигнал на покупку подтверждён, объём полный."
            return decision.copy(reasoning = reasoning)
        }

        if (mode == FilterMode.SIZE) {
            val passed = checks.size - blocking.size
            val conviction = maxOf(minConviction, passed.toDouble() / checks.size)
            reasoning += "Не пройдено фильтров: ${blocking.joinToString(", ") { it.name.lowercase() }}."
            reasoning += "Вход остаётся, но объёмом ${(conviction * 100).toInt()}% от полного: " +
                "слабый сигнал уменьшает ставку, а не отменяет её."
            return decision.copy(reasoning = reasoning, conviction = conviction)
        }

        reasoning += "Покупка отменена: ${blocking.joinToString(", ") { it.name.lowercase() }}."
        reasoning += "Сигнал: ЖДЁМ. Пересечение без тренда чаще всего оборачивается " +
            "входом в боковик, где издержки съедают результат."
        return decision.copy(signal = Signal.HOLD, reasoning = reasoning)
    }

    private fun buildChecks(
        candles: List<Candle>,
        closes: List<Double>,
        lastPrice: Double,
    ): List<FilterCheck> = buildList {
        val longSma = closes.takeLast(trendPeriod).takeIf { it.size >= trendPeriod }?.average()
        add(
            FilterCheck(
                name = "Цена выше длинной средней",
                // Неизвестный фильтр не должен запрещать сделку: иначе на
                // короткой истории бот не торговал бы вообще никогда.
                passed = longSma == null || lastPrice > longSma,
                reading = longSma?.let {
                    "цена ${fmt(lastPrice)} против SMA$trendPeriod ${fmt(it)}"
                } ?: "истории меньше $trendPeriod свечей, фильтр не применяется",
            ),
        )

        val slope = TrendStrength.slopePercent(closes, trendPeriod)
        add(
            FilterCheck(
                name = "Длинная средняя растёт",
                passed = slope == null || slope > 0,
                reading = slope?.let { "наклон ${fmt(it)}% за 10 свечей" }
                    ?: "истории не хватает, фильтр не применяется",
            ),
        )

        val adx = TrendStrength.adx(candles)
        add(
            FilterCheck(
                name = "Тренд достаточно сильный",
                passed = adx == null || adx >= adxThreshold,
                reading = adx?.let { "ADX ${fmt(it)} при пороге ${fmt(adxThreshold)}" }
                    ?: "ADX не рассчитан, фильтр не применяется",
            ),
        )

        val rsi = Momentum.rsi(closes)
        add(
            FilterCheck(
                name = "Рынок не перегрет",
                passed = rsi == null || rsi < rsiOverbought,
                reading = rsi?.let { "RSI ${fmt(it)} при пороге ${fmt(rsiOverbought)}" }
                    ?: "RSI не рассчитан, фильтр не применяется",
            ),
        )

        val atr = Volatility.atr(candles)
        val atrPercent = if (atr != null && lastPrice > 0) atr / lastPrice * 100 else null
        add(
            FilterCheck(
                name = "Размах свечей окупает издержки",
                passed = atrPercent == null || atrPercent >= minAtrPercent,
                reading = atrPercent?.let { "ATR ${fmt(it)}% от цены при минимуме ${fmt(minAtrPercent)}%" }
                    ?: "ATR не рассчитан, фильтр не применяется",
            ),
        )
    }
}
