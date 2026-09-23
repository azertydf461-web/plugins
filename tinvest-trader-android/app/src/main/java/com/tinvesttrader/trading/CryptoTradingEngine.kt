package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import com.tinvesttrader.data.SecureTokenStore
import com.tinvesttrader.data.crypto.CryptoExchange
import com.tinvesttrader.data.crypto.floorToStep
import java.math.BigDecimal

/** То, что криптоботу нужно из настроек. Отдельно от хранилища — ради тестов. */
interface CryptoBotSettings {
    val symbol: String
    val interval: String
    val quoteAmount: Double
    var lastCandleTime: String?

    /**
     * Сколько базовой монеты было на счёте до покупки бота; null — у бота нет
     * позиции. Бот продаёт только разницу: монеты, которые лежали на счёте до
     * него, он не трогает.
     */
    var positionBaseline: String?
}

class StoreCryptoBotSettings(private val store: SecureTokenStore) : CryptoBotSettings {
    override val symbol get() = store.cryptoSymbol
    override val interval get() = store.cryptoInterval
    override val quoteAmount get() = store.cryptoOrderQuoteAmount
    override var lastCandleTime: String?
        get() = store.cryptoLastCandleTime
        set(value) { store.cryptoLastCandleTime = value }
    override var positionBaseline: String?
        get() = store.cryptoPositionBaseline
        set(value) { store.cryptoPositionBaseline = value }
}

/**
 * Один проход криптобота: закрытые свечи -> пробой канала -> заявка.
 *
 * Правило то же, что проверено на истории: только покупка, без фильтров и без
 * стоп-заявок. Позиция закрывается, когда цена закрывается ниже минимума
 * канала выхода. Размер покупки — фиксированная сумма в валюте котировки.
 */
