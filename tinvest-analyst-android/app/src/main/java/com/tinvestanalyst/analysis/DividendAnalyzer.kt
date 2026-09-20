package com.tinvestanalyst.analysis

import com.tinvestanalyst.data.AssetFundamental
import com.tinvestanalyst.data.AssetReportEvent
import com.tinvestanalyst.data.Dividend
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Ближайшее событие, способное резко двинуть цену независимо от индикаторов. */
data class UpcomingEvent(
    val title: String,
    val date: String,
    val daysAway: Long,
    val detail: String,
)

object DividendAnalyzer {

    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault())

    /**
     * Дивидендный фактор: текущая доходность, регулярность выплат и то,
     * сколько прибыли компания раздаёт. Пустая история — не минус, а просто
     * отсутствие фактора: облигации и растущие компании дивидендов не платят.
     */
    fun analyze(dividends: List<Dividend>, fundamental: AssetFundamental?): AnalysisFactor? {
        val paid = dividends.filter { it.dividendNet.toDouble() > 0 }
        val yieldPercent = fundamental?.dividendYieldDailyTtm?.let { toPercent(it) }
            ?: paid.lastOrNull()?.yieldValue?.toDouble()
        if (paid.isEmpty() && yieldPercent == null) return null

        val payoutYears = paid.mapNotNull { parse(it.paymentDate)?.atZone(ZoneId.systemDefault())?.year }
            .distinct()
            .size

        var score = 0
        val notes = mutableListOf<String>()

        yieldPercent?.let {
            when {
                it >= 10 -> { score += 2; notes += "доходность ${fmt(it)}% годовых" }
                it >= 6 -> { score += 1; notes += "доходность ${fmt(it)}% годовых" }
                it > 0 -> notes += "доходность ${fmt(it)}% годовых"
                else -> Unit
            }
        }

        if (payoutYears >= 3) {
            score += 1
            notes += "выплаты как минимум $payoutYears года подряд"
        } else if (payoutYears == 1) {
            notes += "выплата была лишь в одном году из трёх"
        }

        fundamental?.dividendPayoutRatioFy?.let {
            val payout = toPercent(it)
            if (payout > 100) {
                score -= 1
                notes += "на дивиденды уходит ${fmt(payout)}% прибыли — выплаты выше заработанного"
            } else if (payout > 0) {
                notes += "на дивиденды уходит ${fmt(payout)}% прибыли"
            }
        }

        val bounded = score.coerceIn(-2, 2)
        return AnalysisFactor(
            name = "Дивиденды",
            score = bounded,
            weight = 2,
            reading = notes.joinToString(", ").ifBlank { "выплат в истории нет" },
            interpretation = when {
                bounded >= 2 -> "Высокая и регулярная дивидендная доходность — заметная часть дохода инвестора."
                bounded == 1 -> "Дивиденды есть и выплачиваются стабильно."
                bounded < 0 -> "Выплаты не обеспечены прибылью — есть риск их сокращения."
                else -> "Дивиденды не выделяют бумагу из общего ряда."
            },
        )
    }

    /**
     * Ближайшие даты, до которых техника не имеет значения: отсечка (после
     * неё цена падает на размер дивиденда) и публикация отчётности.
     */
    fun upcomingEvents(dividends: List<Dividend>, reports: List<AssetReportEvent>): List<UpcomingEvent> {
        val now = Instant.now()
        val events = mutableListOf<UpcomingEvent>()

        dividends.forEach { dividend ->
            val cutoff = parse(dividend.lastBuyDate) ?: parse(dividend.recordDate)
            if (cutoff != null && cutoff.isAfter(now)) {
                val days = Duration.between(now, cutoff).toDays()
                if (days <= 120) {
                    events += UpcomingEvent(
                        title = "Дивидендная отсечка",
                        date = dateFormat.format(cutoff),
                        daysAway = days,
                        detail = "Дивиденд ${fmt(dividend.dividendNet.toDouble())} " +
                            "${dividend.dividendNet.currency.uppercase()} на акцию. " +
                            "На следующий день после отсечки цена обычно падает примерно на эту сумму.",
                    )
                }
            }
        }

        reports.forEach { report ->
            val date = parse(report.reportDate)
            if (date != null && date.isAfter(now)) {
                val days = Duration.between(now, date).toDays()
                if (days <= 120) {
                    events += UpcomingEvent(
                        title = "Публикация отчётности",
                        date = dateFormat.format(date),
                        daysAway = days,
                        detail = "Отчёт за ${report.periodNum} период ${report.periodYear} года. " +
                            "В день публикации движения бывают резкими и не предсказываются индикаторами.",
                    )
                }
            }
        }

        return events.sortedBy { it.daysAway }
    }

    private fun parse(value: String): Instant? =
        value.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun toPercent(value: Double): Double = if (kotlin.math.abs(value) <= 1.5) value * 100 else value
}
