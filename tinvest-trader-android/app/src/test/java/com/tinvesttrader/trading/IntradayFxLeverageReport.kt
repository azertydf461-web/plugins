package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Test

/**
 * Внутридневная торговля валютными фьючерсами с плечом. Позиция в обе
 * стороны, решение по закрытию свечи, исполнение по открытию следующей,
 * всё закрывается на последней свече дня (перенос через ночь не платится).
 *
 * Плечо: результат сделки умножается на плечо, комиссия берётся со всего
 * объёма позиции и тоже умножается. Если убыток съедает счёт — счёт обнулён.
 */
class IntradayFxLeverageReport {

    private data class Trade(val day: String, val gross: Double, val stopped: Boolean)

    private val commissionPercent = 0.1
    private val spreadPercent = 0.01
    private val leverages = listOf(1.0, 3.0, 5.0)

    @Test
    fun printReport() {
        val dir = File(System.getenv("INTRADAY_FX_DIR") ?: "app/build/intraday-fx")
        val files = dir.listFiles { f -> f.extension == "csv" }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            println("Внутридневная валюта: данных нет (${dir.absolutePath}).")
            return
        }
        files.forEach { file ->
            val candles = readCandlesCsv(file)
            if (candles.size < 2000) {
                println("${file.nameWithoutExtension}: только ${candles.size} свечей — пропуск")
                return@forEach
            }
            val days = candles.map { it.time.take(10) }.distinct().size
            println()
            println("==== ${file.nameWithoutExtension}: ${candles.size} свечей, $days торговых дней, " +
                "${candles.first().time.take(10)} … ${candles.last().time.take(10)} ====")
            println(
                "правило".padEnd(30) + "сделок".padStart(7) + "в день".padStart(7) + "выигр".padStart(7) +
                    "до изд.".padStart(9) + "после".padStart(8) +
                    leverages.joinToString("") { "плечо ${it.toInt()}".padStart(11) } +
                    "просадка x5".padStart(13),
            )
            listOf(
                "пробой 20/10 свечей" to breakout(candles, 20, 10),
                "пробой 60/30 свечей" to breakout(candles, 60, 30),
                "возврат к средней 20, 2σ" to reversion(candles, 20, 2.0, 3.5),
                "возврат к средней 60, 2.5σ" to reversion(candles, 60, 2.5, 4.0),
            ).forEach { (name, trades) -> printRow(name, trades, days) }
        }
    }

    private fun printRow(name: String, trades: List<Trade>, days: Int) {
        if (trades.isEmpty()) {
            println(name.padEnd(30) + "сделок нет")
            return
        }
        val cost = 2 * (commissionPercent + spreadPercent)
        val gross = trades.map { it.gross }
        val net = gross.map { it - cost }
        val cells = leverages.map { lev ->
            val (total, busted, _) = equity(net.map { it * lev })
            if (busted) "обнулён" else num(total)
        }
        val (_, _, dd5) = equity(net.map { it * 5 })
        println(
            name.padEnd(30) + trades.size.toString().padStart(7) +
                num(trades.size.toDouble() / days).padStart(7) +
                num(trades.count { it.gross - cost > 0 } * 100.0 / trades.size).padStart(7) +
                num(gross.average()).padStart(9) + num(net.average()).padStart(8) +
                cells.joinToString("") { it.padStart(11) } + num(dd5).padStart(13),
        )
    }

    /** Итог в %, обнулён ли счёт, наибольшая просадка в %. */
    private fun equity(results: List<Double>): Triple<Double, Boolean, Double> {
        var value = 1.0
        var peak = 1.0
        var maxDd = 0.0
        for (r in results) {
            value *= 1 + r / 100
            if (value <= 0.05) return Triple(-100.0, true, 100.0)
            peak = max(peak, value)
            maxDd = max(maxDd, (peak - value) / peak * 100)
        }
        return Triple((value - 1) * 100, false, maxDd)
    }

    private fun day(c: Candle) = c.time.take(10)

    private fun isLastOfDay(candles: List<Candle>, i: Int) = i == candles.size - 1 || day(candles[i]) != day(candles[i + 1])

    /** Пробой канала в обе стороны; выход по противоположному каналу покороче или в конце дня. */
    private fun breakout(candles: List<Candle>, entry: Int, exit: Int): List<Trade> {
        val hi = candles.map { it.high.toDouble() }
        val lo = candles.map { it.low.toDouble() }
        val cl = candles.map { it.close.toDouble() }
        val op = candles.map { it.open.toDouble() }
        return run(candles, entry) { i, side ->
            val upper = (i - entry until i).maxOf { hi[it] }
            val lower = (i - entry until i).minOf { lo[it] }
            val exitUpper = (i - exit until i).maxOf { hi[it] }
            val exitLower = (i - exit until i).minOf { lo[it] }
            when {
                side > 0 && cl[i] < exitLower -> Action.EXIT
                side < 0 && cl[i] > exitUpper -> Action.EXIT
                side == 0 && cl[i] > upper -> Action.LONG
                side == 0 && cl[i] < lower -> Action.SHORT
                else -> Action.NONE
            }
        }.also { check(op.isNotEmpty()) }
    }

    /** Возврат к средней в обе стороны; выход у средней, стоп дальше полосы, в конце дня — принудительно. */
    private fun reversion(candles: List<Candle>, period: Int, band: Double, stop: Double): List<Trade> {
        val cl = candles.map { it.close.toDouble() }
        return run(candles, period) { i, side ->
            val w = cl.subList(i - period + 1, i + 1)
            val m = w.average()
            val sd = sqrt(w.sumOf { (it - m) * (it - m) } / w.size)
            when {
                sd <= 0 -> Action.NONE
                side > 0 && cl[i] < m - stop * sd -> Action.STOP
                side < 0 && cl[i] > m + stop * sd -> Action.STOP
                side > 0 && cl[i] >= m -> Action.EXIT
                side < 0 && cl[i] <= m -> Action.EXIT
                side == 0 && cl[i] < m - band * sd -> Action.LONG
                side == 0 && cl[i] > m + band * sd -> Action.SHORT
                else -> Action.NONE
            }
        }
    }

    private enum class Action { NONE, LONG, SHORT, EXIT, STOP }

    private fun run(candles: List<Candle>, warmup: Int, rule: (Int, Int) -> Action): List<Trade> {
        val op = candles.map { it.open.toDouble() }
        val cl = candles.map { it.close.toDouble() }
        val trades = mutableListOf<Trade>()
        var side = 0
        var entry = 0.0
        var pending = Action.NONE
        for (i in warmup until candles.size) {
            // Исполнение вчерашнего решения по открытию, если это та же сессия.
            val sameDay = day(candles[i]) == day(candles[i - 1])
            if (pending != Action.NONE && sameDay) {
                when (pending) {
                    Action.LONG, Action.SHORT -> if (side == 0) {
                        side = if (pending == Action.LONG) 1 else -1
                        entry = op[i]
                    }
                    Action.EXIT, Action.STOP -> if (side != 0) {
                        trades += Trade(day(candles[i]), side * (op[i] - entry) / entry * 100, pending == Action.STOP)
                        side = 0
                    }
                    else -> {}
                }
            }
            pending = Action.NONE
            if (isLastOfDay(candles, i)) {
                if (side != 0) {
                    trades += Trade(day(candles[i]), side * (cl[i] - entry) / entry * 100, false)
                    side = 0
                }
                continue
            }
            pending = rule(i, side)
        }
        return trades
    }

    private fun num(v: Double) = String.format(Locale.US, "%.2f", v)
}
