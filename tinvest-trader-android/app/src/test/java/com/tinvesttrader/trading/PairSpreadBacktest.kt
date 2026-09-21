package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class PairSettings(
    /** Окно, по которому считаются среднее и разброс отношения цен. */
    val window: Int = 60,
    /** Отклонение в сигмах, при котором открывается спред. */
    val entryZ: Double = 2.0,
    /** Отклонение, при котором спред считается вернувшимся. */
    val exitZ: Double = 0.5,
    /** Отклонение, при котором признаётся, что связь сломалась. */
    val stopZ: Double = 4.0,
    val maxHoldDays: Int = 40,
    val commissionPercent: Double = 0.05,
    val spreadPercent: Double = 0.05,
    /** Плата за короткую позицию, годовых от её объёма. */
    val shortRatePercent: Double = 20.0,
)

data class PairResult(
    val pair: String,
    val days: Int,
    val trades: Int,
    val winRatePercent: Double,
    val expectancyPercent: Double,
    val totalReturnPercent: Double,
    val maxDrawdownPercent: Double,
    val averageHoldDays: Double,
    val stops: Int,
    val timeExits: Int,
    val shortCostPercent: Double,
)

/**
 * Спред двух связанных бумаг: ставка не на направление, а на то, что
 * отношение их цен вернётся к привычному.
 *
 * Это другой источник края, чем всё проверенное до сих пор. Пробой канала
 * зарабатывает на редких крупных движениях и проигрывает пассиву на растущем
 * рынке. Спред от направления рынка не зависит вовсе: одна нога длинная,
 * другая короткая, и общий рост или падение сокращаются. Зарабатывает он на
 * том, что обыкновенная и привилегированная акция одного эмитента не могут
 * разъезжаться бесконечно.
 *
 * Дисциплина та же: решение по закрытию дня, исполнение по следующему
 * открытию обеих ног, комиссия и спред на каждой ноге в обе стороны, плата за
 * короткую позицию за каждый день удержания. Капитал делится поровну между
 * ногами.
 */
object PairSpreadBacktest {

    private class Open(
        val entryIndex: Int,
        /** +1: длинная A и короткая B (отношение ниже нормы), -1: наоборот. */
        val direction: Int,
        val aEntry: Double,
        val bEntry: Double,
    )

    fun run(pair: String, a: List<Candle>, b: List<Candle>, settings: PairSettings): PairResult? {
        val byDayA = a.associateBy { it.time.take(10) }
        val byDayB = b.associateBy { it.time.take(10) }
        val days = byDayA.keys.intersect(byDayB.keys).sorted()
        if (days.size < settings.window + 60) return null

        val closeA = days.map { byDayA.getValue(it).close.toDouble() }
        val closeB = days.map { byDayB.getValue(it).close.toDouble() }
        val openA = days.map { byDayA.getValue(it).open.toDouble() }
        val openB = days.map { byDayB.getValue(it).open.toDouble() }
        val ratio = days.indices.map { ln(closeA[it] / closeB[it]) }

        // Две ноги по половине капитала: в обе стороны каждой ноги.
        val costHalf = (settings.commissionPercent + settings.spreadPercent) / 100.0
        val roundTripCost = 4 * 0.5 * costHalf
        val shortDaily = settings.shortRatePercent / 100.0 / 365.0 * 0.5

        var cash = 1.0
        var open: Open? = null
        var pendingEntry = 0
        var pendingExit = false
        val results = mutableListOf<Double>()
        var peak = 1.0
        var drawdown = 0.0
        var holdSum = 0
        var stops = 0
        var timeExits = 0
        var shortCost = 0.0
        var pendingReason = ""

        fun pnl(position: Open, aPrice: Double, bPrice: Double): Double {
            val aMove = aPrice / position.aEntry - 1
            val bMove = bPrice / position.bEntry - 1
            return 0.5 * position.direction * (aMove - bMove)
        }

        for (index in settings.window + 1 until days.size) {
            // 1. Исполняем вчерашние решения по сегодняшнему открытию.
            open?.let { position ->
                if (pendingExit) {
                    val result = pnl(position, openA[index], openB[index]) - roundTripCost
                    cash += result
                    results += result * 100
                    holdSum += index - position.entryIndex
                    if (pendingReason == "stop") stops++
                    if (pendingReason == "time") timeExits++
                    open = null
                    pendingExit = false
                }
            }
            if (open == null && pendingEntry != 0) {
                open = Open(index, pendingEntry, openA[index], openB[index])
                pendingEntry = 0
            }

            // 2. Плата за короткую ногу за прошедший день.
            if (open != null) {
                cash -= shortDaily
                shortCost += shortDaily
            }

            // 3. Решение по сегодняшнему закрытию.
            val history = ratio.subList(index - settings.window, index)
            val mean = history.average()
            val sd = sqrt(history.sumOf { (it - mean) * (it - mean) } / history.size)
            val z = if (sd > 0) (ratio[index] - mean) / sd else 0.0

            val position = open
            if (position == null) {
                if (z > settings.entryZ) pendingEntry = -1
                if (z < -settings.entryZ) pendingEntry = +1
            } else {
                val returned = position.direction * z > -settings.exitZ
                val broken = abs(z) > settings.stopZ
                val expired = index - position.entryIndex >= settings.maxHoldDays
                when {
                    broken -> { pendingExit = true; pendingReason = "stop" }
                    returned -> { pendingExit = true; pendingReason = "return" }
                    expired -> { pendingExit = true; pendingReason = "time" }
                }
            }

            // 4. Капитал с учётом открытого спреда.
            val equity = cash + (open?.let { pnl(it, closeA[index], closeB[index]) } ?: 0.0)
            peak = max(peak, equity)
            drawdown = max(drawdown, (peak - equity) / peak * 100)
        }

        open?.let { position ->
            val last = days.size - 1
            val result = pnl(position, closeA[last], closeB[last]) - roundTripCost
            cash += result
            results += result * 100
            holdSum += last - position.entryIndex
        }

        return PairResult(
            pair = pair,
            days = days.size,
            trades = results.size,
            winRatePercent = if (results.isEmpty()) 0.0 else results.count { it > 0 } * 100.0 / results.size,
            expectancyPercent = if (results.isEmpty()) 0.0 else results.average(),
            totalReturnPercent = (cash - 1) * 100,
            maxDrawdownPercent = drawdown,
            averageHoldDays = if (results.isEmpty()) 0.0 else holdSum.toDouble() / results.size,
            stops = stops,
            timeExits = timeExits,
            shortCostPercent = shortCost * 100,
        )
    }
}
