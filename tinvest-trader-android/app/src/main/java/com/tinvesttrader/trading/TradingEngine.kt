package com.tinvesttrader.trading

import com.tinvesttrader.data.OrderDirection
import com.tinvesttrader.data.SecureTokenStore
import com.tinvesttrader.data.TInvestRepository

sealed class EngineEvent {
    data class Signal(val signal: com.tinvesttrader.trading.Signal, val price: Double) : EngineEvent()
    data class OrderPlaced(val direction: OrderDirection, val lots: Long, val orderId: String) : EngineEvent()
    data class RiskBlocked(val reason: String) : EngineEvent()
    data class Error(val message: String) : EngineEvent()
    data object Idle : EngineEvent()
}

/**
 * Один тик торгового цикла: свежие свечи -> сигнал стратегии -> проверка
 * риск-менеджера -> (опционально) ордер. Вызывается периодически из
 * TradingWorker; сам по себе engine не планирует расписание.
 */
class TradingEngine(
    private val repository: TInvestRepository,
    private val strategy: Strategy,
    private val riskManager: RiskManager,
    private val tokenStore: SecureTokenStore,
    private val lotsPerOrder: Long = 1,
) {

    suspend fun tick(): EngineEvent {
        val accountId = tokenStore.accountId
            ?: return EngineEvent.Error("Счёт не выбран (Settings)")
        val figi = tokenStore.instrumentFigi
            ?: return EngineEvent.Error("Инструмент не выбран (Settings)")

        val candles = try {
            repository.getRecentCandles(figi)
        } catch (e: Exception) {
            return EngineEvent.Error("Не удалось получить свечи: ${e.message}")
        }
        if (candles.isEmpty()) return EngineEvent.Idle

        val lastPrice = candles.last().close.toDouble()
        val signal = strategy.evaluate(candles)
        if (signal == Signal.HOLD) return EngineEvent.Signal(signal, lastPrice)

        val portfolio = try {
            repository.getPortfolio(accountId)
        } catch (e: Exception) {
            return EngineEvent.Error("Не удалось получить портфель: ${e.message}")
        }
        val position = portfolio.positions.firstOrNull { it.figi == figi }
        val currentLots = position?.quantity?.toDouble()?.toLong() ?: 0L

        // Стоп-лосс имеет приоритет над сигналом стратегии: если позиция уже
        // просела больше лимита, закрываем её независимо от того, что говорит SMA.
        if (position != null && currentLots > 0) {
            val avgEntry = position.averagePositionPrice.toDouble()
            if (riskManager.shouldStopLoss(avgEntry, lastPrice)) {
                return placeOrderChecked(accountId, figi, currentLots, OrderDirection.SELL)
            }
        }

        return when (signal) {
            Signal.BUY -> {
                if (!riskManager.canOpenPosition(currentLots, lotsPerOrder)) {
                    EngineEvent.RiskBlocked("Лимит позиции или дневной лимит убытка исчерпан")
                } else {
                    placeOrderChecked(accountId, figi, lotsPerOrder, OrderDirection.BUY)
                }
            }
            Signal.SELL -> {
                if (currentLots <= 0) {
                    EngineEvent.Signal(signal, lastPrice) // нечего продавать — просто отмечаем сигнал
                } else {
                    placeOrderChecked(accountId, figi, currentLots, OrderDirection.SELL)
                }
            }
            Signal.HOLD -> EngineEvent.Idle
        }
    }

    private suspend fun placeOrderChecked(
        accountId: String,
        figi: String,
        lots: Long,
        direction: OrderDirection,
    ): EngineEvent {
        if (riskManager.isKillSwitchActive) {
            return EngineEvent.RiskBlocked("Kill switch активен — дневной лимит убытка превышен")
        }
        return try {
            val response = repository.placeOrder(accountId, figi, lots, direction)
            EngineEvent.OrderPlaced(direction, lots, response.orderId)
        } catch (e: Exception) {
            EngineEvent.Error("Ордер не выполнен: ${e.message}")
        }
    }
}
