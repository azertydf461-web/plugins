package com.tinvesttrader.trading

import java.time.LocalDate

data class RiskLimits(
    /** Максимум лотов в одной позиции — жёсткий потолок на переразгон. */
    val maxPositionLots: Long = 10,
    /** Стоп-лосс в процентах от средней цены открытия позиции. */
    val stopLossPercent: Double = 3.0,
    /** Дневной лимит убытка в валюте счёта — при достижении бот останавливается. */
    val maxDailyLossAmount: Double = 5000.0,
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
