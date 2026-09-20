package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import java.time.LocalDate

/** Как считается расстояние до стопа. */
enum class StopMode { PERCENT, ATR }

/** Цена стопа вместе с объяснением, откуда она взялась. */
data class StopLevel(
    val price: Double,
    val distancePercent: Double,
    val explanation: String,
)

data class RiskLimits(
    /** Максимум лотов в одной позиции — жёсткий потолок на переразгон. */
    val maxPositionLots: Long = 10,
    /** Стоп-лосс в процентах от средней цены открытия позиции. */
    val stopLossPercent: Double = 3.0,
    /** Дневной лимит убытка в валюте счёта — при достижении бот останавливается. */
    val maxDailyLossAmount: Double = 5000.0,
    /**
     * Откуда берётся расстояние до стопа. ATR по умолчанию: одинаковый для
     * всех процент либо режет позицию на обычном шуме, либо пропускает
     * реальное падение — в зависимости от того, насколько бумага подвижна.
     */
    val stopMode: StopMode = StopMode.ATR,
    val atrMultiplier: Double = 2.0,
    /** Потолок расстояния до стопа: даже в самой дикой бумаге убыток ограничен. */
    val maxStopPercent: Double = 8.0,
)

/** Решение риск-менеджера вместе с объяснением, почему оно такое. */
data class RiskVerdict(
    val allowed: Boolean,
    val reasoning: List<String>,
)

/**
 * Risk manager — единственное место, которое разрешает или запрещает
 * реальный ордер. TradingEngine обязан спрашивать его перед КАЖДЫМ
 * вызовом placeOrder; сама стратегия риски не считает.
 */
class RiskManager(private val limits: RiskLimits) {

    private var dailyPnl: Double = 0.0
    private var pnlDate: LocalDate = LocalDate.now()
    private var killSwitchActive: Boolean = false

    val isKillSwitchActive: Boolean get() = killSwitchActive
    val currentDailyPnl: Double get() = dailyPnl
    val activeLimits: RiskLimits get() = limits

    fun recordRealizedPnl(amount: Double) {
        rolloverDayIfNeeded()
        dailyPnl += amount
        if (dailyPnl <= -limits.maxDailyLossAmount) {
            killSwitchActive = true
        }
    }

    /** Используется при пересборке менеджера, чтобы блокировка пережила смену настроек. */
    fun activateKillSwitch() {
        killSwitchActive = true
    }

    /** Сбрасывается вручную из UI — осознанное действие пользователя, не автоматика. */
    fun resetKillSwitch() {
        killSwitchActive = false
    }

    fun checkCanOpenPosition(currentLots: Long, additionalLots: Long): RiskVerdict {
        rolloverDayIfNeeded()
        val reasoning = mutableListOf(
            "Текущая позиция: $currentLots лот(ов), заявка на +$additionalLots.",
            "Лимит позиции: ${limits.maxPositionLots} лот(ов).",
            "Дневной результат: ${fmt(dailyPnl)} при лимите убытка ${fmt(-limits.maxDailyLossAmount)}.",
        )
        if (killSwitchActive) {
            reasoning += "Аварийная блокировка активна — дневной лимит убытка исчерпан."
            reasoning += "Вердикт: ордер ЗАПРЕЩЁН."
            return RiskVerdict(allowed = false, reasoning = reasoning)
        }
        val fits = currentLots + additionalLots <= limits.maxPositionLots
        reasoning += if (fits) {
            "Итоговая позиция ${currentLots + additionalLots} лот(ов) укладывается в лимит."
        } else {
            "Итоговая позиция ${currentLots + additionalLots} лот(ов) превысила бы лимит."
        }
        reasoning += "Вердикт: ордер ${if (fits) "РАЗРЕШЁН" else "ЗАПРЕЩЁН"}."
        return RiskVerdict(allowed = fits, reasoning = reasoning)
    }

