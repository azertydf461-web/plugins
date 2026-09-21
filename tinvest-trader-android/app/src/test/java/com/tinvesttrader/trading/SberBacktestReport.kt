package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import com.tinvesttrader.data.Quotation
import java.io.File
import kotlin.math.floor
import org.junit.Test

/**
 * Прогон стратегии по настоящим котировкам, а не по выдуманным числам.
 *
 * Свечи кладёт рядом workflow (данные Московской биржи), тест читает файл и
 * гоняет ТУ ЖЕ логику, которой торгует приложение: никакой отдельной
 * «модельной» копии, иначе проверялась бы не та программа. Отчёт печатается
 * в лог сборки.
 *
 * Если файла с котировками нет, тест молча проходит: обычная сборка не должна
 * падать из-за отсутствия исследовательских данных.
 */
class SberBacktestReport {

    @Test
    fun printReport() {
        // Переменная окружения, а не системное свойство: свойства уходят в JVM
        // самого Gradle и до тестовой JVM не доезжают.
        val dataDir = File(System.getenv("BACKTEST_DATA_DIR") ?: "app/build/backtest-data")
        if (!dataDir.isDirectory) {
            println("BACKTEST: каталог с котировками не найден (${dataDir.absolutePath}) — пропуск.")
            return
        }
        val files = dataDir.listFiles { file -> file.extension == "csv" }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            println("BACKTEST: csv с котировками не найдены — пропуск.")
            return
        }

        val blocking = mutableListOf<BotBacktestResult>()
        val sizing = mutableListOf<BotBacktestResult>()

        files.forEach { file ->
            val candles = readCandles(file)
            println()
            println("==== ${file.nameWithoutExtension}: ${candles.size} свечей ====")
            if (candles.size < 200) {
                println("Слишком мало свечей для прогона.")
                return@forEach
            }
            // Две конфигурации на одних и тех же свечах: фильтры запрещают
            // вход против фильтров, режущих объём. Исходная логика приходит
            // третьей колонкой внутри каждого результата.
            val block = StrategyBacktest.run(
                intervalTitle = file.nameWithoutExtension,
                candles = candles,
                settings = BotBacktestSettings(pollEveryNBars = 1),
            )
            val size = StrategyBacktest.run(
                intervalTitle = file.nameWithoutExtension,
                candles = candles,
                settings = BotBacktestSettings(pollEveryNBars = 1, sizingMode = true),
            )
            if (block == null || size == null) {
                println("Прогон не состоялся: истории не хватило.")
                return@forEach
            }
            printResult(size)
            blocking += block
            sizing += size
        }

