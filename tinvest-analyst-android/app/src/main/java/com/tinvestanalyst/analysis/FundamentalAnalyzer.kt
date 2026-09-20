package com.tinvestanalyst.analysis

import com.tinvestanalyst.data.AssetFundamental

/**
 * Разбор отчётности эмитента: оценка, рентабельность, долговая нагрузка и
 * рост. Показатели приходят не по всем бумагам — отсутствующие honestly
 * пропускаются, а не подменяются нулями, иначе компания без данных выглядела
 * бы как компания с нулевым долгом.
 */
object FundamentalAnalyzer {

    fun analyze(data: AssetFundamental?): List<AnalysisFactor> {
        if (data == null) return emptyList()
        return buildList {
            valuationFactor(data)?.let(::add)
            profitabilityFactor(data)?.let(::add)
            debtFactor(data)?.let(::add)
            growthFactor(data)?.let(::add)
        }
    }

    private fun valuationFactor(data: AssetFundamental): AnalysisFactor? {
        val pe = data.peRatioTtm?.takeIf { it > 0 }
        val pb = data.priceToBookTtm?.takeIf { it > 0 }
        val evEbitda = data.evToEbitdaMrq?.takeIf { it > 0 }
        if (pe == null && pb == null && evEbitda == null) return null

        var score = 0
        val notes = mutableListOf<String>()

        pe?.let {
            when {
                it < 6 -> { score += 1; notes += "P/E ${fmt(it)} — дёшево" }
                it < 12 -> notes += "P/E ${fmt(it)} — умеренно"
                it > 25 -> { score -= 1; notes += "P/E ${fmt(it)} — дорого" }
                else -> notes += "P/E ${fmt(it)} — около рынка"
            }
        }
        pb?.let {
            when {
                it < 1 -> { score += 1; notes += "P/B ${fmt(it)} — ниже балансовой стоимости" }
                it > 3 -> { score -= 1; notes += "P/B ${fmt(it)} — высокая премия к балансу" }
                else -> notes += "P/B ${fmt(it)}"
            }
        }
        evEbitda?.let {
            when {
                it < 4 -> { score += 1; notes += "EV/EBITDA ${fmt(it)} — низкая оценка" }
                it > 10 -> { score -= 1; notes += "EV/EBITDA ${fmt(it)} — высокая оценка" }
                else -> notes += "EV/EBITDA ${fmt(it)}"
            }
        }

        val bounded = score.coerceIn(-2, 2)
        return AnalysisFactor(
            name = "Оценка компании",
            score = bounded,
            weight = 2,
            reading = notes.joinToString(", "),
            interpretation = when {
                bounded > 0 -> "Бумага оценена дёшево относительно прибыли и активов."
                bounded < 0 -> "Бумага оценена дорого: платить приходится с премией."
                else -> "Оценка близка к среднерыночной."
            },
        )
    }

    private fun profitabilityFactor(data: AssetFundamental): AnalysisFactor? {
        val roe = data.roe
        val netMargin = data.netMarginMrq
        if (roe == null && netMargin == null) return null

        var score = 0
        val notes = mutableListOf<String>()

        roe?.let {
            val percent = toPercent(it)
            when {
                percent > 20 -> { score += 1; notes += "ROE ${fmt(percent)}% — высокая отдача на капитал" }
                percent < 5 -> { score -= 1; notes += "ROE ${fmt(percent)}% — капитал работает слабо" }
                else -> notes += "ROE ${fmt(percent)}%"
            }
        }
        netMargin?.let {
            val percent = toPercent(it)
            when {
                percent > 15 -> { score += 1; notes += "чистая маржа ${fmt(percent)}%" }
                percent < 0 -> { score -= 1; notes += "чистая маржа отрицательная (${fmt(percent)}%)" }
                else -> notes += "чистая маржа ${fmt(percent)}%"
            }
        }

        val bounded = score.coerceIn(-2, 2)
        return AnalysisFactor(
            name = "Рентабельность",
            score = bounded,
            weight = 2,
            reading = notes.joinToString(", "),
            interpretation = when {
                bounded > 0 -> "Бизнес прибыльный и эффективно использует капитал."
                bounded < 0 -> "Прибыльность слабая — запаса прочности у бизнеса мало."
                else -> "Рентабельность средняя для рынка."
            },
        )
    }

    private fun debtFactor(data: AssetFundamental): AnalysisFactor? {
        val netDebtEbitda = data.netDebtToEbitda ?: data.totalDebtToEbitdaMrq
        val debtEquity = data.totalDebtToEquityMrq
        if (netDebtEbitda == null && debtEquity == null) return null

        var score = 0
        val notes = mutableListOf<String>()

        netDebtEbitda?.let {
            when {
                it < 1 -> { score += 1; notes += "чистый долг/EBITDA ${fmt(it)} — долговая нагрузка низкая" }
                it > 3 -> { score -= 2; notes += "чистый долг/EBITDA ${fmt(it)} — нагрузка высокая" }
                it > 2 -> { score -= 1; notes += "чистый долг/EBITDA ${fmt(it)} — нагрузка заметная" }
                else -> notes += "чистый долг/EBITDA ${fmt(it)}"
            }
        }
        debtEquity?.let { notes += "долг/капитал ${fmt(toPercent(it))}%" }

        val bounded = score.coerceIn(-2, 2)
        return AnalysisFactor(
            name = "Долговая нагрузка",
            score = bounded,
            weight = 2,
            reading = notes.joinToString(", "),
            interpretation = when {
                bounded > 0 -> "Долг небольшой — компания устойчива к росту ставок."
                bounded < 0 -> "Долг высокий: при дорогих деньгах это давит на прибыль и дивиденды."
                else -> "Долговая нагрузка в пределах нормы."
            },
        )
    }

    private fun growthFactor(data: AssetFundamental): AnalysisFactor? {
        val growth = data.oneYearAnnualRevenueGrowthRate
            ?: data.threeYearAnnualRevenueGrowthRate
            ?: data.fiveYearAnnualRevenueGrowthRate
            ?: return null
        val percent = toPercent(growth)
        val score = when {
            percent > 15 -> 1
            percent < 0 -> -1
            else -> 0
        }
        return AnalysisFactor(
            name = "Рост выручки",
            score = score,
            weight = 1,
            reading = "рост выручки ${fmt(percent)}% в год",
            interpretation = when {
                score > 0 -> "Выручка растёт быстрее инфляции — бизнес расширяется."
                score < 0 -> "Выручка сокращается — бизнес под давлением."
                else -> "Выручка растёт умеренно."
            },
        )
    }

    /** API отдаёт доли и проценты вперемешку: 0.18 и 18 означают одно и то же. */
    private fun toPercent(value: Double): Double = if (kotlin.math.abs(value) <= 1.5) value * 100 else value
}