    /** Проверка стоп-лосса с объяснением: просадка считается от средней цены входа. */
    /**
     * Цена, на которой позиция закрывается. По ней выставляется защитная
     * стоп-заявка у брокера, и она же служит порогом для проверки в цикле.
     */
    fun stopLevelFor(entryPrice: Double, atr: Double?): StopLevel {
        val percentStop = entryPrice * (1 - limits.stopLossPercent / 100)
        if (limits.stopMode == StopMode.PERCENT || atr == null || atr <= 0) {
            val note = if (limits.stopMode == StopMode.ATR) {
                "ATR посчитать не удалось, стоп взят фиксированным процентом " +
                    "${fmt(limits.stopLossPercent)}%."
            } else {
                "Стоп задан фиксированным процентом ${fmt(limits.stopLossPercent)}% от цены входа."
            }
            return StopLevel(percentStop, limits.stopLossPercent, note)
        }

        val atrStop = entryPrice - limits.atrMultiplier * atr
        val atrPercent = (entryPrice - atrStop) / entryPrice * 100
        // Потолок нужен, потому что на неликвиде ATR бывает огромным, и
        // «честный по волатильности» стоп превратился бы в отсутствие стопа.
        if (atrPercent > limits.maxStopPercent) {
            val capped = entryPrice * (1 - limits.maxStopPercent / 100)
            return StopLevel(
                price = capped,
                distancePercent = limits.maxStopPercent,
                explanation = "Стоп по ATR получился ${fmt(atrPercent)}% — это больше потолка " +
                    "${fmt(limits.maxStopPercent)}%, поэтому ограничен потолком.",
            )
        }
        return StopLevel(
            price = atrStop,
            distancePercent = atrPercent,
            explanation = "Стоп по волатильности: ${fmt(limits.atrMultiplier)} x ATR = " +
                "${fmt(limits.atrMultiplier * atr)} от цены входа, то есть ${fmt(atrPercent)}%.",
        )
    }

    /**
     * Проверка стопа по всем свечам с прошлого визита бота, а не только по
     * текущей цене. Если цена сходила вниз и вернулась, пока бот спал, старая
     * проверка этого не видела и держала позицию дальше вопреки правилу.
     */
    fun checkStopLossOverBars(
        averageEntryPrice: Double,
        stopPrice: Double,
        candlesSinceLastCheck: List<Candle>,
        currentPrice: Double,
    ): RiskVerdict {
        val lowest = candlesSinceLastCheck.minOfOrNull { it.low.toDouble() } ?: currentPrice
        val touched = lowest <= stopPrice
        val reasoning = mutableListOf(
            "Цена входа ${fmt(averageEntryPrice)}, стоп ${fmt(stopPrice)}, сейчас ${fmt(currentPrice)}.",
            "Минимум за ${candlesSinceLastCheck.size} свеч(и) с прошлой проверки: ${fmt(lowest)}.",
        )
        reasoning += if (touched) {
            "Стоп был задет — позиция закрывается, сигнал стратегии игнорируется."
        } else {
            "Стоп не задет — позиция остаётся открытой."
        }
        return RiskVerdict(allowed = touched, reasoning = reasoning)
    }

    fun checkStopLoss(averageEntryPrice: Double, currentPrice: Double): RiskVerdict {
        if (averageEntryPrice <= 0) {
            return RiskVerdict(
                allowed = false,
                reasoning = listOf("Средняя цена входа неизвестна — стоп-лосс не рассчитывается."),
            )
        }
        val dropPercent = (averageEntryPrice - currentPrice) / averageEntryPrice * 100.0
        val triggered = dropPercent >= limits.stopLossPercent
        val reasoning = mutableListOf(
            "Средняя цена входа ${fmt(averageEntryPrice)}, текущая ${fmt(currentPrice)}.",
            "Просадка ${fmt(dropPercent)}% при пороге стоп-лосса ${fmt(limits.stopLossPercent)}%.",
            if (triggered) {
                "Порог пройден — позиция закрывается принудительно, сигнал стратегии игнорируется."
            } else {
                "Порог не достигнут — позиция остаётся открытой."
            },
        )
        return RiskVerdict(allowed = triggered, reasoning = reasoning)
    }

    private fun rolloverDayIfNeeded() {
        val today = LocalDate.now()
        if (today != pnlDate) {
            pnlDate = today
            dailyPnl = 0.0
            // killSwitch намеренно НЕ сбрасывается автоматически по дате —
            // это решение пользователя (resetKillSwitch), а не таймер.
        }
    }
}