class CryptoTradingEngine(
    private val exchange: CryptoExchange,
    private val settings: CryptoBotSettings,
    private val record: (DecisionRecord) -> Unit,
) {

    suspend fun tick(): DecisionRecord {
        val symbol = settings.symbol
        val label = "BYBIT:$symbol"
        val mode = IndicatorReading("Режим", "Bybit ${exchange.modeLabel}")
        val channel = channelFor(settings.interval)

        val candles = try {
            exchange.closedCandles(symbol, settings.interval, channel.entry + CANDLE_RESERVE)
        } catch (e: Exception) {
            return note(label, DecisionAction.ERROR, "Нет данных с биржи", listOf("Запрос свечей не прошёл: ${e.message}"), indicators = listOf(mode))
        }

        val missed = missedBarsSince(candles, settings.lastCandleTime)
        val decision = DonchianBreakoutStrategy(channel.entry, channel.exit).evaluate(candles, missed)
        candles.lastOrNull()?.time?.let { settings.lastCandleTime = it }

        val indicators = listOf(mode, IndicatorReading("Свечей с прошлой проверки", missed.toString())) +
            (decision.indicators?.let {
                listOf(
                    IndicatorReading("Последняя цена", fmt(it.lastPrice)),
                    IndicatorReading("Максимум за ${channel.entry} свечей", fmt(it.slowSmaCurrent)),
                    IndicatorReading("Минимум за ${channel.exit} свечей", fmt(it.fastSmaCurrent)),
                )
            } ?: emptyList())

        if (decision.indicators == null) {
            return note(label, DecisionAction.HOLD, "Данных пока недостаточно", decision.reasoning, indicators = indicators)
        }

        val (instrument, balances) = try {
            val instrument = exchange.instrument(symbol)
            instrument to exchange.availableBalances(listOf(instrument.baseCoin, instrument.quoteCoin))
        } catch (e: Exception) {
            return note(
                label, DecisionAction.ERROR, "Не удалось прочитать счёт биржи", decision.reasoning,
                listOf("Запрос баланса не прошёл: ${e.message}", "Заявка не выставлялась."), indicators = indicators,
            )
        }
        val baseFree = balances[instrument.baseCoin] ?: BigDecimal.ZERO
        val quoteFree = balances[instrument.quoteCoin] ?: BigDecimal.ZERO
        val baseline = settings.positionBaseline?.toBigDecimalOrNull()
        val inPosition = baseline != null
        val sellable = if (baseline == null) {
            BigDecimal.ZERO
        } else {
            floorToStep((baseFree - baseline).max(BigDecimal.ZERO), instrument.basePrecision)
        }
        val balanceNote = "На счёте: ${baseFree.toPlainString()} ${instrument.baseCoin}, " +
            "${quoteFree.toPlainString()} ${instrument.quoteCoin}."

        return when (decision.signal) {
            Signal.BUY -> {
                if (inPosition) {
                    return note(label, DecisionAction.HOLD, "Уже в позиции", decision.reasoning, listOf(balanceNote, "Повторная покупка не делается."), indicators = indicators)
                }
                val amount = BigDecimal.valueOf(settings.quoteAmount).setScale(2, java.math.RoundingMode.FLOOR)
                val problem = when {
                    amount < instrument.minOrderAmt ->
                        "Сумма покупки ${amount.toPlainString()} ${instrument.quoteCoin} меньше минимальной " +
                            "заявки биржи ${instrument.minOrderAmt.toPlainString()}."
                    quoteFree < amount ->
                        "Не хватает ${instrument.quoteCoin}: нужно ${amount.toPlainString()}, " +
                            "доступно ${quoteFree.toPlainString()}."
                    else -> null
                }
                if (problem != null) {
                    return note(label, DecisionAction.BLOCKED, "Покупка невозможна", decision.reasoning, listOf(balanceNote, problem), indicators = indicators)
                }
                order(label, DecisionAction.BUY, "Покупаю на ${amount.toPlainString()} ${instrument.quoteCoin}", decision.reasoning, listOf(balanceNote), indicators) {
                    exchange.marketBuy(symbol, amount).also { settings.positionBaseline = baseFree.toPlainString() }
                }
            }

            Signal.SELL -> {
                if (!inPosition) {
                    note(label, DecisionAction.HOLD, "Сигнал на продажу без позиции", decision.reasoning, listOf(balanceNote, "Продавать нечего; в минус бот не продаёт."), indicators = indicators)
                } else if (sellable < instrument.minOrderQty || sellable.signum() <= 0) {
                    settings.positionBaseline = null
                    note(
                        label, DecisionAction.ERROR, "Позиция бота не найдена на счёте", decision.reasoning,
                        listOf(
                            balanceNote,
                            "Купленные ботом монеты на счёте не обнаружены — возможно, их продали вручную.",
                            "Бот считает позицию закрытой и ничего не продаёт.",
                        ),
                        indicators = indicators,
                    )
                } else {
                    order(label, DecisionAction.SELL, "Продаю ${sellable.toPlainString()} ${instrument.baseCoin}", decision.reasoning, listOf(balanceNote, "Продаётся только купленное ботом; монеты, лежавшие на счёте до него, не трогаются."), indicators) {
                        exchange.marketSell(symbol, sellable).also { settings.positionBaseline = null }
                    }
                }
            }

            Signal.HOLD -> note(
                label, DecisionAction.HOLD,
                if (inPosition) "Держу позицию" else "Остаюсь вне рынка",
                decision.reasoning, listOf(balanceNote), indicators = indicators,
            )
        }
    }

    private suspend fun order(
        label: String,
        action: DecisionAction,
        headline: String,
        market: List<String>,
        risk: List<String>,
        indicators: List<IndicatorReading>,
        place: suspend () -> String,
    ): DecisionRecord = try {
        val id = place()
        note(label, action, headline, market, risk, "Заявка принята биржей, номер $id.", indicators)
    } catch (e: Exception) {
        note(label, DecisionAction.ERROR, "Заявка не прошла", market, risk, "Биржа отклонила заявку: ${e.message}", indicators)
    }

    private fun note(
        label: String,
        action: DecisionAction,
        headline: String,
        market: List<String> = emptyList(),
        risk: List<String> = emptyList(),
        execution: String? = null,
        indicators: List<IndicatorReading> = emptyList(),
    ): DecisionRecord = DecisionRecord(
        timestampMillis = System.currentTimeMillis(),
        figi = label,
        action = action.name,
        headline = headline,
        marketReasoning = market,
        riskReasoning = risk,
        executionNote = execution,
        indicators = indicators,
    ).also(record)

    private fun missedBarsSince(candles: List<Candle>, last: String?): Int {
        if (last.isNullOrBlank()) return 1
        val index = candles.indexOfLast { it.time == last }
        if (index < 0) return 5
        return (candles.size - 1 - index).coerceIn(1, 20)
    }

    private companion object {
        const val CANDLE_RESERVE = 60
    }
}
