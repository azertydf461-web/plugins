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
        val dataDir = File(System.getProperty("backtest.data.dir") ?: "build/backtest-data")
        if (!dataDir.isDirectory) {
            println("BACKTEST: каталог с котировками не найден (${dataDir.absolutePath}) — пропуск.")
            return
        }
        val files = dataDir.listFiles { file -> file.extension == "csv" }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            println("BACKTEST: csv с котировками не найдены — пропуск.")
            return
        }

        files.forEach { file ->
            val candles = readCandles(file)
            println()
            println("==== ${file.nameWithoutExtension}: ${candles.size} свечей ====")
            if (candles.size < 200) {
                println("Слишком мало свечей для прогона.")
                return@forEach
            }
            val result = StrategyBacktest.run(
                intervalTitle = file.nameWithoutExtension,
                candles = candles,
                settings = BotBacktestSettings(pollEveryNBars = 1),
            )
            if (result == null) {
                println("Прогон не состоялся: истории не хватило.")
                return@forEach
            }
            printResult(result)
        }
    }

    private fun printResult(result: BotBacktestResult) {
        println("Период: ${result.periodFrom} — ${result.periodTo}, свечей в расчёте ${result.barsTested}")
        println()
        println(row("Показатель", "с фильтрами", "исходный бот"))
        println(row("Сделок", result.tradeCount.toString(), result.legacyTradeCount.toString()))
        println(
            row(
                "Средняя сделка, %",
                fmt(result.expectancyPercent),
                fmt(result.legacyExpectancyPercent),
            ),
        )
        println(
            row(
                "Итог стратегии, %",
                fmt(result.totalReturnPercent),
                fmt(result.legacyTotalReturnPercent),
            ),
        )
        println(
            row(
                "Худшая сделка, %",
                fmt(result.worstTradePercent),
                fmt(result.legacyWorstTradePercent),
            ),
        )
        println(row("Доля прибыльных, %", fmt(result.winRatePercent), "-"))
        println(row("Средняя прибыль, %", fmt(result.averageWinPercent), "-"))
        println(row("Средний убыток, %", fmt(result.averageLossPercent), "-"))
        println(row("Профит-фактор", result.profitFactor?.let(::fmt) ?: "нет убытков", "-"))
        println(row("Макс. просадка, %", fmt(result.maxDrawdownPercent), "-"))
        println(row("Выходов по стопу", result.stopLossExits.toString(), "-"))
        println(row("Среднее удержание, свечей", fmt(result.averageBarsHeld), "-"))
        println(row("Издержки на сделку, %", fmt(result.costPerTradePercent), "-"))
        println()
        println("Купить и держать за тот же период: ${fmt(result.buyHoldReturnPercent)}%")
        println("Вывод: ${result.verdict}")
    }

    private fun row(name: String, now: String, before: String): String =
        name.padEnd(28) + now.padStart(14) + before.padStart(16)

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

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
