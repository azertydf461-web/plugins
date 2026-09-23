package com.tinvesttrader.trading

import com.tinvesttrader.data.SecureTokenStore

/**
 * Бот торгует одним правилом — чистым пробоем канала. Пересечение средних,
 * фильтры входа и уменьшение объёма убраны: на истории каждое из них делало
 * результат хуже, а не лучше (см. README, разделы про фильтры и криптовалюту).
 *
 * Размер канала зависит от таймфрейма: на дневных свечах 20/10, на часовых
 * 120/60 — это те же примерно пять и две с половиной торговых недели… точнее,
 * то окно, которое на часовых свечах показало плюс, в отличие от 20/10.
 */
enum class StrategyMode(val key: String, val title: String, val description: String) {
    DONCHIAN(
        key = "DONCHIAN",
        title = "Пробой канала",
        description = "Покупка, когда цена закрылась выше максимума за канал входа; продажа, " +
            "когда закрылась ниже минимума за канал выхода. Никаких фильтров и стопов: " +
            "роль стопа играет нижняя граница канала, которая сама поднимается вслед за ценой.",
    ),
    ;

    companion object {
        fun fromKey(@Suppress("UNUSED_PARAMETER") key: String?): StrategyMode = DONCHIAN
    }
}

/** Канал входа и выхода под таймфрейм. */
data class ChannelSize(val entry: Int, val exit: Int)

fun channelFor(interval: String): ChannelSize = when (interval) {
    SecureTokenStore.INTERVAL_HOUR -> ChannelSize(entry = 120, exit = 60)
    else -> ChannelSize(entry = 20, exit = 10)
}

/**
 * Стратегия собирается в одном месте, чтобы фоновый воркер и ручной запуск из
 * интерфейса не могли случайно торговать по разной логике.
 */
fun strategyFor(store: SecureTokenStore): Strategy {
    val channel = channelFor(store.candleInterval)
    return DonchianBreakoutStrategy(channel.entry, channel.exit)
}
