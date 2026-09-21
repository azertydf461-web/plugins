package com.tinvesttrader.trading

import java.io.File
import java.util.Locale
import kotlin.math.max
import org.junit.Test

/**
 * Проверка на отложенной выборке.
 *
 * Шесть конфигураций подряд подбирались на одних и тех же данных, и этого
 * достаточно, чтобы найти «работающую» систему случайно. Здесь та же логика
 * без единой правки прогоняется по данным, которые в подборе не участвовали:
 * по инструментам, которых в прошлых прогонах не было, и по более раннему
 * десятилетию тех же индексов.
 *
 * Издержки считаются полностью: комиссия и спред на обеих сторонах, НДФЛ с
 * каждой прибыльной сделки, ежегодная ребалансировка эталона с её
 * собственными издержками и стоимость переноса непокрытой позиции. Дивиденды
 * и купоны входят в цену там, где инструмент — индекс полной доходности;
 * какие именно, видно по именам в отчёте.
 */
class OutOfSampleReport {

    @Test
    fun printReport() {
        val root = File(System.getenv("OOS_DATA_DIR") ?: "app/build/oos-data")
        val datasets = root.listFiles { file -> file.isDirectory }?.sortedBy { it.name }
        if (datasets.isNullOrEmpty()) {
            println("OOS: наборы данных не найдены (${root.absolutePath}) — пропуск.")
            return
        }
        datasets.forEach { dataset -> report(dataset) }
    }

    private fun report(dataset: File) {
        val data = dataset.listFiles { file -> file.extension == "csv" }
            ?.associate { it.nameWithoutExtension to readCandlesCsv(it) }
            ?.filterValues { it.size >= 400 }
            .orEmpty()

        println()
        println("================ ${dataset.name} ================")
        if (data.size < 3) {
            println("Инструментов с достаточной историей: ${data.size} — прогон невозможен.")
            return
        }
        println("Инструментов: ${data.size} — ${data.keys.sorted().joinToString(", ")}")

        println(
            "позиций".padEnd(9) + "сделок".padStart(8) + "итог".padStart(10) +
                "просад".padStart(9) + "итог/просад".padStart(12) +
                "налог".padStart(8) + "плечо".padStart(8) + "в рынке".padStart(9),
        )

        var hold: PortfolioResult? = null
        listOf(3, 5, 10).forEach { slots ->
            if (slots > data.size) return@forEach
            val result = PortfolioBacktest.run(
                data,
                // Эталон считаем и с льготой за срок владения, и без неё: ставка
                // должна дойти до расчёта, иначе обе строки в отчёте совпадут.
                PortfolioSettings(maxPositions = slots, buyHoldTaxRatePercent = 13.0),
            ) ?: return@forEach
            hold = hold ?: result
            println(row(slots.toString(), result))
        }

        // То же самое на счёте, освобождённом от налога на доход. Налог —
        // самая крупная издержка активной торговли, и без него сравнение с
        // пассивом становится другим: это отдельный прогон, а не поправка к
        // предыдущему, потому что налог меняет размер позиции на всём пути.
        println("без НДФЛ — счёт с освобождением от налога:")
        listOf(3, 5, 10).forEach { slots ->
            if (slots > data.size) return@forEach
            val result = PortfolioBacktest.run(
                data,
                PortfolioSettings(
                    maxPositions = slots,
                    taxRatePercent = 0.0,
                    buyHoldTaxRatePercent = 13.0,
                ),
            ) ?: return@forEach
            println(row(slots.toString(), result))
        }

        // То же правило, что и без налога: отдельная симуляция, а не поправка.
        // Вход на растущем рынке отключён — там система не зарабатывает.
        println("без входа на растущем рынке:")
        listOf(3, 5, 10).forEach { slots ->
            if (slots > data.size) return@forEach
            val result = PortfolioBacktest.run(
                data,
                PortfolioSettings(
                    maxPositions = slots,
                    buyHoldTaxRatePercent = 13.0,
                    skipRisingMarket = true,
                ),
            ) ?: return@forEach
            println(row(slots.toString(), result))
        }

        println("без входа на растущем рынке и без НДФЛ:")
        listOf(3, 5, 10).forEach { slots ->
            if (slots > data.size) return@forEach
            val result = PortfolioBacktest.run(
                data,
                PortfolioSettings(
                    maxPositions = slots,
                    taxRatePercent = 0.0,
                    buyHoldTaxRatePercent = 13.0,
                    skipRisingMarket = true,
                ),
            ) ?: return@forEach
            println(row(slots.toString(), result))
        }

        hold?.let {
            println(
                "Купить и держать (ребалансировка раз в год, льгота за срок владения): " +
                    "${num(it.buyHoldReturnPercent)}% при просадке ${num(it.buyHoldMaxDrawdownPercent)}%, " +
                    "итог/просадка ${num(it.buyHoldReturnPercent / max(1.0, it.buyHoldMaxDrawdownPercent))}",
            )
            println("То же с НДФЛ, если льготы нет: ${num(it.buyHoldAfterTaxReturnPercent)}%")
            println("Торговых дней: ${it.days}")
        }
    }

    private fun row(label: String, result: PortfolioResult): String =
        label.padEnd(9) +
            result.trades.toString().padStart(8) +
            num(result.totalReturnPercent).padStart(10) +
            num(result.maxDrawdownPercent).padStart(9) +
            num(result.totalReturnPercent / max(1.0, result.maxDrawdownPercent)).padStart(12) +
            num(result.taxPaidPercent).padStart(8) +
            num(result.marginPaidPercent).padStart(8) +
            num(result.averageExposurePercent).padStart(9)

    private fun num(value: Double): String = String.format(Locale.US, "%.2f", value)


}
