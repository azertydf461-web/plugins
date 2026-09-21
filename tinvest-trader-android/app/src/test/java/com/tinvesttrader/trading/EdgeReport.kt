package com.tinvesttrader.trading

import java.io.File
import java.util.Locale
import kotlin.math.max
import org.junit.Test

/**
 * Поиск другого источника края.
 *
 * Пробой канала на дневных свечах проверен до конца: край есть, но меньше
 * издержек и пассива, и никакой фильтр входа его не увеличивает. Здесь
 * проверяются два других источника:
 *
 *  - внутридневная частота: те же пробой и возврат к среднему на часовых и
 *    десятиминутных свечах, с закрытием дня и без; при двух тарифах, потому
 *    что внутри дня именно комиссия решает, жив ли край;
 *  - спреды: отношение цен обыкновенной и привилегированной акции одного
 *    эмитента и пары бумаг одного сектора — ставка на возврат к норме, не
 *    зависящая от направления рынка.
 *
 * Каждая строка — отдельный честный прогон с теми же издержками, что и раньше.
 */
class EdgeReport {

    private val lowFee = 0.05
    private val highFee = 0.30

    @Test
    fun printReport() {
        val root = File(System.getenv("EDGE_DATA_DIR") ?: "app/build/edge-data")
        intraday(File(root, "intraday"))
        pairs(File(root, "pairs"))
    }

    private fun intraday(dir: File) {
        val files = dir.listFiles { file -> file.extension == "csv" }?.sortedBy { it.name }
        println()
        println("================ Внутридневные свечи ================")
        if (files.isNullOrEmpty()) {
            println("Данных нет (${dir.absolutePath}).")
            return
        }
        println(
            "инструмент".padEnd(22) + "подход".padEnd(22) + "комис".padStart(6) + "сделок".padStart(8) +
                "выигр".padStart(7) + "на сделку".padStart(11) + "итог".padStart(9) +
                "удерж".padStart(9) + "просад".padStart(8),
        )

        data class Setup(val title: String, val settings: BotBacktestSettings)

        val totals = linkedMapOf<String, MutableList<Double>>()
        files.forEach { file ->
            val candles = readCandlesCsv(file)
            if (candles.size < 600) {
                println("${file.nameWithoutExtension}: только ${candles.size} свечей — пропуск")
                return@forEach
            }
            val interval = file.nameWithoutExtension.substringAfter("__")
            listOf(lowFee, highFee).forEach { fee ->
                val base = BotBacktestSettings(
                    commissionPercent = fee,
                    pollEveryNBars = 1,
                    windowBars = 60,
                    donchian = true,
                )
                val setups = listOf(
                    Setup("пробой, с ночью", base),
                    Setup("пробой, без ночи", base.copy(flatAtSessionEnd = true)),
                    Setup(
                        "возврат, без ночи",
                        base.copy(
                            strategy = MeanReversionStrategy(),
                            flatAtSessionEnd = true,
                            trailingStop = false,
                        ),
                    ),
                )
                setups.forEach { setup ->
                    val result = StrategyBacktest.run(file.nameWithoutExtension, candles, setup.settings)
                        ?: return@forEach
                    println(
                        file.nameWithoutExtension.padEnd(22) + setup.title.padEnd(22) +
                            num(fee).padStart(6) + result.tradeCount.toString().padStart(8) +
                            num(result.winRatePercent).padStart(7) +
                            num(result.expectancyPercent).padStart(11) +
                            num(result.totalReturnPercent).padStart(9) +
                            num(result.buyHoldReturnPercent).padStart(9) +
                            num(result.maxDrawdownPercent).padStart(8),
                    )
                    totals.getOrPut("$interval / ${setup.title} / комиссия ${num(fee)}") { mutableListOf() } +=
                        result.totalReturnPercent
                }
            }
        }

        println()
        println("--- сводка по подходам: сумма итогов и сколько инструментов в плюсе ---")
        totals.forEach { (key, values) ->
            println(
                key.padEnd(48) + "итог ${num(values.sum())}%".padStart(16) +
                    "  в плюсе ${values.count { it > 0 }} из ${values.size}",
            )
        }
    }

    private fun pairs(dir: File) {
        println()
        println("================ Спреды ================")
        val candidates = listOf(
            // Обыкновенная / привилегированная одного эмитента: связь структурная.
            "SBER" to "SBERP", "TATN" to "TATNP", "SNGS" to "SNGSP", "RTKM" to "RTKMP",
            "BANE" to "BANEP", "MTLR" to "MTLRP", "NKNC" to "NKNCP", "LSNG" to "LSNGP",
            // Один сектор: связь статистическая, слабее.
            "LKOH" to "ROSN", "NLMK" to "MAGN", "NLMK" to "CHMF", "GAZP" to "NVTK", "SBER" to "VTBR",
        )
        val loaded = candidates.mapNotNull { (a, b) ->
            val fa = File(dir, "$a.csv")
            val fb = File(dir, "$b.csv")
            if (!fa.exists() || !fb.exists()) return@mapNotNull null
            Triple("$a/$b", readCandlesCsv(fa), readCandlesCsv(fb))
        }
        if (loaded.isEmpty()) {
            println("Данных нет (${dir.absolutePath}).")
            return
        }
        println(
            "пара".padEnd(12) + "дней".padStart(6) + "сделок".padStart(8) + "выигр".padStart(7) +
                "на сделку".padStart(11) + "итог".padStart(9) + "просад".padStart(8) +
                "держим".padStart(8) + "стопов".padStart(8) + "по сроку".padStart(9) + "шорт".padStart(7),
        )
        val results = loaded.mapNotNull { (name, a, b) ->
            PairSpreadBacktest.run(name, a, b, PairSettings())
        }
        results.forEach { r ->
            println(
                r.pair.padEnd(12) + r.days.toString().padStart(6) + r.trades.toString().padStart(8) +
                    num(r.winRatePercent).padStart(7) + num(r.expectancyPercent).padStart(11) +
                    num(r.totalReturnPercent).padStart(9) + num(r.maxDrawdownPercent).padStart(8) +
                    num(r.averageHoldDays).padStart(8) + r.stops.toString().padStart(8) +
                    r.timeExits.toString().padStart(9) + num(r.shortCostPercent).padStart(7),
            )
        }
        if (results.isNotEmpty()) {
            val allTrades = results.sumOf { it.trades }
            val weighted = results.sumOf { it.expectancyPercent * it.trades } / max(1, allTrades)
            println(
                "итого: пар ${results.size}, сделок $allTrades, на сделку ${num(weighted)}%, " +
                    "в плюсе ${results.count { it.totalReturnPercent > 0 }} из ${results.size}, " +
                    "сумма итогов ${num(results.sumOf { it.totalReturnPercent })}%",
            )
        }

        // Чувствительность к плате за шорт: без неё и при удвоенной.
        println()
        println("--- те же пары без платы за шорт / при 40% годовых ---")
        listOf(0.0, 40.0).forEach { rate ->
            val rows = loaded.mapNotNull { (name, a, b) ->
                PairSpreadBacktest.run(name, a, b, PairSettings(shortRatePercent = rate))
            }
            println(
                "шорт ${num(rate)}%: в плюсе ${rows.count { it.totalReturnPercent > 0 }} из ${rows.size}, " +
                    "сумма итогов ${num(rows.sumOf { it.totalReturnPercent })}%",
            )
        }
    }

    private fun num(value: Double): String = String.format(Locale.US, "%.2f", value)
}
