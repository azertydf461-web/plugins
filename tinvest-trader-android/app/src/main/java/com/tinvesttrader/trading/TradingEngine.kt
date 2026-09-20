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
            repository.getRecentCandles(figi, interval, lookbackMinutesFor(interval))
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

        if (position != null && currentLots > 0) {
            val entryPrice = position.averagePositionPrice.toDouble()
            val guardNotes = ensureProtectiveStop(accountId, figi, currentLots, entryPrice, candles)

            // Стоп-лосс имеет приоритет над сигналом стратегии. Проверяется по
            // минимумам всех свечей с прошлого визита, а не по текущей цене:
            // цена могла сходить к стопу и вернуться, пока бот спал.
            val stopPrice = tokenStore.protectiveStopPrice.takeIf { it > 0 }
                ?: riskManager.stopLevelFor(entryPrice, Volatility.atr(candles)).price
            val stopLoss = riskManager.checkStopLossOverBars(
                averageEntryPrice = entryPrice,
                stopPrice = stopPrice,
                candlesSinceLastCheck = candles.takeLast(missedBars.coerceAtLeast(1)),
                currentPrice = lastPrice,
            )
            if (stopLoss.allowed) {
                cancelProtectiveStop(accountId)
                return executeOrder(
                    accountId = accountId,
                    figi = figi,
                    lots = currentLots,
                    direction = OrderDirection.SELL,
                    action = DecisionAction.STOP_LOSS,
                    headline = "Стоп-лосс: закрываю позицию",
                    marketReasoning = decision.reasoning,
                    riskReasoning = guardNotes + stopLoss.reasoning,
                    indicators = indicators,
                )
            }
        } else if (currentLots <= 0 && tokenStore.protectiveStopOrderId != null) {
            // Позиции нет, а стоп-заявка числится: значит, она уже сработала
            // или позицию закрыли руками. Снимаем, чтобы она не выстрелила по
            // следующей покупке с чужой ценой.
            cancelProtectiveStop(accountId)
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
                    val bought = executeOrder(
                        accountId, figi, lotsPerOrder, OrderDirection.BUY,
                        DecisionAction.BUY, "Покупаю $lotsPerOrder лот(ов)",
                        decision.reasoning, verdict.reasoning, indicators,
                    )
                    // Защита ставится сразу после покупки: незащищённая
                    // позиция не должна пережить даже один цикл бота.
                    if (bought.decisionAction == DecisionAction.BUY) {
                        val notes = ensureProtectiveStop(
                            accountId, figi, currentLots + lotsPerOrder, lastPrice, candles,
                        )
                        record(
                            figi = figi,
                            action = DecisionAction.BUY,
                            headline = "Защита позиции",
                            riskReasoning = notes,
                            indicators = indicators,
                        )
                    }
                    bought
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
                    val cancelNote = cancelProtectiveStop(accountId)
                    executeOrder(
                        accountId, figi, currentLots, OrderDirection.SELL,
                        DecisionAction.SELL, "Продаю $currentLots лот(ов)",
                        decision.reasoning,
                        listOf("Закрывается вся позиция: $currentLots лот(ов).") + cancelNote,
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

    /**
     * Ставит защитную стоп-заявку, если её ещё нет. Заявка живёт на сервере
     * брокера, поэтому срабатывает и когда приложение выгружено. Вызывается
     * на каждом цикле: если заявка пропала (сработала частично, была снята
     * вручную), позиция не должна остаться без защиты.
     */
    private suspend fun ensureProtectiveStop(
        accountId: String,
        figi: String,
        lots: Long,
        entryPrice: Double,
        candles: List<Candle>,
    ): List<String> {
        if (!tokenStore.protectiveStopEnabled) {
            return listOf(
                "Защитная стоп-заявка отключена в настройках: стоп сработает только " +
                    "при очередной проверке, а на разрыве цены убыток будет больше.",
            )
        }
        if (lots <= 0 || entryPrice <= 0) return emptyList()

        val level = riskManager.stopLevelFor(entryPrice, Volatility.atr(candles))
        val existing = runCatching { repository.getStopOrders(accountId) }.getOrNull()
        val alive = existing?.firstOrNull { it.figi == figi || it.stopOrderId == tokenStore.protectiveStopOrderId }

        if (alive != null) {
            tokenStore.protectiveStopOrderId = alive.stopOrderId
            if (tokenStore.protectiveStopPrice <= 0) tokenStore.protectiveStopPrice = level.price
            return listOf(
                "Защитная стоп-заявка у брокера активна: ${fmt(tokenStore.protectiveStopPrice)}.",
            )
        }

        return runCatching {
            repository.placeProtectiveStop(accountId, figi, lots, level.price)
        }.fold(
            onSuccess = { id ->
                tokenStore.protectiveStopOrderId = id
                tokenStore.protectiveStopPrice = level.price
                listOf(
                    level.explanation,
                    "Выставлена стоп-заявка у брокера на ${fmt(level.price)} " +
                        "(${fmt(level.distancePercent)}% от входа). Она сработает сама, " +
                        "даже если приложение выгружено из памяти.",
                )
            },
            onFailure = { error ->
                // Песочница стоп-заявки не поддерживает, и это не повод молча
                // остаться без защиты: пользователь должен знать, что стоп
                // сейчас держится только на проверках бота.
                tokenStore.protectiveStopOrderId = null
                tokenStore.protectiveStopPrice = level.price
                listOf(
                    level.explanation,
                    "Брокер не принял стоп-заявку: ${error.message}",
                    "Позиция остаётся под мягким стопом — он сработает только на " +
                        "очередной проверке бота, и на разрыве цены убыток окажется больше.",
                )
            },
        )
    }

    /** Снимает защитную заявку перед закрытием позиции, чтобы она не выстрелила потом. */
    private suspend fun cancelProtectiveStop(accountId: String): List<String> {
        val id = tokenStore.protectiveStopOrderId ?: return emptyList()
        val result = runCatching { repository.cancelStopOrder(accountId, id) }
        tokenStore.protectiveStopOrderId = null
        tokenStore.protectiveStopPrice = 0.0
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

    /** Запас истории под таймфрейм: с тройным запасом на ночь и выходные. */
    private fun lookbackMinutesFor(interval: String): Long {
        val minutesPerBar = when (interval) {
            "CANDLE_INTERVAL_1_MIN" -> 1L
            "CANDLE_INTERVAL_5_MIN" -> 5L
            "CANDLE_INTERVAL_15_MIN" -> 15L
            "CANDLE_INTERVAL_HOUR" -> 60L
            else -> 15L
        }
        return minutesPerBar * BARS_WANTED * 3
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
            IndicatorReading("SMA(${snapshot.fastPeriod})", fmt(snapshot.fastSmaCurrent)),
            IndicatorReading("SMA(${snapshot.slowPeriod})", fmt(snapshot.slowSmaCurrent)),
            IndicatorReading("Расхождение SMA", "${fmt(snapshot.spreadPercent)}%"),
            IndicatorReading("Свечей в расчёте", snapshot.candlesAnalyzed.toString()),
        ) + base +
            listOfNotNull(
                decision.signalCandleTime?.let { IndicatorReading("Пересечение на свече", it) },
                tokenStore.protectiveStopPrice.takeIf { it > 0 }
                    ?.let { IndicatorReading("Защитный стоп", fmt(it)) },
            )
    }

    private fun humanInterval(interval: String): String = when (interval) {
        "CANDLE_INTERVAL_1_MIN" -> "1 минута"
        "CANDLE_INTERVAL_5_MIN" -> "5 минут"
        "CANDLE_INTERVAL_15_MIN" -> "15 минут"
        "CANDLE_INTERVAL_HOUR" -> "1 час"
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
        const val BARS_WANTED = 120
        const val MAX_SCAN_BARS = 20
        const val DEFAULT_SCAN_BARS = 5
    }
}
