package com.tinvesttrader.trading

/**
 * RSI по Уайлдеру. В стратегии используется не как самостоятельный сигнал, а
 * как фильтр: покупать на уже перегретом рынке — значит входить там, где
 * ближайшее движение с большей вероятностью вниз.
 */
object Momentum {

    fun rsi(closes: List<Double>, period: Int = 14): Double? {
        if (closes.size < period + 1) return null
        var gain = 0.0
        var loss = 0.0
        for (index in 1..period) {
            val change = closes[index] - closes[index - 1]
            if (change >= 0) gain += change else loss -= change
        }
        var averageGain = gain / period
        var averageLoss = loss / period

        for (index in period + 1 until closes.size) {
            val change = closes[index] - closes[index - 1]
            val currentGain = if (change > 0) change else 0.0
            val currentLoss = if (change < 0) -change else 0.0
            averageGain = (averageGain * (period - 1) + currentGain) / period
            averageLoss = (averageLoss * (period - 1) + currentLoss) / period
        }

        if (averageLoss == 0.0) return 100.0
        val rs = averageGain / averageLoss
        return 100 - 100 / (1 + rs)
    }
}
