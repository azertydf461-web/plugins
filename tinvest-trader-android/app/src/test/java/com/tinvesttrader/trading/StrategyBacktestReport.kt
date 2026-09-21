package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import com.tinvesttrader.data.Quotation
import java.io.File
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import org.junit.Test

/**
 * Прогон стратегий по настоящим котировкам, а не по выдуманным числам.
 *
 * Свечи кладёт рядом workflow (Московская биржа и Stooq), тест читает файлы и
 * гоняет ТУ ЖЕ логику, которой торгует приложение: никакой отдельной
 * «модельной» копии, иначе проверялась бы не та программа.
 *
 * Имя файла — `<класс>__<инструмент>`: итоги группируются по классу активов,
 * потому что вопрос не «работает ли на Сбере», а «на каком рынке у системы
 * вообще есть край».
 *
 * Если файлов с котировками нет, тест молча проходит: обычная сборка не
 * должна падать из-за отсутствия исследовательских данных.
 */
class StrategyBacktestReport {

    private data class Row(
        val assetClass: String,
        val name: String,
        val breakout: BotBacktestResult,
        val sizing: BotBacktestResult,
        val blocking: BotBacktestResult,
    )

    @Test
    fun printReport() {
        val dataDir = File(System.getenv("BACKTEST_DATA_DIR") ?: "app/build/backtest-data")
        val files = dataDir.listFiles { file -> file.extension == "csv" }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            println("BACKTEST: котировки не найдены (${dataDir.absolutePath}) — пропуск.")
            return
        }

        val candlesByName = files.associate { it.nameWithoutExtension to readCandles(it) }
            .filterValues { it.size >= 400 }
        val rows = files.mapNotNull { file -> runOne(file) }
        if (rows.isEmpty()) {
            println("BACKTEST: ни один инструмент не дал достаточной истории.")
            return
        }

