package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import com.tinvesttrader.data.Quotation
import java.io.File
import java.util.Locale
import kotlin.math.floor
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
            ?.associate { it.nameWithoutExtension to readCandles(it) }
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
            val result = PortfolioBacktest.run(data, PortfolioSettings(maxPositions = slots)) ?: return@forEach
            hold = hold ?: result
            println(
                slots.toString().padEnd(9) +
                    result.trades.toString().padStart(8) +
                    num(result.totalReturnPercent).padStart(10) +
                    num(result.maxDrawdownPercent).padStart(9) +
                    num(result.totalReturnPercent / max(1.0, result.maxDrawdownPercent)).padStart(12) +
                    num(result.taxPaidPercent).padStart(8) +
                    num(result.marginPaidPercent).padStart(8) +
                    num(result.averageExposurePercent).padStart(9),
            )
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

        // Тот же набор без налога — чтобы было видно, сколько именно
        // активная торговля отдаёт государству по сравнению с пассивом.
        val slots = minOf(5, data.size)
        val gross = PortfolioBacktest.run(
            data,
            PortfolioSettings(maxPositions = slots, taxRatePercent = 0.0),
        )
        val net = PortfolioBacktest.run(data, PortfolioSettings(maxPositions = slots))
        if (gross != null && net != null) {
            println(
                "Вклад НДФЛ при $slots позициях: без налога ${num(gross.totalReturnPercent)}%, " +
                    "с налогом ${num(net.totalReturnPercent)}% " +
                    "(разница ${num(gross.totalReturnPercent - net.totalReturnPercent)} п.п.)",
            )
        }
    }

    private fun num(value: Double): String = String.format(Locale.US, "%.2f", value)

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
