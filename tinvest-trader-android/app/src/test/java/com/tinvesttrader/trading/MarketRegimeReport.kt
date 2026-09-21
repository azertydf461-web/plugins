package com.tinvesttrader.trading

import java.io.File
import java.util.Locale
import org.junit.Test

/**
 * Где именно у системы край.
 *
 * Прошлые прогоны показали единственный класс, выигрывавший систематически, —
 * индексы Мосбиржи, и напрашивалась догадка: пробой канала зарабатывает в
 * боковике, а на тренде отдаёт заработанное обратно. Догадку надо либо
 * подтвердить числом, либо отбросить.
 *
 * Режим определяется по самому инструменту на момент входа: годовое изменение
 * цены к этому дню. Порог в 15% делит историю на три состояния — рост, боковик
 * и падение. Знание берётся только из прошлого относительно входа, поэтому
 * подсматривания вперёд здесь нет.
 *
 * Рядом с результатом системы стоит пассив в том же режиме: средний дневной
 * ход инструмента на днях этого режима, растянутый на среднюю длительность
 * сделки. Без этой колонки нельзя отличить «система зарабатывает в боковике»
 * от «в боковике зарабатывают все».
 */
class MarketRegimeReport {

    private companion object {
        /** Окно, по которому определяется режим: примерно торговый год. */
        const val REGIME_WINDOW = 252

        /** Ширина боковика: годовое изменение в пределах ±15%. */
        const val SIDEWAYS_BAND = 15.0

        const val UP = "тренд вверх"
        const val FLAT = "боковик"
        const val DOWN = "тренд вниз"
        val ORDER = listOf(UP, FLAT, DOWN)
    }

    private class Bucket {
        var trades = 0
        var wins = 0
        var resultSum = 0.0
        var barsSum = 0
        var marketDays = 0
        var marketDailySum = 0.0

        val expectancy get() = if (trades == 0) 0.0 else resultSum / trades
        val winRate get() = if (trades == 0) 0.0 else wins * 100.0 / trades
        val averageBars get() = if (trades == 0) 0.0 else barsSum.toDouble() / trades
        val marketDaily get() = if (marketDays == 0) 0.0 else marketDailySum / marketDays

        /** Что дал бы вход наугад той же длительности в том же режиме. */
        val passiveOverTrade get() = marketDaily * averageBars
    }

    @Test
    fun printReport() {
        val dir = File(System.getenv("REGIME_DATA_DIR") ?: System.getenv("BACKTEST_DATA_DIR") ?: "app/build/backtest-data")
        val files = dir.listFiles { file -> file.extension == "csv" }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            println("Режимы: данные не найдены (${dir.absolutePath}) — пропуск.")
            return
        }

        val overall = ORDER.associateWith { Bucket() }
        val byClass = linkedMapOf<String, Map<String, Bucket>>()
        var instruments = 0

        files.forEach { file ->
            val candles = readCandlesCsv(file)
            if (candles.size < REGIME_WINDOW + 200) return@forEach
            instruments++
            val assetClass = file.nameWithoutExtension.substringBefore("__")
            val buckets = byClass.getOrPut(assetClass) { ORDER.associateWith { Bucket() } }

            val closes = candles.map { it.close.toDouble() }
            val regimes = closes.indices.map { index ->
                if (index < REGIME_WINDOW) null else regimeOf(closes[index] / closes[index - REGIME_WINDOW] - 1)
            }

            // Пассив: дневной ход инструмента на днях каждого режима.
            for (index in 1 until closes.size) {
                val regime = regimes[index - 1] ?: continue
                val move = (closes[index] / closes[index - 1] - 1) * 100
                listOf(overall.getValue(regime), buckets.getValue(regime)).forEach {
                    it.marketDays++
                    it.marketDailySum += move
                }
            }

            // Прогон печатает время входа в сокращённом виде, а не так, как оно
            // записано в свече: искать надо по той же форме, иначе ни одна
            // сделка не найдётся и отчёт молча покажет нули.
            val indexByTime = candles.withIndex()
                .associate { (i, candle) -> candle.time.take(16).replace('T', ' ') to i }
            val result = StrategyBacktest.run(
                file.nameWithoutExtension,
                candles,
                BotBacktestSettings(pollEveryNBars = 1, donchian = true),
            ) ?: return@forEach

            result.trades.forEach { trade ->
                val entryIndex = indexByTime[trade.entryTime] ?: return@forEach
                val regime = regimes.getOrNull(entryIndex) ?: return@forEach
                listOf(overall.getValue(regime), buckets.getValue(regime)).forEach {
                    it.trades++
                    if (trade.resultPercent > 0) it.wins++
                    it.resultSum += trade.resultPercent
                    it.barsSum += trade.barsHeld
                }
            }
        }

