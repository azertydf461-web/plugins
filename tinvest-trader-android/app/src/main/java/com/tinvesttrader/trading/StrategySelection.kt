package com.tinvesttrader.trading

import com.tinvesttrader.data.SecureTokenStore

/** Какую стратегию использует бот. */
enum class StrategyMode(val key: String, val title: String, val description: String) {
    TREND(
        key = "TREND",
        title = "Пересечение с фильтрами",
        description = "Покупка только когда рынок действительно идёт вверх: цена выше длинной " +
            "средней, средняя растёт, тренд подтверждён ADX, рынок не перегрет и размах " +
            "свечей окупает издержки. Выходы фильтрами не ограничиваются никогда.",
    ),
    SMA(
        key = "SMA",
        title = "Голое пересечение средних",
        description = "Исходная логика без фильтров: покупка на каждом пересечении вверх. " +
            "Оставлена, чтобы на истории было видно, что дают фильтры.",
    ),
    ;

    companion object {
        fun fromKey(key: String?): StrategyMode =
            entries.firstOrNull { it.key == key } ?: TREND
    }
}

/**
 * Стратегия собирается в одном месте, чтобы фоновый воркер и ручной запуск из
 * интерфейса не могли случайно торговать по разной логике.
 */
fun strategyFor(store: SecureTokenStore): Strategy =
    when (StrategyMode.fromKey(store.strategyMode)) {
        StrategyMode.TREND -> TrendFollowingStrategy()
        StrategyMode.SMA -> SmaCrossoverStrategy()
    }
