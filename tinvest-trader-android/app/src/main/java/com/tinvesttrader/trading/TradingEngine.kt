package com.tinvesttrader.trading

import com.tinvesttrader.data.OrderDirection
import com.tinvesttrader.data.SecureTokenStore
import com.tinvesttrader.data.TInvestRepository

/**
 * Один тик торгового цикла: свежие свечи -> сигнал стратегии -> проверка
 * риск-менеджера -> (опционально) ордер. Каждый тик заканчивается записью в
 * журнал решений, включая тики без действий: пропуск таких записей скрыл бы
 * от пользователя половину логики бота.
 *
 * Вызывается периодически из TradingWorker; сам по себе engine не планирует
 * расписание.
 */
class TradingEngine(
    private val repository: TInvestRepository,
    private val strategy: Strategy,
    private val riskManager: RiskManager,
    private val tokenStore: SecureTokenStore,
    private val journal: DecisionJournal,
    private val lotsPerOrder: Long = 1,
) {

    suspend fun tick(): DecisionRecord {
        val figi = tokenStore.instrumentFigi
        val accountId = tokenStore.accountId
        val mode = if (repository.isLiveMode) "LIVE" else "SANDBOX"

        if (accountId.isNullOrBlank() || figi.isNullOrBlank()) {
            return record(
                figi = figi.orEmpty(),
                action = DecisionAction.ERROR,
                headline = "Бот не настроен",
                marketReasoning = listOf(
                    if (accountId.isNullOrBlank()) "Не указан ID счёта." else "ID счёта задан.",
                    if (figi.isNullOrBlank()) "Не выбран инструмент (FIGI)." else "Инструмент задан.",
                    "Проверка рынка не выполнялась.",
                ),
                indicators = listOf(IndicatorReading("Режим", mode)),
            )
        }

        val candles = try {
            repository.getRecentCandles(figi)
        } catch (e: Exception) {
            return record(
                figi = figi,
                action = DecisionAction.ERROR,
                headline = "Нет данных с биржи",
                marketReasoning = listOf("Запрос свечей не прошёл: ${e.message}"),
                indicators = listOf(IndicatorReading("Режим", mode)),
            )
        }

        val decision = strategy.evaluate(candles)
        val indicators = buildIndicatorReadings(mode, decision)

        if (decision.indicators == null) {
            return record(
                figi = figi,
                action = DecisionAction.HOLD,
                headline = "Данных пока недостаточно",
                marketReasoning = decision.reasoning,
                indicators = indicators,
            )
        }

        val portfolio = try {
            repository.getPortfolio(accountId)
        } catch (e: Exception) {
            return record(
                figi = figi,
                action = DecisionAction.ERROR,
                headline = "Не удалось прочитать портфель",
                marketReasoning = decision.reasoning,
                riskReasoning = listOf("Запрос портфеля не прошёл: ${e.message}", "Ордер не выставлялся."),
                indicators = indicators,
            )
        }

        val position = portfolio.positions.firstOrNull { it.figi == figi }
        val currentLots = position?.quantity?.toDouble()?.toLong() ?: 0L
        val lastPrice = decision.indicators.lastPrice

        // Стоп-лосс имеет приоритет над сигналом стратегии: если позиция уже
        // просела больше лимита, закрываем её независимо от того, что говорит SMA.
        if (position != null && currentLots > 0) {
            val stopLoss = riskManager.checkStopLoss(position.averagePositionPrice.toDouble(), lastPrice)
            if (stopLoss.allowed) {
                return executeOrder(
                    accountId = accountId,
                    figi = figi,
                    lots = currentLots,
                    direction = OrderDirection.SELL,
                    action = DecisionAction.STOP_LOSS,
                    headline = "Стоп-лосс: закрываю позицию",
                    marketReasoning = decision.reasoning,
                    riskReasoning = stopLoss.reasoning,
                    indicators = indicators,
                )
            }
        }

        return when (decision.signal) {
            Signal.BUY -> {
                val verdict = riskManager.checkCanOpenPosition(currentLots, lotsPerOrder)
                if (!verdict.allowed) {
                    record(
                        figi = figi,
                        action = DecisionAction.BLOCKED,
                        headline = "Покупка отклонена риск-контролем",
                        marketReasoning = decision.reasoning,
                        riskReasoning = verdict.reasoning,
                        indicators = indicators,
                    )
                } else {
                    executeOrder(
                        accountId, figi, lotsPerOrder, OrderDirection.BUY,
                        DecisionAction.BUY, "Покупаю $lotsPerOrder лот(ов)",
                        decision.reasoning, verdict.reasoning, indicators,
                    )
                }
            }

            Signal.SELL -> {
                if (currentLots <= 0) {
                    record(
                        figi = figi,
                        action = DecisionAction.HOLD,
                        headline = "Сигнал на продажу без позиции",
                        marketReasoning = decision.reasoning,
                        riskReasoning = listOf(
                            "Открытой позиции нет — продавать нечего.",
                            "Шорт не используется, сигнал пропущен.",
                        ),
                        indicators = indicators,
                    )
                } else {
                    executeOrder(
                        accountId, figi, currentLots, OrderDirection.SELL,
                        DecisionAction.SELL, "Продаю $currentLots лот(ов)",
                        decision.reasoning,
                        listOf("Закрывается вся позиция: $currentLots лот(ов)."),
                        indicators,
                    )
                }
            }

            Signal.HOLD -> record(
                figi = figi,
                action = DecisionAction.HOLD,
                headline = "Остаюсь вне рынка",
                marketReasoning = decision.reasoning,
                riskReasoning = listOf("Действий не требуется, позиция: $currentLots лот(ов)."),
                indicators = indicators,
            )
        }
    }

    private suspend fun executeOrder(
        accountId: String,
        figi: String,
        lots: Long,
        direction: OrderDirection,
        action: DecisionAction,
        headline: String,
        marketReasoning: List<String>,
        riskReasoning: List<String>,
        indicators: List<IndicatorReading>,
    ): DecisionRecord {
        if (riskManager.isKillSwitchActive) {
            return record(
                figi, DecisionAction.BLOCKED, "Аварийная блокировка активна",
                marketReasoning,
                riskReasoning + "Дневной лимит убытка превышен — новые ордера запрещены.",
                indicators = indicators,
            )
        }
        return try {
            val response = repository.placeOrder(accountId, figi, lots, direction)
            record(
                figi, action, headline, marketReasoning, riskReasoning,
                executionNote = "Ордер отправлен, id ${response.orderId}, " +
                    "статус ${response.executionReportStatus}, исполнено ${response.lotsExecuted} из $lots.",
                indicators = indicators,
            )
        } catch (e: Exception) {
            record(
                figi, DecisionAction.ERROR, "Ордер не прошёл",
                marketReasoning, riskReasoning,
                executionNote = "Брокер отклонил заявку: ${e.message}",
                indicators = indicators,
            )
        }
    }

    private fun buildIndicatorReadings(mode: String, decision: StrategyDecision): List<IndicatorReading> {
        val snapshot = decision.indicators ?: return listOf(IndicatorReading("Режим", mode))
        return listOf(
            IndicatorReading("Режим", mode),
            IndicatorReading("Последняя цена", fmt(snapshot.lastPrice)),
            IndicatorReading("SMA(${snapshot.fastPeriod})", fmt(snapshot.fastSmaCurrent)),
            IndicatorReading("SMA(${snapshot.slowPeriod})", fmt(snapshot.slowSmaCurrent)),
            IndicatorReading("Расхождение SMA", "${fmt(snapshot.spreadPercent)}%"),
            IndicatorReading("Свечей в расчёте", snapshot.candlesAnalyzed.toString()),
        )
    }

    private fun record(
        figi: String,
        action: DecisionAction,
        headline: String,
        marketReasoning: List<String> = emptyList(),
        riskReasoning: List<String> = emptyList(),
        executionNote: String? = null,
        indicators: List<IndicatorReading> = emptyList(),
    ): DecisionRecord = DecisionRecord(
        timestampMillis = System.currentTimeMillis(),
        figi = figi,
        action = action.name,
        headline = headline,
        marketReasoning = marketReasoning,
        riskReasoning = riskReasoning,
        executionNote = executionNote,
        indicators = indicators,
    ).also(journal::append)
}
