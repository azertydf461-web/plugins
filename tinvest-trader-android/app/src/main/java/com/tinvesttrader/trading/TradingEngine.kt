package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
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

        val interval = tokenStore.candleInterval
        val candles = try {
            // Только закрытые свечи: незакрытая дневная свеча — это половина
            // дня, и пробой по ней может исчезнуть к вечеру.
            repository.getHistory(figi, interval, historyDaysFor(interval)).filter { it.isComplete }
        } catch (e: Exception) {
            return record(
                figi = figi,
                action = DecisionAction.ERROR,
                headline = "Нет данных с биржи",
                marketReasoning = listOf("Запрос свечей не прошёл: ${e.message}"),
                indicators = listOf(IndicatorReading("Режим", mode)),
            )
        }

        // Бот просыпается по расписанию и между проверками пропускает свечи.
        // Считаем, сколько их накопилось, и просим стратегию просмотреть весь
        // пропуск: иначе пересечение, случившееся во сне, теряется навсегда.
        val missedBars = missedBarsSince(candles, tokenStore.lastProcessedCandleTime)
        val decision = strategy.evaluate(candles, missedBars)
        candles.lastOrNull()?.time?.let { tokenStore.lastProcessedCandleTime = it }
        val indicators = buildIndicatorReadings(mode, decision, interval, missedBars)

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

        // Стопы у брокера больше не ставятся: роль стопа играет нижняя граница
        // канала. Заявка, оставшаяся от прошлых версий бота, снимается, чтобы
        // не выбросить из позиции раньше правила.
        val leftoverStopNotes = if (tokenStore.protectiveStopOrderId != null) {
            cancelProtectiveStop(accountId)
        } else {
            emptyList()
        }

        return when (decision.signal) {
            Signal.BUY -> {
                // Уверенность стратегии переводится в объём: слабый сигнал
                // даёт неполную позицию, но сделка всё равно происходит.
                val orderLots = orderLotsFor(decision)
                val verdict = riskManager.checkCanOpenPosition(currentLots, orderLots)
                    .let { it.copy(reasoning = leftoverStopNotes + it.reasoning) }
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
                        accountId, figi, orderLots, OrderDirection.BUY,
                        DecisionAction.BUY, "Покупаю $orderLots лот(ов)",
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
                        leftoverStopNotes + "Закрывается вся позиция: $currentLots лот(ов).",
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
        // Аварийная блокировка запрещает только новые покупки: не дать закрыть
        // позицию значило бы держать убыток дальше именно тогда, когда он велик.
        if (riskManager.isKillSwitchActive && direction == OrderDirection.BUY) {
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

    /** Снимает защитную заявку перед закрытием позиции, чтобы она не выстрелила потом. */
    private suspend fun cancelProtectiveStop(accountId: String): List<String> {
        val id = tokenStore.protectiveStopOrderId ?: return emptyList()
        val result = runCatching { repository.cancelStopOrder(accountId, id) }
        tokenStore.protectiveStopOrderId = null
        tokenStore.protectiveStopPrice = 0.0
        tokenStore.positionHighWaterPrice = 0.0
        return listOf(
            if (result.isSuccess) {
                "Защитная стоп-заявка снята."
            } else {
                "Снять стоп-заявку не удалось: ${result.exceptionOrNull()?.message}. " +
                    "Проверьте её вручную в приложении брокера."
            },
        )
    }

    /**
     * Сколько свечей появилось с прошлой проверки. Именно этот пропуск
     * стратегия и должна досмотреть; одна свеча — минимум, двадцать — потолок,
     * чтобы после долгого простоя не отыгрывать протухшие сигналы.
     */
    private fun missedBarsSince(candles: List<Candle>, lastProcessedTime: String?): Int {
        if (lastProcessedTime.isNullOrBlank()) return 1
        val index = candles.indexOfLast { it.time == lastProcessedTime }
        if (index < 0) return DEFAULT_SCAN_BARS
        return (candles.size - 1 - index).coerceIn(1, MAX_SCAN_BARS)
    }

    /**
     * Сколько лотов брать. Полная уверенность — базовый размер, неполная —
     * пропорционально меньше, но не меньше одного лота: дробить лот биржа
     * не даёт, а отменять сделку из-за округления бессмысленно.
     */
    private fun orderLotsFor(decision: StrategyDecision): Long {
        val base = tokenStore.baseLots.coerceAtLeast(1)
        if (decision.conviction >= 0.999) return base
        return Math.round(base * decision.conviction).coerceIn(1L, base)
    }

    /**
     * Сколько календарных дней истории брать. Канал входа — 20 дневных или 120
     * часовых свечей; запас нужен на выходные, праздники и досмотр пропуска.
     */
    private fun historyDaysFor(interval: String): Long = when (interval) {
        SecureTokenStore.INTERVAL_HOUR -> 45L
        else -> 120L
    }

    private fun buildIndicatorReadings(
        mode: String,
        decision: StrategyDecision,
        interval: String,
        missedBars: Int,
    ): List<IndicatorReading> {
        val base = listOf(
            IndicatorReading("Таймфрейм", humanInterval(interval)),
            IndicatorReading("Свечей с прошлой проверки", missedBars.toString()),
        )
        val snapshot = decision.indicators
            ?: return listOf(IndicatorReading("Режим", mode)) + base
        return listOf(
            IndicatorReading("Режим", mode),
            IndicatorReading("Последняя цена", fmt(snapshot.lastPrice)),
            IndicatorReading("Максимум за ${snapshot.slowPeriod} свечей", fmt(snapshot.slowSmaCurrent)),
            IndicatorReading("Минимум за ${snapshot.fastPeriod} свечей", fmt(snapshot.fastSmaCurrent)),
            IndicatorReading("Свечей в расчёте", snapshot.candlesAnalyzed.toString()),
        ) + base +
            listOfNotNull(
                decision.signalCandleTime?.let { IndicatorReading("Сигнал на свече", it) },
            )
    }

    private fun humanInterval(interval: String): String = when (interval) {
        "CANDLE_INTERVAL_1_MIN" -> "1 минута"
        "CANDLE_INTERVAL_5_MIN" -> "5 минут"
        "CANDLE_INTERVAL_15_MIN" -> "15 минут"
        "CANDLE_INTERVAL_HOUR" -> "1 час"
        "CANDLE_INTERVAL_DAY" -> "1 день"
        else -> interval.removePrefix("CANDLE_INTERVAL_")
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

    private companion object {
        const val MAX_SCAN_BARS = 20
        const val DEFAULT_SCAN_BARS = 5
    }
}