        if (instruments == 0) {
            println("Режимы: ни у одного инструмента нет истории нужной длины.")
            return
        }
        check(overall.values.sumOf { it.trades } > 0) {
            "Режимы: сделок не нашлось ни одной — разбор нечего показывать, и нули здесь были бы обманом."
        }

        println()
        println("================ Режим рынка на входе ================")
        println("Инструментов: $instruments, окно режима: $REGIME_WINDOW дней, боковик: ±$SIDEWAYS_BAND%")
        println(
            "режим".padEnd(14) + "сделок".padStart(8) + "доля дней".padStart(11) +
                "выигрыш".padStart(9) + "на сделку".padStart(11) + "вклад".padStart(10) +
                "держим".padStart(8) + "пассив".padStart(9),
        )
        printRows(overall)

        println()
        println("--- вход на растущем рынке отключён ---")
        printComparison(files)

        println()
        println("--- по классам активов ---")
        byClass.entries.sortedBy { it.key }.forEach { (assetClass, buckets) ->
            println("$assetClass:")
            printRows(buckets)
        }
    }

    /**
     * Прямое сравнение на тех же данных: те же сделки с правилом и без.
     * Считается по инструментам, чтобы было видно не только сумму, но и на
     * скольких инструментах правило помогло.
     */
    private fun printComparison(files: List<java.io.File>) {
        var withTrades = 0
        var withoutTrades = 0
        var withSum = 0.0
        var withoutSum = 0.0
        var better = 0
        var worse = 0
        var counted = 0

        files.forEach { file ->
            val candles = readCandlesCsv(file)
            if (candles.size < REGIME_WINDOW + 200) return@forEach
            val base = BotBacktestSettings(pollEveryNBars = 1, donchian = true)
            val open = StrategyBacktest.run(file.nameWithoutExtension, candles, base) ?: return@forEach
            val filtered = StrategyBacktest.run(
                file.nameWithoutExtension,
                candles,
                base.copy(skipRisingMarket = true),
            ) ?: return@forEach

            counted++
            withoutTrades += open.tradeCount
            withTrades += filtered.tradeCount
            withoutSum += open.totalReturnPercent
            withSum += filtered.totalReturnPercent
            if (filtered.totalReturnPercent > open.totalReturnPercent) better++ else worse++
        }

        if (counted == 0) {
            println("Сравнить не на чем.")
            return
        }
        println("Инструментов в сравнении: $counted")
        println(
            "как есть:      сделок ${withoutTrades}, итог ${num(withoutSum)}%, " +
                "на инструмент ${num(withoutSum / counted)}%",
        )
        println(
            "без роста:     сделок ${withTrades}, итог ${num(withSum)}%, " +
                "на инструмент ${num(withSum / counted)}%",
        )
        println("правило помогло на $better инструментах из $counted, навредило на $worse")
    }

    private fun printRows(buckets: Map<String, Bucket>) {
        val allDays = buckets.values.sumOf { it.marketDays }.coerceAtLeast(1)
        ORDER.forEach { regime ->
            val bucket = buckets.getValue(regime)
            println(
                regime.padEnd(14) +
                    bucket.trades.toString().padStart(8) +
                    num(bucket.marketDays * 100.0 / allDays).padStart(11) +
                    num(bucket.winRate).padStart(9) +
                    num(bucket.expectancy).padStart(11) +
                    num(bucket.resultSum).padStart(10) +
                    num(bucket.averageBars).padStart(8) +
                    num(bucket.passiveOverTrade).padStart(9),
            )
        }
    }

    private fun regimeOf(yearChange: Double): String = when {
        yearChange * 100 > SIDEWAYS_BAND -> UP
        yearChange * 100 < -SIDEWAYS_BAND -> DOWN
        else -> FLAT
    }

    private fun num(value: Double): String = String.format(Locale.US, "%.2f", value)
}
