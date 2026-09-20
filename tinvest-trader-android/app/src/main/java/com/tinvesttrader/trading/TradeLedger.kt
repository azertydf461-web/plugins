package com.tinvesttrader.trading

/** Закрытая сделка бота, восстановленная из журнала решений. */
data class ClosedTrade(
    val figi: String,
    val entryMillis: Long,
    val entryPrice: Double,
    val exitMillis: Long,
    val exitPrice: Double,
    val closedByStopLoss: Boolean,
    val resultPercent: Double,
)

data class LedgerStats(
    val closedTrades: Int,
    val winners: Int,
    val losers: Int,
    val stopLossExits: Int,
    val averageResultPercent: Double,
    val bestPercent: Double,
    val worstPercent: Double,
    val totalReturnPercent: Double,
    val openSinceMillis: Long?,
    val openEntryPrice: Double?,
    val blockedCount: Int,
    val errorCount: Int,
)

/**
 * Что у бота получилось на самом деле. Журнал хранит ход рассуждений, но не
 * результат: запись «купил» ничего не говорит о том, чем эта покупка
 * закончилась. Здесь из пар «покупка -> продажа» собираются закрытые сделки —
 * единственная честная оценка работы бота, в отличие от прогона на истории.
 *
 * Считаются только реально отправленные ордера; отклонённые риск-контролем и
 * ошибки в сделки не превращаются, но показываются отдельным счётчиком —
 * бот, который чаще спотыкается, чем торгует, тоже должен быть виден.
 */
object TradeLedger {

    private const val PRICE_LABEL = "Последняя цена"

    fun stats(records: List<DecisionRecord>): LedgerStats {
        val trades = closedTrades(records)
        val chronological = records.sortedBy { it.timestampMillis }
        val open = openPosition(chronological)

        var equity = 1.0
        trades.forEach { equity *= (1 + it.resultPercent / 100) }

        return LedgerStats(
            closedTrades = trades.size,
            winners = trades.count { it.resultPercent > 0 },
            losers = trades.count { it.resultPercent <= 0 },
            stopLossExits = trades.count { it.closedByStopLoss },
            averageResultPercent = if (trades.isEmpty()) 0.0 else trades.sumOf { it.resultPercent } / trades.size,
            bestPercent = trades.maxOfOrNull { it.resultPercent } ?: 0.0,
            worstPercent = trades.minOfOrNull { it.resultPercent } ?: 0.0,
            totalReturnPercent = (equity - 1) * 100,
            openSinceMillis = open?.timestampMillis,
            openEntryPrice = open?.let(::priceOf),
            blockedCount = records.count { it.decisionAction == DecisionAction.BLOCKED },
            errorCount = records.count { it.decisionAction == DecisionAction.ERROR },
        )
    }

    fun closedTrades(records: List<DecisionRecord>): List<ClosedTrade> {
        val executed = records
            .sortedBy { it.timestampMillis }
            .filter { it.executionNote != null && it.decisionAction in EXECUTING_ACTIONS }

        val trades = mutableListOf<ClosedTrade>()
        var entry: DecisionRecord? = null

        executed.forEach { record ->
            val price = priceOf(record) ?: return@forEach
            when (record.decisionAction) {
                DecisionAction.BUY -> if (entry == null) entry = record
                DecisionAction.SELL, DecisionAction.STOP_LOSS -> {
                    val opened = entry ?: return@forEach
                    val entryPrice = priceOf(opened) ?: return@forEach
                    if (entryPrice > 0) {
                        trades += ClosedTrade(
                            figi = record.figi,
                            entryMillis = opened.timestampMillis,
                            entryPrice = entryPrice,
                            exitMillis = record.timestampMillis,
                            exitPrice = price,
                            closedByStopLoss = record.decisionAction == DecisionAction.STOP_LOSS,
                            resultPercent = (price - entryPrice) / entryPrice * 100,
                        )
                    }
                    entry = null
                }
                else -> Unit
            }
        }
        return trades
    }

    /** Покупка, для которой закрытия в журнале ещё нет. */
    private fun openPosition(chronological: List<DecisionRecord>): DecisionRecord? {
        var open: DecisionRecord? = null
        chronological
            .filter { it.executionNote != null && it.decisionAction in EXECUTING_ACTIONS }
            .forEach { record ->
                when (record.decisionAction) {
                    DecisionAction.BUY -> if (open == null) open = record
                    DecisionAction.SELL, DecisionAction.STOP_LOSS -> open = null
                    else -> Unit
                }
            }
        return open
    }

    /**
     * Цена берётся из показаний индикаторов на момент решения, а не из отчёта
     * брокера об исполнении: реальной цены исполнения журнал не хранит,
     * поэтому результат здесь — оценка без комиссии и проскальзывания.
     */
    private fun priceOf(record: DecisionRecord): Double? =
        record.indicators.firstOrNull { it.label == PRICE_LABEL }
            ?.value
            ?.replace(',', '.')
            ?.filter { it.isDigit() || it == '.' || it == '-' }
            ?.toDoubleOrNull()

    private val EXECUTING_ACTIONS = setOf(
        DecisionAction.BUY,
        DecisionAction.SELL,
        DecisionAction.STOP_LOSS,
    )
}
