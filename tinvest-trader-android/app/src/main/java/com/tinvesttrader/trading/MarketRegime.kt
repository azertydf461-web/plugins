package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle

/**
 * Состояние инструмента на конкретный день: годовое изменение его же цены.
 *
 * Разбор сделок по режимам показал, что пробой канала на растущем рынке даёт
 * ровно ноль — стоп выбивает на первом откате, а обратно система заходит по
 * новому пробою, то есть выше. Здесь и живёт всё отставание от простого
 * удержания. Отсюда правило: на растущем рынке не входить.
 *
 * Знание берётся строго из прошлого относительно проверяемого дня. Если
 * истории меньше окна, режим неизвестен и правило не вмешивается: отказывать
 * во входе из-за незнания значило бы выкинуть первый год каждого инструмента.
 */
object MarketRegime {

    /** Окно, по которому определяется режим: примерно торговый год. */
    const val WINDOW_BARS = 252

    /** Выше этого годового роста вход считается входом в растущий рынок. */
    const val RISING_THRESHOLD_PERCENT = 15.0

    /** Изменение цены за окно к бару [index], в процентах; null — истории мало. */
    fun changePercent(candles: List<Candle>, index: Int, window: Int = WINDOW_BARS): Double? {
        if (index < window || index >= candles.size) return null
        val past = candles[index - window].close.toDouble()
        if (past <= 0) return null
        return (candles[index].close.toDouble() / past - 1) * 100
    }

    fun isRising(
        candles: List<Candle>,
        index: Int,
        window: Int = WINDOW_BARS,
        thresholdPercent: Double = RISING_THRESHOLD_PERCENT,
    ): Boolean = (changePercent(candles, index, window) ?: return false) > thresholdPercent
}
