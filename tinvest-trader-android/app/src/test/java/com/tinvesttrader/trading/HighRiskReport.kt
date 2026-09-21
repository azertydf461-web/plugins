package com.tinvesttrader.trading

import java.io.File
import java.util.Locale
import kotlin.math.max
import org.junit.Test

/**
 * Инструменты «с повышенным риском», к которым счёт получил доступ после
 * тестирования: фьючерсы, акции вне котировальных списков, иностранные акции
 * и ETF, маржинальная торговля. Тот же пробой канала, те же правила, но
 * издержки — свои у каждого класса по тарифу «Инвестор»:
 *
 *  - фьючерсы: 0,1 % от стоимости контракта за сторону, спред узкий;
 *  - акции вне списков: 0,3 % и широкий спред — ликвидности там мало;
 *  - иностранные: 0,3 % и обычный спред.
 *
 * Плечо считается поверх каждого класса: результат сделки удваивается, а за
 * заёмную половину начисляется плата за перенос по таблице тарифа
 * (около 28 % годовых) за каждый день удержания.
 */
class HighRiskReport {

    private class ClassCosts(val title: String, val commission: Double, val spread: Double)

    private val classes = linkedMapOf(
        "fyuchers" to ClassCosts("Фьючерсы Мосбиржи (склеенные)", 0.1, 0.02),
        "eshelon" to ClassCosts("Акции вне котировальных списков", 0.3, 0.5),
        "mir-akcii" to ClassCosts("Иностранные акции", 0.3, 0.1),
        "mir-etf" to ClassCosts("Иностранные ETF", 0.3, 0.1),
    )
    private val marginRatePercent = 28.0
    private val leverage = 2.0

    @Test
    fun printReport() {
        val dir = File(System.getenv("HIGHRISK_DATA_DIR") ?: "app/build/highrisk-data")
        val files = dir.listFiles { file -> file.extension == "csv" }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            println("Высокорисковые: данных нет (${dir.absolutePath}).")
            return
        }
        classes.forEach { (prefix, costs) ->
            val own = files.filter { it.nameWithoutExtension.startsWith("${prefix}__") }
            if (own.isEmpty()) return@forEach
            println()
            println("==== ${costs.title}: комиссия ${num(costs.commission)}%, спред ${num(costs.spread)}% ====")
            println(
                "инструмент".padEnd(18) + "сделок".padStart(8) + "выигр".padStart(7) +
                    "на сделку".padStart(11) + "итог".padStart(10) + "удерж".padStart(10) +
                    "просад".padStart(8) + "плечо 2x".padStart(10) + "с платой".padStart(10),
            )
            var positive = 0
            var beatsHold = 0
            var leveragedPositive = 0
            val expectancies = mutableListOf<Double>()
            var sumTotal = 0.0
            var sumHold = 0.0
            var sumLeveraged = 0.0
            own.forEach { file ->
                val candles = readCandlesCsv(file)
                if (candles.size < 400) {
                    println("${file.nameWithoutExtension}: только ${candles.size} свечей — пропуск")
                    return@forEach
                }
                val result = StrategyBacktest.run(
                    file.nameWithoutExtension,
                    candles,
                    BotBacktestSettings(
                        commissionPercent = costs.commission,
                        spreadPercent = costs.spread,
                        pollEveryNBars = 1,
                        donchian = true,
                    ),
                ) ?: return@forEach

                // Плечо 2x: каждая сделка удваивается, заёмная половина платит
                // за перенос по дням удержания. Итог — произведение сделок.
                var leveragedGross = 1.0
                var leveragedNet = 1.0
                result.trades.forEach { trade ->
                    val doubled = trade.resultPercent * leverage
                    val financing = marginRatePercent * (trade.barsHeld / 252.0) * (leverage - 1)
                    leveragedGross *= 1 + doubled / 100
                    leveragedNet *= 1 + (doubled - financing) / 100
                }
                val leveragedGrossPercent = (leveragedGross - 1) * 100
                val leveragedNetPercent = (leveragedNet - 1) * 100

                val name = file.nameWithoutExtension.substringAfter("__")
                println(
                    name.padEnd(18) + result.tradeCount.toString().padStart(8) +
                        num(result.winRatePercent).padStart(7) +
                        num(result.expectancyPercent).padStart(11) +
                        num(result.totalReturnPercent).padStart(10) +
                        num(result.buyHoldReturnPercent).padStart(10) +
                        num(result.maxDrawdownPercent).padStart(8) +
                        num(leveragedGrossPercent).padStart(10) +
                        num(leveragedNetPercent).padStart(10),
                )
                if (result.totalReturnPercent > 0) positive++
                if (result.totalReturnPercent > result.buyHoldReturnPercent) beatsHold++
                if (leveragedNetPercent > 0) leveragedPositive++
                expectancies += result.expectancyPercent
                sumTotal += result.totalReturnPercent
                sumHold += result.buyHoldReturnPercent
                sumLeveraged += leveragedNetPercent
            }
            if (expectancies.isNotEmpty()) {
                println(
                    "итог по классу: инструментов ${expectancies.size}, ср. сделка ${num(expectancies.average())}%, " +
                        "в плюсе $positive, лучше удержания $beatsHold, с плечом и платой в плюсе $leveragedPositive",
                )
                println(
                    "  сумма итогов: пробой ${num(sumTotal)}%, удержание ${num(sumHold)}%, " +
                        "плечо 2x с платой ${num(sumLeveraged)}%",
                )
            }
        }
    }

    private fun num(value: Double): String = String.format(Locale.US, "%.2f", value)
}
