package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import kotlin.math.max
import kotlin.math.min

data class PortfolioSettings(
    /** Сколько позиций держим одновременно. Это и есть степень диверсификации. */
    val maxPositions: Int = 10,
    val commissionPercent: Double = 0.05,
    val spreadPercent: Double = 0.05,
    val atrMultiplier: Double = 2.0,
    val maxStopPercent: Double = 8.0,
    val trailingStop: Boolean = true,
    /** Сколько собственных свечей нужно инструменту, прежде чем он даёт сигналы. */
    val warmupBars: Int = 60,
)

data class PortfolioResult(
    val maxPositions: Int,
    val instruments: Int,
    val days: Int,
    val trades: Int,
    val winRatePercent: Double,
    val expectancyPercent: Double,
    val totalReturnPercent: Double,
    val maxDrawdownPercent: Double,
    /** Средняя доля капитала в рынке: показывает, сколько времени деньги работали. */
    val averageExposurePercent: Double,
    val buyHoldReturnPercent: Double,
    val buyHoldMaxDrawdownPercent: Double,
)

/**
 * Портфельный прогон: тот же сигнал пробоя канала, но капитал распределяется
 * между инструментами, а не ставится на один.
 *
 * Одиночные прогоны показали край в долях процента на сделку при разбросе от
 * минус сорока до плюс шестидесяти процентов по итогу. Такой разброс и есть
 * причина, по которой край не превращается в систему: на одном инструменте
 * результат определяет случай. Портфель — прямая проверка того, собирается ли
 * из тех же сделок работающее целое.
 *
 * Считается честно по дням: заявка, увиденная на свече, исполняется по
 * открытию следующей, стоп срабатывает внутри дня по своей цене, комиссия и
 * спред вычитаются с обеих сторон, а кривая капитала ведётся ежедневно — в
 * том числе для «купить и держать», у которого в прошлых прогонах просадка
 * вообще не измерялась.
 */
object PortfolioBacktest {

    private class Position(
        val entryPrice: Double,
        val quantity: Double,
        var stopPrice: Double,
        val atrAtEntry: Double,
        var highWater: Double,
    )

    private class Series(val candles: List<Candle>, val indexByDate: Map<String, Int>)

    fun run(
        data: Map<String, List<Candle>>,
        settings: PortfolioSettings,
        strategy: Strategy = DonchianBreakoutStrategy(),
    ): PortfolioResult? {
        if (data.isEmpty()) return null

        val series = data.mapValues { (_, candles) ->
            Series(candles, candles.withIndex().associate { (i, c) -> day(c.time) to i })
        }
        val dates = series.values.flatMap { it.indexByDate.keys }.distinct().sorted()
        if (dates.size < settings.warmupBars + 50) return null

        val costHalf = (settings.commissionPercent + settings.spreadPercent) / 100.0
        var cash = 1.0
        val positions = linkedMapOf<String, Position>()
        val pendingEntry = linkedSetOf<String>()
        val pendingExit = linkedSetOf<String>()
        val results = mutableListOf<Double>()
        val lastClose = mutableMapOf<String, Double>()

        var peak = 1.0
        var drawdown = 0.0
        var exposureSum = 0.0
        var exposureDays = 0

        dates.forEachIndexed { dayIndex, date ->
            // 1. Исполняем заявки, выставленные накануне, по открытию.
            pendingExit.toList().forEach { name ->
                val candle = candleOn(series, name, date) ?: return@forEach
                val position = positions.remove(name) ?: run { pendingExit.remove(name); return@forEach }
                cash += position.quantity * candle.open.toDouble() * (1 - costHalf)
                results += resultOf(position, candle.open.toDouble(), costHalf)
                pendingExit.remove(name)
            }
            pendingEntry.toList().forEach { name ->
                val candle = candleOn(series, name, date) ?: return@forEach
                pendingEntry.remove(name)
                if (positions.size >= settings.maxPositions) return@forEach
                val price = candle.open.toDouble()
                if (price <= 0) return@forEach
                val slot = min(cash, equity(cash, positions, lastClose) / settings.maxPositions)
                if (slot <= 0.001) return@forEach
                val quantity = slot * (1 - costHalf) / price
                val window = windowUpTo(series, name, date) ?: return@forEach
                val atr = Volatility.atr(window) ?: 0.0
                cash -= slot
                positions[name] = Position(
                    entryPrice = price,
                    quantity = quantity,
                    stopPrice = stopFor(price, atr, settings),
                    atrAtEntry = atr,
                    highWater = price,
                )
            }

            // 2. Стоп-заявки живут у брокера и срабатывают внутри дня.
            positions.toList().forEach { (name, position) ->
                val candle = candleOn(series, name, date) ?: return@forEach
                position.highWater = max(position.highWater, candle.high.toDouble())
                if (candle.low.toDouble() <= position.stopPrice) {
                    val fill = min(position.stopPrice, candle.open.toDouble())
                    positions.remove(name)
                    pendingExit.remove(name)
                    cash += position.quantity * fill * (1 - costHalf)
                    results += resultOf(position, fill, costHalf)
                }
            }

            // 3. Решения по сегодняшним свечам — исполнятся завтра.
            series.keys.forEach { name ->
                val window = windowUpTo(series, name, date) ?: return@forEach
                if (window.size < settings.warmupBars) return@forEach
                val decision = strategy.evaluate(window, 1)
                val held = positions.containsKey(name)
                when {
                    held && decision.signal == Signal.SELL -> pendingExit += name
                    !held && decision.signal == Signal.BUY &&
                        positions.size + pendingEntry.size < settings.maxPositions -> pendingEntry += name
                    else -> Unit
                }
            }

            // 4. Подтягиваем стопы и пересчитываем капитал.
            positions.forEach { (name, position) ->
                candleOn(series, name, date)?.let { lastClose[name] = it.close.toDouble() }
                if (settings.trailingStop && position.atrAtEntry > 0) {
                    position.stopPrice = max(
                        position.stopPrice,
                        position.highWater - settings.atrMultiplier * position.atrAtEntry,
                    )
                }
            }
            series.keys.forEach { name ->
                candleOn(series, name, date)?.let { lastClose[name] = it.close.toDouble() }
            }

            val equity = equity(cash, positions, lastClose)
            peak = max(peak, equity)
            drawdown = max(drawdown, (peak - equity) / peak * 100)
            if (dayIndex >= settings.warmupBars) {
                exposureSum += (equity - cash) / equity * 100
                exposureDays++
            }
        }

        val finalEquity = equity(cash, positions, lastClose)
        val hold = buyAndHold(series, dates, settings)

        return PortfolioResult(
            maxPositions = settings.maxPositions,
            instruments = series.size,
            days = dates.size,
            trades = results.size,
            winRatePercent = if (results.isEmpty()) 0.0 else results.count { it > 0 } * 100.0 / results.size,
            expectancyPercent = if (results.isEmpty()) 0.0 else results.average(),
            totalReturnPercent = (finalEquity - 1) * 100,
            maxDrawdownPercent = drawdown,
            averageExposurePercent = if (exposureDays == 0) 0.0 else exposureSum / exposureDays,
            buyHoldReturnPercent = hold.first,
            buyHoldMaxDrawdownPercent = hold.second,
        )
    }

