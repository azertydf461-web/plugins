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

    fun canOpenPosition(currentLots: Long, additionalLots: Long): Boolean {
        rolloverDayIfNeeded()
        if (killSwitchActive) return false
        return currentLots + additionalLots <= limits.maxPositionLots
    }

    fun shouldStopLoss(averageEntryPrice: Double, currentPrice: Double): Boolean {
        if (averageEntryPrice <= 0) return false
        val dropPercent = (averageEntryPrice - currentPrice) / averageEntryPrice * 100.0
        return dropPercent >= limits.stopLossPercent
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
