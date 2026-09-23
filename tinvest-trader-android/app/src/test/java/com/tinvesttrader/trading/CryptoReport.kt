package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import java.io.File
import java.util.Locale
import kotlin.math.max
import org.junit.Test

/**
 * Криптовалюта: тот же пробой канала.
 *
 *  1. «Бот как есть» — рабочая логика приложения (только покупка, стопы и
 *     фильтры), комиссия спота 0,1 %.
 *  2. «Пробой, только покупка» и «пробой в обе стороны» — чистое правило
 *     20/10 без фильтров. Обе стороны — это бессрочный фьючерс: комиссия
 *     0,04 %, плата за финансирование позиции 0,03 % в день.
 *
 * Решение по закрытию свечи, исполнение по открытию следующей.
 */
class CryptoReport {

    private data class Trade(val entry: String, val result: Double)

    @Test
    fun printReport() {
        val dir = File(System.getenv("CRYPTO_DATA_DIR") ?: "app/build/crypto-data")
        val files = dir.listFiles { f -> f.extension == "csv" }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            println("Крипта: данных нет (${dir.absolutePath}).")
            return
        }
        listOf("osnovnye" to "Биткоин и эфир", "oos" to "Отложенная выборка — другие монеты").forEach { (group, title) ->
            val own = files.filter { it.name.startsWith("${group}__") }
            if (own.isEmpty()) return@forEach
            println()
            println("==== $title, дневные свечи ====")
            header()
            own.forEach { daily(it) }
        }
        val hourly = files.filter { it.name.startsWith("chas__") }
        if (hourly.isNotEmpty()) {
            println()
            println("==== Часовые свечи ====")
            header()
            hourly.forEach { hourly(it) }
        }
    }

    private fun header() = println(
        "инструмент / правило".padEnd(34) + "сделок".padStart(7) + "выигр".padStart(7) + "на сделку".padStart(10) +
            "итог".padStart(11) + "удерж".padStart(11) + "просадка".padStart(9) +
            "до 2022".padStart(10) + "с 2022".padStart(10),
    )

    private fun daily(file: File) {
        val candles = readCandlesCsv(file)
        val name = file.nameWithoutExtension.substringAfter("__")
        if (candles.size < 500) {
            println("$name: только ${candles.size} свечей — пропуск")
            return
        }
        val hold = (candles.last().close.toDouble() / candles.first().open.toDouble() - 1) * 100
        println("$name: ${candles.first().time.take(10)} … ${candles.last().time.take(10)}, ${candles.size} дней")
        StrategyBacktest.run(
            name, candles,
            BotBacktestSettings(commissionPercent = 0.1, spreadPercent = 0.02, pollEveryNBars = 1, donchian = true),
        )?.let { r ->
            println(
                "  бот как есть, спот 0,1%".padEnd(34) + r.tradeCount.toString().padStart(7) +
                    num(r.winRatePercent).padStart(7) + num(r.expectancyPercent).padStart(10) +
                    num(r.totalReturnPercent).padStart(11) + num(r.buyHoldReturnPercent).padStart(11) +
                    num(r.maxDrawdownPercent).padStart(9),
            )
        }
        row("  пробой, покупка, спот 0,1%", breakout(candles, 20, 10, false, 0.1, 0.0), hold)
        row("  пробой, обе стороны, фьюч 0,04%", breakout(candles, 20, 10, true, 0.04, 0.03), hold)
        row("  то же без издержек", breakout(candles, 20, 10, true, 0.0, 0.0), hold)
    }

    private fun hourly(file: File) {
        val candles = readCandlesCsv(file)
        val name = file.nameWithoutExtension.substringAfter("__")
        if (candles.size < 5000) {
            println("$name: только ${candles.size} свечей — пропуск")
            return
        }
        val hold = (candles.last().close.toDouble() / candles.first().open.toDouble() - 1) * 100
        println("$name: ${candles.first().time.take(10)} … ${candles.last().time.take(10)}, ${candles.size} свечей")
        val perBar = 0.03 / 24
        row("  пробой 20/10, обе, фьюч 0,04%", breakout(candles, 20, 10, true, 0.04, perBar), hold)
        row("  пробой 120/60, обе, фьюч 0,04%", breakout(candles, 120, 60, true, 0.04, perBar), hold)
        row("  пробой 120/60, покупка, спот 0,1%", breakout(candles, 120, 60, false, 0.1, 0.0), hold)
        row("  пробой 120/60, обе, без издержек", breakout(candles, 120, 60, true, 0.0, 0.0), hold)
    }

    private fun row(label: String, trades: List<Trade>, hold: Double) {
        if (trades.isEmpty()) {
            println(label.padEnd(34) + "сделок нет")
            return
        }
        val results = trades.map { it.result }
        val before = trades.filter { it.entry < "2022" }.map { it.result }
        val after = trades.filter { it.entry >= "2022" }.map { it.result }
        println(
            label.padEnd(34) + trades.size.toString().padStart(7) +
                num(results.count { it > 0 } * 100.0 / results.size).padStart(7) +
                num(results.average()).padStart(10) + num(compound(results)).padStart(11) +
                num(hold).padStart(11) + num(drawdown(results)).padStart(9) +
                (if (before.isEmpty()) "—" else num(compound(before))).padStart(10) +
                (if (after.isEmpty()) "—" else num(compound(after))).padStart(10),
        )
    }

    /** Пробой канала: вход при закрытии за экстремумом [entry] свечей, выход — за противоположным экстремумом [exit] свечей. */
    private fun breakout(
        candles: List<Candle>, entry: Int, exit: Int, allowShort: Boolean,
        commission: Double, financingPerBar: Double,
    ): List<Trade> {
        val hi = candles.map { it.high.toDouble() }
        val lo = candles.map { it.low.toDouble() }
        val cl = candles.map { it.close.toDouble() }
        val op = candles.map { it.open.toDouble() }
        val costHalf = commission + 0.02 * (if (commission > 0) 1 else 0)
        val trades = mutableListOf<Trade>()
        var side = 0
        var entryPrice = 0.0
        var entryBar = 0
        var pending = 0 // +1/-1 вход, 2 выход
        fun close(bar: Int) {
            val gross = side * (op[bar] - entryPrice) / entryPrice * 100
            val net = gross - 2 * costHalf - financingPerBar * (bar - entryBar)
            trades += Trade(candles[entryBar].time, net)
            side = 0
        }
        for (i in entry until candles.size) {
            when {
                pending == 2 && side != 0 -> close(i)
                (pending == 1 || pending == -1) && side == 0 -> {
                    side = pending; entryPrice = op[i]; entryBar = i
                }
            }
            pending = 0
            val upper = (i - entry until i).maxOf { hi[it] }
            val lower = (i - entry until i).minOf { lo[it] }
            val exitUpper = (i - exit until i).maxOf { hi[it] }
            val exitLower = (i - exit until i).minOf { lo[it] }
            pending = when {
                side > 0 && cl[i] < exitLower -> 2
                side < 0 && cl[i] > exitUpper -> 2
                side == 0 && cl[i] > upper -> 1
                side == 0 && allowShort && cl[i] < lower -> -1
                else -> 0
            }
        }
        return trades
    }

    private fun compound(r: List<Double>) = (r.fold(1.0) { a, x -> a * max(0.0, 1 + x / 100) } - 1) * 100

    private fun drawdown(r: List<Double>): Double {
        var v = 1.0; var peak = 1.0; var dd = 0.0
        r.forEach { v *= max(0.0, 1 + it / 100); peak = max(peak, v); dd = max(dd, (peak - v) / peak * 100) }
        return dd
    }

    private fun num(v: Double) = String.format(Locale.US, "%.2f", v)
}