    /**
     * Равновзвешенное «купить и держать» по той же корзине. Считается той же
     * ежедневной кривой, чтобы просадку пассива можно было сравнивать с
     * просадкой системы, а не принимать на веру.
     */
    private fun buyAndHold(
        series: Map<String, Series>,
        dates: List<String>,
        settings: PortfolioSettings,
    ): Pair<Double, Double> {
        val startDate = dates[settings.warmupBars]
        val quantities = mutableMapOf<String, Double>()
        val lastClose = mutableMapOf<String, Double>()
        val share = 1.0 / series.size

        series.forEach { (name, s) ->
            val index = s.indexByDate[startDate] ?: s.indexByDate.entries
                .filter { it.key >= startDate }
                .minByOrNull { it.key }?.value
            if (index != null) {
                val price = s.candles[index].close.toDouble()
                if (price > 0) {
                    quantities[name] = share / price
                    lastClose[name] = price
                }
            }
        }

        var peak = 1.0
        var drawdown = 0.0
        var equity = 1.0
        dates.drop(settings.warmupBars).forEach { date ->
            series.forEach { (name, _) ->
                candleOn(series, name, date)?.let { lastClose[name] = it.close.toDouble() }
            }
            equity = quantities.entries.sumOf { (name, quantity) -> quantity * (lastClose[name] ?: 0.0) }
            peak = max(peak, equity)
            drawdown = max(drawdown, (peak - equity) / peak * 100)
        }
        return (equity - 1) * 100 to drawdown
    }

    private fun resultOf(position: Position, exitPrice: Double, costHalf: Double): Double =
        (exitPrice * (1 - costHalf) - position.entryPrice) / position.entryPrice * 100 - costHalf * 100

    private fun stopFor(price: Double, atr: Double, settings: PortfolioSettings): Double {
        val floor = price * (1 - settings.maxStopPercent / 100)
        if (atr <= 0) return floor
        return max(price - settings.atrMultiplier * atr, floor)
    }

    private fun equity(
        cash: Double,
        positions: Map<String, Position>,
        lastClose: Map<String, Double>,
    ): Double = cash + positions.entries.sumOf { (name, p) -> p.quantity * (lastClose[name] ?: p.entryPrice) }

    private fun candleOn(series: Map<String, Series>, name: String, date: String): Candle? {
        val s = series[name] ?: return null
        val index = s.indexByDate[date] ?: return null
        return s.candles[index]
    }

    /** Окно истории инструмента по указанную дату включительно, без заглядывания вперёд. */
    private fun windowUpTo(series: Map<String, Series>, name: String, date: String): List<Candle>? {
        val s = series[name] ?: return null
        val index = s.indexByDate[date] ?: return null
        val from = max(0, index - 200)
        return s.candles.subList(from, index + 1)
    }

    private fun day(time: String): String = time.take(10)
}
