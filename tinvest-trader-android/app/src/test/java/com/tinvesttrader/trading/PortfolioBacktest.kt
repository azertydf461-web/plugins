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
    /**
     * НДФЛ с прибыли по каждой закрытой сделке. Для активной торговли налог
     * платится с каждого удачного выхода, и на длинной дистанции это
     * заметная часть результата.
     */
    val taxRatePercent: Double = 13.0,
    /**
     * Налог для «купить и держать». По умолчанию ноль: при владении бумагой
     * дольше трёх лет действует льгота на долгосрочное владение. Это
     * несимметрично к системе намеренно — такова реальность, а не поблажка
     * эталону.
     */
    val buyHoldTaxRatePercent: Double = 0.0,
    /** Через сколько торговых дней эталон приводится обратно к равным долям. */
    val rebalanceDays: Int = 252,
    /**
     * Ставка за перенос непокрытой позиции, годовых. Начисляется только на
     * часть позиций сверх собственного капитала; при работе без плеча равна
     * нулю по построению.
     */
    val marginRatePercent: Double = 20.0,
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
    /** Тот же эталон, но с НДФЛ — на случай, если льготы по сроку владения нет. */
    val buyHoldAfterTaxReturnPercent: Double,
    val taxPaidPercent: Double,
    val marginPaidPercent: Double,
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
        val invested: Double,
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
        var taxPaid = 0.0
        var marginPaid = 0.0

        dates.forEachIndexed { dayIndex, date ->
            // 1. Исполняем заявки, выставленные накануне, по открытию.
            pendingExit.toList().forEach { name ->
                val candle = candleOn(series, name, date) ?: return@forEach
                val position = positions.remove(name) ?: run { pendingExit.remove(name); return@forEach }
                val closed = close(position, candle.open.toDouble(), costHalf, settings)
                cash += closed.first
                taxPaid += closed.second
                results += resultOf(position, closed.first)
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
                    invested = slot,
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
                    val closed = close(position, fill, costHalf, settings)
                    cash += closed.first
                    taxPaid += closed.second
                    results += resultOf(position, closed.first)
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

            // Плечо система не использует: доля в рынке не превышает капитал.
            // Если бы превышала, перенос стоил бы денег каждый день — считаем
            // это здесь, чтобы нулевая строка в отчёте была посчитанной, а не
            // забытой.
            if (cash < 0) {
                val daily = settings.marginRatePercent / 100.0 / 365.0
                val cost = -cash * daily
                cash -= cost
                marginPaid += cost
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
            buyHoldReturnPercent = hold.gross,
            buyHoldMaxDrawdownPercent = hold.drawdown,
            buyHoldAfterTaxReturnPercent = hold.afterTax,
            taxPaidPercent = taxPaid * 100,
            marginPaidPercent = marginPaid * 100,
        )
    }

    /**
     * Равновзвешенное «купить и держать» по той же корзине. Считается той же
     * ежедневной кривой, чтобы просадку пассива можно было сравнивать с
     * просадкой системы, а не принимать на веру.
     */
    private class HoldResult(val gross: Double, val afterTax: Double, val drawdown: Double)

    /**
     * Равновзвешенное «купить и держать» по той же корзине, с периодическим
     * приведением к равным долям и теми же издержками на ребалансировке.
     * Считается ежедневной кривой, чтобы просадку пассива можно было
     * сравнивать с просадкой системы, а не принимать на веру.
     */
    private fun buyAndHold(
        series: Map<String, Series>,
        dates: List<String>,
        settings: PortfolioSettings,
    ): HoldResult {
        val startDate = dates[settings.warmupBars]
        val quantities = mutableMapOf<String, Double>()
        val lastClose = mutableMapOf<String, Double>()
        val costHalf = (settings.commissionPercent + settings.spreadPercent) / 100.0
        val share = 1.0 / series.size

        series.forEach { (name, s) ->
            val index = s.indexByDate[startDate] ?: s.indexByDate.entries
                .filter { it.key >= startDate }
                .minByOrNull { it.key }?.value
            if (index != null) {
                val price = s.candles[index].close.toDouble()
                if (price > 0) {
                    quantities[name] = share * (1 - costHalf) / price
                    lastClose[name] = price
                }
            }
        }

        var peak = 1.0
        var drawdown = 0.0
        var equity = 1.0
        var rebalanceCost = 0.0

        dates.drop(settings.warmupBars).forEachIndexed { index, date ->
            series.forEach { (name, _) ->
                candleOn(series, name, date)?.let { lastClose[name] = it.close.toDouble() }
            }
            equity = quantities.entries.sumOf { (name, quantity) -> quantity * (lastClose[name] ?: 0.0) }

            if (settings.rebalanceDays > 0 && index > 0 && index % settings.rebalanceDays == 0 && equity > 0) {
                val target = equity / quantities.size
                var turnover = 0.0
                quantities.keys.toList().forEach { name ->
                    val price = lastClose[name] ?: return@forEach
                    if (price <= 0) return@forEach
                    val current = quantities.getValue(name) * price
                    turnover += kotlin.math.abs(target - current)
                    quantities[name] = target / price
                }
                val cost = turnover * costHalf
                rebalanceCost += cost
                val scale = (equity - cost) / equity
                quantities.keys.toList().forEach { name -> quantities[name] = quantities.getValue(name) * scale }
                equity -= cost
            }

            peak = max(peak, equity)
            drawdown = max(drawdown, (peak - equity) / peak * 100)
        }

        val gross = (equity - 1) * 100
        val tax = if (gross > 0) gross * settings.buyHoldTaxRatePercent / 100.0 else 0.0
        return HoldResult(gross, gross - tax, drawdown)
    }

    /**
     * Закрытие позиции: из выручки уходят комиссия и спред, а с прибыли —
     * НДФЛ. Возвращает деньги, реально вернувшиеся на счёт, и уплаченный
     * налог отдельной величиной, чтобы его было видно в отчёте.
     */
    private fun close(
        position: Position,
        exitPrice: Double,
        costHalf: Double,
        settings: PortfolioSettings,
    ): Pair<Double, Double> {
        val gross = position.quantity * exitPrice * (1 - costHalf)
        val profit = gross - position.invested
        val tax = if (profit > 0) profit * settings.taxRatePercent / 100.0 else 0.0
        return (gross - tax) to tax
    }

    /** Результат сделки в процентах к вложенному, уже после издержек и налога. */
    private fun resultOf(position: Position, proceeds: Double): Double =
        if (position.invested <= 0) 0.0 else (proceeds - position.invested) / position.invested * 100

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