        printComparison(blocking, sizing)
    }

    /**
     * Сравнение трёх стратегий на одних и тех же свечах. Вопрос эксперимента
     * ровно один: дороже ли обходится отменённый вход, чем уменьшенный.
     */
    private fun printComparison(
        blocking: List<BotBacktestResult>,
        sizing: List<BotBacktestResult>,
    ) {
        if (sizing.isEmpty()) return
        println()
        println("============ СВОДКА: объём против запрета ============")
        println(
            "бумага".padEnd(22) + "сдел".padStart(6) + "ОБЪЁМ".padStart(9) +
                "запрет".padStart(9) + "исходн".padStart(9) + "куп-держ".padStart(10) +
                "ср.сд".padStart(8) + "просад".padStart(8),
        )
        sizing.forEachIndexed { index, r ->
            val b = blocking[index]
            println(
                r.intervalTitle.padEnd(22) +
                    r.tradeCount.toString().padStart(6) +
                    num(r.totalReturnPercent).padStart(9) +
                    num(b.totalReturnPercent).padStart(9) +
                    num(r.legacyTotalReturnPercent).padStart(9) +
                    num(r.buyHoldReturnPercent).padStart(10) +
                    num(r.expectancyPercent).padStart(8) +
                    num(r.maxDrawdownPercent).padStart(8),
            )
        }

        val sizeBeatsBlock = sizing.indices.count {
            sizing[it].totalReturnPercent > blocking[it].totalReturnPercent
        }
        val sizeBeatsLegacy = sizing.count { it.totalReturnPercent > it.legacyTotalReturnPercent }
        val sizeBeatsHold = sizing.count { it.totalReturnPercent > it.buyHoldReturnPercent }
        val positive = sizing.count { it.expectancyPercent > 0 }
        println()
        println("Прогонов: ${sizing.size}, сделок всего: ${sizing.sumOf { it.tradeCount }}")
        println("Объём лучше запрета: $sizeBeatsBlock из ${sizing.size}")
        println("Объём лучше исходной логики: $sizeBeatsLegacy из ${sizing.size}")
        println("Объём лучше «купить и держать»: $sizeBeatsHold из ${sizing.size}")
        println("Положительное матожидание: $positive из ${sizing.size}")
        println(
            "Среднее матожидание: объём ${num(sizing.sumOf { it.expectancyPercent } / sizing.size)}%, " +
                "запрет ${num(blocking.sumOf { it.expectancyPercent } / blocking.size)}%",
        )
        println(
            "Средняя просадка: объём ${num(sizing.sumOf { it.maxDrawdownPercent } / sizing.size)}%, " +
                "запрет ${num(blocking.sumOf { it.maxDrawdownPercent } / blocking.size)}%",
        )
    }

    @Suppress("unused")
    private fun printSummary(results: List<BotBacktestResult>) {
        if (results.isEmpty()) return
        println()
        println("============ СВОДКА ============")
        println(
            "бумага".padEnd(22) + "сдел".padStart(6) + "ср.сд".padStart(8) +
                "итог".padStart(9) + "исходн".padStart(9) + "купил-держал".padStart(14) +
                "ПФ".padStart(7) + "просад".padStart(8),
        )
        results.forEach { r ->
            println(
                r.intervalTitle.padEnd(22) +
                    r.tradeCount.toString().padStart(6) +
                    num(r.expectancyPercent).padStart(8) +
                    num(r.totalReturnPercent).padStart(9) +
                    num(r.legacyTotalReturnPercent).padStart(9) +
                    num(r.buyHoldReturnPercent).padStart(14) +
                    (r.profitFactor?.let { num(it) } ?: "-").padStart(7) +
                    num(r.maxDrawdownPercent).padStart(8),
            )
        }

        // Итоги по всему набору: одна бумага ничего не доказывает, а вот
        // доля выигранных сравнений и суммарный счёт — уже свидетельство.
        val filtersBeatLegacy = results.count { it.totalReturnPercent > it.legacyTotalReturnPercent }
        val beatBuyHold = results.count { it.totalReturnPercent > it.buyHoldReturnPercent }
        val profitable = results.count { it.expectancyPercent > 0 }
        val totalTrades = results.sumOf { it.tradeCount }
        val avgExpectancy = results.sumOf { it.expectancyPercent } / results.size
        println()
        println("Прогонов: ${results.size}, сделок всего: $totalTrades")
        println("Фильтры лучше исходной логики: $filtersBeatLegacy из ${results.size}")
        println("Лучше «купить и держать»: $beatBuyHold из ${results.size}")
        println("Положительное матожидание: $profitable из ${results.size}")
        println("Среднее матожидание по всем прогонам: ${num(avgExpectancy)}%")
    }

    private fun printResult(result: BotBacktestResult) {
        println("Период: ${result.periodFrom} — ${result.periodTo}, свечей в расчёте ${result.barsTested}")
        println()
        println(row("Показатель", "с фильтрами", "исходный бот"))
        println(row("Сделок", result.tradeCount.toString(), result.legacyTradeCount.toString()))
        println(
            row(
                "Средняя сделка, %",
                num(result.expectancyPercent),
                num(result.legacyExpectancyPercent),
            ),
        )
        println(
            row(
                "Итог стратегии, %",
                num(result.totalReturnPercent),
                num(result.legacyTotalReturnPercent),
            ),
        )
        println(
            row(
                "Худшая сделка, %",
                num(result.worstTradePercent),
                num(result.legacyWorstTradePercent),
            ),
        )
        println(row("Доля прибыльных, %", num(result.winRatePercent), "-"))
        println(row("Средняя прибыль, %", num(result.averageWinPercent), "-"))
        println(row("Средний убыток, %", num(result.averageLossPercent), "-"))
        println(row("Профит-фактор", result.profitFactor?.let(::fmt) ?: "нет убытков", "-"))
        println(row("Макс. просадка, %", num(result.maxDrawdownPercent), "-"))
        println(row("Выходов по стопу", result.stopLossExits.toString(), "-"))
        val held = result.trades.map { it.barsHeld }
        println(
            row(
                "Удержание, свечей",
                if (held.isEmpty()) "-" else "${held.min()}..${held.max()}",
                "-",
            ),
        )
        println(row("Издержки на сделку, %", num(result.costPerTradePercent), "-"))
        println()
        println("Купить и держать за тот же период: ${num(result.buyHoldReturnPercent)}%")
        println("Вывод: ${result.verdict}")
    }

    private fun row(name: String, now: String, before: String): String =
        name.padEnd(28) + now.padStart(14) + before.padStart(16)

    private fun num(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

    /** CSV: open,high,low,close,volume,begin — ровно то, что отдаёт биржа. */
    private fun readCandles(file: File): List<Candle> = file.readLines()
        .drop(1)
        .mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size < 6) return@mapNotNull null
            val open = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val high = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val low = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            val close = parts[3].toDoubleOrNull() ?: return@mapNotNull null
            if (close <= 0) return@mapNotNull null
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
