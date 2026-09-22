package com.tinvesttrader.trading

import java.io.File
import java.util.Locale
import kotlin.math.sqrt
import org.junit.Test

/**
 * Торговля коридора на валютных фьючерсах: цена закрылась ниже нижней
 * границы — покупка, выше верхней — продажа (на фьючерсе продажа без займа и
 * без платы за перенос). Выход — когда цена вернулась к середине коридора.
 * Стоп — если цена ушла дальше границы ещё на столько же: коридор сломан.
 * Решение по закрытию дня, исполнение по открытию следующего.
 */
class CurrencyRangeReport {

    private data class Params(val period: Int, val entrySigma: Double, val stopSigma: Double, val maxHold: Int)

    private data class Trade(val entryDate: String, val resultPercent: Double, val bars: Int, val stopped: Boolean)

    private val commissionPercent = 0.1
    private val spreadPercent = 0.02

    private val grid = listOf(
        Params(20, 2.0, 3.5, 30),
        Params(20, 1.5, 3.0, 30),
        Params(40, 2.0, 3.5, 40),
        Params(60, 2.0, 3.5, 60),
    )

    @Test
    fun printReport() {
        val dir = File(System.getenv("RANGE_DATA_DIR") ?: "app/build/range-data")
        val files = dir.listFiles { f -> f.extension == "csv" }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            println("Коридор: данных нет (${dir.absolutePath}).")
            return
        }
        grid.forEach { p ->
            println()
            println(
                "==== Коридор ${p.period} дн., вход за ${num(p.entrySigma)}σ, стоп за ${num(p.stopSigma)}σ, " +
                    "не дольше ${p.maxHold} дн.; комиссия ${num(commissionPercent)}%, спред ${num(spreadPercent)}% ====",
            )
            println(
                "инструмент".padEnd(14) + "сделок".padStart(7) + "выигр".padStart(7) + "на сделку".padStart(11) +
                    "итог".padStart(9) + "до изд.".padStart(9) + "стопов".padStart(8) +
                    "до 2022".padStart(9) + "с 2022".padStart(9),
            )
            files.forEach { file ->
                val candles = readCandlesCsv(file)
                val trades = simulate(candles, p)
                val name = file.nameWithoutExtension.substringAfter("__")
                if (trades.isEmpty()) {
                    println("$name: сделок нет")
                    return@forEach
                }
                val cost = 2 * (commissionPercent + spreadPercent)
                val gross = trades.map { it.resultPercent + cost }
                val before = trades.filter { it.entryDate < "2022" }
                val after = trades.filter { it.entryDate >= "2022" }
                println(
                    name.padEnd(14) + trades.size.toString().padStart(7) +
                        num(trades.count { it.resultPercent > 0 } * 100.0 / trades.size).padStart(7) +
                        num(trades.map { it.resultPercent }.average()).padStart(11) +
                        num(compound(trades.map { it.resultPercent })).padStart(9) +
                        num(compound(gross)).padStart(9) +
                        trades.count { it.stopped }.toString().padStart(8) +
                        (if (before.isEmpty()) "—" else num(compound(before.map { it.resultPercent }))).padStart(9) +
                        (if (after.isEmpty()) "—" else num(compound(after.map { it.resultPercent }))).padStart(9),
                )
            }
        }
    }

    private fun simulate(candles: List<com.tinvesttrader.data.Candle>, p: Params): List<Trade> {
        val closes = candles.map { it.close.toDouble() }
        val opens = candles.map { it.open.toDouble() }
        val costHalf = (commissionPercent + spreadPercent) / 100
        val trades = mutableListOf<Trade>()
        var side = 0 // +1 покупка, -1 продажа
        var entryPrice = 0.0
        var entryBar = 0
        var stopLevel = 0.0
        var pending = 0 // решение, исполняемое по открытию следующего дня
        var pendingExit = false
        var pendingStopped = false

        for (bar in p.period until candles.size) {
            // Исполнение решения, принятого вчера.
            if (pendingExit && side != 0) {
                val exit = opens[bar]
                val raw = side * (exit - entryPrice) / entryPrice
                trades += Trade(candles[entryBar].time, (raw - 2 * costHalf) * 100, bar - entryBar, pendingStopped)
                side = 0
                pendingExit = false
            }
            if (pending != 0 && side == 0) {
                side = pending
                entryPrice = opens[bar]
                entryBar = bar
                pending = 0
            }
            pending = 0

            val window = closes.subList(bar - p.period + 1, bar + 1)
            val mean = window.average()
            val sd = sqrt(window.sumOf { (it - mean) * (it - mean) } / window.size)
            if (sd <= 0) continue
            val close = closes[bar]

            if (side != 0) {
                if (bar == entryBar) stopLevel = mean + side * -p.stopSigma * sd
                val broken = if (side > 0) close < stopLevel else close > stopLevel
                val back = if (side > 0) close >= mean else close <= mean
                if (broken || back || bar - entryBar >= p.maxHold) {
                    pendingExit = true
                    pendingStopped = broken
                }
                continue
            }
            if (bar == candles.size - 1) break
            if (close < mean - p.entrySigma * sd) pending = 1
            if (close > mean + p.entrySigma * sd) pending = -1
        }
        return trades
    }

    private fun compound(results: List<Double>): Double =
        (results.fold(1.0) { acc, r -> acc * (1 + r / 100) } - 1) * 100

    private fun num(value: Double): String = String.format(Locale.US, "%.2f", value)
}