        rows.groupBy { it.assetClass }.toSortedMap().forEach { (assetClass, group) ->
            printGroup(assetClass, group)
        }
        printOverall(rows)
        printPortfolio(candlesByName)
    }

    /**
     * Портфель на тех же сделках. Одиночные прогоны дали край в доли процента
     * при разбросе итогов от минус сорока до плюс шестидесяти процентов —
     * вопрос в том, собирается ли из этих сделок работающее целое, когда
     * капитал распределён, а не поставлен на один инструмент.
     */
    private fun printPortfolio(data: Map<String, List<Candle>>) {
        if (data.size < 5) return
        println()
        println("============ ПОРТФЕЛЬ (пробой канала) ============")
        println(
            "позиций".padEnd(10) + "сделок".padStart(8) + "итог".padStart(10) +
                "просад".padStart(9) + "итог/просад".padStart(12) +
                "прибыльн".padStart(10) + "ср.сд".padStart(8) + "в рынке".padStart(9),
        )

        var benchmark: PortfolioResult? = null
        listOf(1, 3, 5, 10, 20, data.size).distinct().forEach { slots ->
            val result = PortfolioBacktest.run(data, PortfolioSettings(maxPositions = slots)) ?: return@forEach
            benchmark = benchmark ?: result
            println(
                slots.toString().padEnd(10) +
                    result.trades.toString().padStart(8) +
                    num(result.totalReturnPercent).padStart(10) +
                    num(result.maxDrawdownPercent).padStart(9) +
                    num(ratio(result)).padStart(12) +
                    num(result.winRatePercent).padStart(10) +
                    num(result.expectancyPercent).padStart(8) +
                    num(result.averageExposurePercent).padStart(9),
            )
        }

        benchmark?.let {
            println()
            println(
                "Равновзвешенное «купить и держать» по той же корзине: " +
                    "итог ${num(it.buyHoldReturnPercent)}%, просадка ${num(it.buyHoldMaxDrawdownPercent)}%, " +
                    "итог/просадка ${num(it.buyHoldReturnPercent / max(1.0, it.buyHoldMaxDrawdownPercent))}",
            )
            println("Инструментов в корзине: ${it.instruments}, торговых дней: ${it.days}")
        }

        // Портфели внутри одного класса: диверсификация по коррелированным
        // инструментам работает хуже, и это должно быть видно отдельно.
        println()
        println("Портфели по классам (5 позиций):")
        data.keys.map { it.substringBefore("__") }.distinct().sorted().forEach { assetClass ->
            val subset = data.filterKeys { it.startsWith("${assetClass}__") }
            if (subset.size < 5) return@forEach
            val result = PortfolioBacktest.run(subset, PortfolioSettings(maxPositions = 5)) ?: return@forEach
            println(
                "  " + assetClass.padEnd(14) +
                    "итог ${num(result.totalReturnPercent)}%".padEnd(18) +
                    "просадка ${num(result.maxDrawdownPercent)}%".padEnd(20) +
                    "удержание ${num(result.buyHoldReturnPercent)}% " +
                    "при просадке ${num(result.buyHoldMaxDrawdownPercent)}%",
            )
        }
    }

    /** Доходность на единицу просадки — то, чем системы вообще сравнивают. */
    private fun ratio(result: PortfolioResult): Double =
        result.totalReturnPercent / max(1.0, result.maxDrawdownPercent)

    private fun runOne(file: File): Row? {
        val candles = readCandles(file)
        if (candles.size < 400) {
            println("${file.nameWithoutExtension}: ${candles.size} свечей — мало, пропуск.")
            return null
        }
        val base = BotBacktestSettings(pollEveryNBars = 1)
        val breakout = StrategyBacktest.run(file.nameWithoutExtension, candles, base.copy(donchian = true))
        val sizing = StrategyBacktest.run(file.nameWithoutExtension, candles, base.copy(sizingMode = true))
        val blocking = StrategyBacktest.run(file.nameWithoutExtension, candles, base)
        if (breakout == null || sizing == null || blocking == null) return null

        val parts = file.nameWithoutExtension.split("__")
        return Row(
            assetClass = parts.firstOrNull() ?: "прочее",
            name = parts.getOrNull(1) ?: file.nameWithoutExtension,
            breakout = breakout,
            sizing = sizing,
            blocking = blocking,
        )
    }

    private fun printGroup(assetClass: String, rows: List<Row>) {
        println()
        println("==== $assetClass (${rows.size}) ====")
        println(
            "инструмент".padEnd(14) + "сдел".padStart(6) + "ПРОБОЙ".padStart(9) +
                "объём".padStart(9) + "запрет".padStart(9) + "исходн".padStart(9) +
                "куп-держ".padStart(10) + "ср.сд".padStart(7) + "просад".padStart(8),
        )
        rows.forEach { row ->
            val d = row.breakout
            println(
                row.name.take(13).padEnd(14) +
                    d.tradeCount.toString().padStart(6) +
                    num(d.totalReturnPercent).padStart(9) +
                    num(row.sizing.totalReturnPercent).padStart(9) +
                    num(row.blocking.totalReturnPercent).padStart(9) +
                    num(d.legacyTotalReturnPercent).padStart(9) +
                    num(d.buyHoldReturnPercent).padStart(10) +
                    num(d.expectancyPercent).padStart(7) +
                    num(d.maxDrawdownPercent).padStart(8),
            )
        }
        printAggregate("Итог по классу", rows)
    }

    private fun printOverall(rows: List<Row>) {
        println()
        println("============ ВСЕ ИНСТРУМЕНТЫ ============")
        printAggregate("Итог", rows)
        println()
        println("По классам, средняя сделка на пробое:")
        rows.groupBy { it.assetClass }.toSortedMap().forEach { (assetClass, group) ->
            val expectancy = group.sumOf { it.breakout.expectancyPercent } / group.size
            val beatsHold = group.count { it.breakout.totalReturnPercent > it.breakout.buyHoldReturnPercent }
            println(
                "  " + assetClass.padEnd(14) + num(expectancy).padStart(7) + "%" +
                    "   лучше удержания $beatsHold из ${group.size}",
            )
        }
    }

    private fun printAggregate(label: String, rows: List<Row>) {
        val breakout = rows.map { it.breakout }
        println(
            "$label: инструментов ${rows.size}, сделок ${breakout.sumOf { it.tradeCount }}, " +
                "ср. сделка ${num(breakout.sumOf { it.expectancyPercent } / rows.size)}%, " +
                "положительных ${breakout.count { it.expectancyPercent > 0 }} из ${rows.size}",
        )
        println(
            "  лучше удержания ${breakout.count { it.totalReturnPercent > it.buyHoldReturnPercent }} из ${rows.size}, " +
                "лучше исходной ${breakout.count { it.totalReturnPercent > it.legacyTotalReturnPercent }} из ${rows.size}, " +
                "ср. просадка ${num(breakout.sumOf { it.maxDrawdownPercent } / rows.size)}%",
        )
        println(
            "  сумма итогов: пробой ${num(breakout.sumOf { it.totalReturnPercent })}%, " +
                "объём ${num(rows.sumOf { it.sizing.totalReturnPercent })}%, " +
                "запрет ${num(rows.sumOf { it.blocking.totalReturnPercent })}%, " +
                "исходная ${num(breakout.sumOf { it.legacyTotalReturnPercent })}%, " +
                "удержание ${num(breakout.sumOf { it.buyHoldReturnPercent })}%",
        )
    }

    private fun num(value: Double): String = String.format(Locale.US, "%.2f", value)

    /** CSV: open,high,low,close,volume,begin — ровно то, что отдают источники. */
    private fun readCandles(file: File): List<Candle> = file.readLines()
        .drop(1)
        .mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size < 6) return@mapNotNull null
            val open = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val high = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val low = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            val close = parts[3].toDoubleOrNull() ?: return@mapNotNull null
            if (close <= 0 || high <= 0 || low <= 0) return@mapNotNull null
            Candle(
                open = open.toQuotation(),
                high = high.toQuotation(),
                low = low.toQuotation(),
                close = close.toQuotation(),
                volume = parts[4].substringBefore('.'),
                time = parts[5].trim(),
            )
        }

    private fun Double.toQuotation(): Quotation {
        val units = floor(this).toLong()
        return Quotation(units = units.toString(), nano = ((this - units) * 1_000_000_000).toInt())
    }
}
