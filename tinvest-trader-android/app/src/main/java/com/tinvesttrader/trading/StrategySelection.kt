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
    SIZING(
        key = "SIZING",
        title = "Фильтры режут объём",
        description = "Покупка происходит на каждом пересечении, но непройденный фильтр " +
            "уменьшает объём позиции, минимум до трети. Так слабый сигнал снижает риск, " +
            "а не отменяет сделку: прибыль трендовой системы делают одна-две крупные " +
            "сделки, и пропустить такую дороже, чем войти в неё неполным объёмом.",
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
        StrategyMode.SIZING -> TrendFollowingStrategy(mode = FilterMode.SIZE)
        StrategyMode.SMA -> SmaCrossoverStrategy()
    }
