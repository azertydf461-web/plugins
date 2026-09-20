package com.tinvestanalyst.analysis

import com.tinvestanalyst.data.WatchedInstrument
import kotlin.math.floor

data class RiskProfile(
    val capital: Double,
    val riskPerTradePercent: Double,
    val maxLeverage: Double,
    val horizon: Horizon,
)

/**
 * Горизонт задаёт, что важнее в итоговом выводе. Новости весят заметно на
 * днях и почти ничего не значат на годах: информационный повод отыгрывается
 * рынком за часы, а бизнес эмитента — за кварталы.
 */
enum class Horizon(
    val title: String,
    val technicalWeight: Double,
    val fundamentalWeight: Double,
    val dividendWeight: Double,
    val newsWeight: Double,
) {
    SPECULATIVE("Спекулятивный (дни)", 0.55, 0.15, 0.05, 0.25),
    SWING("Среднесрочный (недели)", 0.38, 0.32, 0.12, 0.18),
    LONG("Долгосрочный (год+)", 0.18, 0.47, 0.23, 0.12),
    ;

    companion object {
        fun fromKey(key: String): Horizon = entries.firstOrNull { it.name == key } ?: SWING
    }
}

/**
 * Расчёт сделки: где стоп, сколько брать и нужно ли плечо. Всё считается от
 * допустимого убытка, а не от «хочется купить на всё» — размер позиции
 * определяется расстоянием до стопа, иначе риск на сделку неуправляем.
 */
data class PositionPlan(
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val riskPerShare: Double,
    val riskRewardRatio: Double,
    val lots: Long,
    val shares: Long,
    val positionValue: Double,
    val moneyAtRisk: Double,
    val ownFundsRequired: Double,
    val leverageUsed: Double,
    val maxLeverageAvailable: Double?,
    val notes: List<String>,
    val blocked: Boolean,
)

object RiskCalculator {

    fun plan(
        instrument: WatchedInstrument,
        profile: RiskProfile,
        lastPrice: Double,
        atr: Double?,
        stopMultiplier: Double = 1.5,
        targetMultiplier: Double = 2.5,
    ): PositionPlan? {
        if (lastPrice <= 0 || atr == null || atr <= 0) return null

        val stop = lastPrice - stopMultiplier * atr
        val target = lastPrice + targetMultiplier * atr
        val riskPerShare = lastPrice - stop
        if (riskPerShare <= 0) return null

        val notes = mutableListOf<String>()
        // Ставка риска брокера показывает, какую долю позиции нужно обеспечить
        // своими деньгами: 0.2 означает, что доступно плечо 5x.
        val maxLeverageAvailable = instrument.riskRateLong
            .takeIf { it > 0.0001 }
            ?.let { 1.0 / it }

        if (profile.capital <= 0) {
            notes += "Укажите капитал в настройках — без него размер позиции не рассчитать."
            return PositionPlan(
                entryPrice = lastPrice,
                stopPrice = stop,
                targetPrice = target,
                riskPerShare = riskPerShare,
                riskRewardRatio = (target - lastPrice) / riskPerShare,
                lots = 0,
                shares = 0,
                positionValue = 0.0,
                moneyAtRisk = 0.0,
                ownFundsRequired = 0.0,
                leverageUsed = 0.0,
                maxLeverageAvailable = maxLeverageAvailable,
                notes = notes,
                blocked = true,
            )
        }

        val allowedLoss = profile.capital * profile.riskPerTradePercent / 100.0
        val lotSize = instrument.lot.coerceAtLeast(1)
        val riskPerLot = riskPerShare * lotSize
        var lots = floor(allowedLoss / riskPerLot).toLong()

        if (lots <= 0) {
            notes += "Даже один лот превышает допустимый убыток " +
                "${fmt(allowedLoss)} — стоп слишком далеко для такого капитала."
            return PositionPlan(
                lastPrice, stop, target, riskPerShare,
                (target - lastPrice) / riskPerShare,
                0, 0, 0.0, 0.0, 0.0, 0.0, maxLeverageAvailable, notes, blocked = true,
            )
        }

        // Плечо ограничивается и настройкой пользователя, и ставкой риска брокера.
        val leverageCap = minOf(
            profile.maxLeverage.coerceAtLeast(1.0),
            maxLeverageAvailable ?: profile.maxLeverage.coerceAtLeast(1.0),
        )
        val maxPositionValue = profile.capital * leverageCap
        var positionValue = lots * lotSize * lastPrice

        if (positionValue > maxPositionValue) {
            lots = floor(maxPositionValue / (lotSize * lastPrice)).toLong()
            positionValue = lots * lotSize * lastPrice
            notes += "Размер урезан лимитом плеча ${fmt(leverageCap)}x."
        }

        if (lots <= 0) {
            notes += "Капитала не хватает даже на один лот (${fmt(lotSize * lastPrice)})."
            return PositionPlan(
                lastPrice, stop, target, riskPerShare,
                (target - lastPrice) / riskPerShare,
                0, 0, 0.0, 0.0, 0.0, 0.0, maxLeverageAvailable, notes, blocked = true,
            )
        }

        val shares = lots * lotSize
        val moneyAtRisk = shares * riskPerShare
        val leverageUsed = positionValue / profile.capital
        val ownFunds = if (leverageUsed > 1.0 && instrument.riskRateLong > 0) {
            positionValue * instrument.riskRateLong
        } else {
            positionValue
        }

        when {
            maxLeverageAvailable == null ->
                notes += "Ставка риска по бумаге неизвестна — считайте, что плечо недоступно."
            leverageUsed > 1.01 ->
                notes += "Позиция использует плечо ${fmt(leverageUsed)}x при доступном " +
                    "${fmt(maxLeverageAvailable)}x. Плечо увеличивает и прибыль, и убыток, " +
                    "а при просадке возможен принудительный выкуп позиции брокером."
            else ->
                notes += "Позиция укладывается в собственные средства, плечо не требуется."
        }

        return PositionPlan(
            entryPrice = lastPrice,
            stopPrice = stop,
            targetPrice = target,
            riskPerShare = riskPerShare,
            riskRewardRatio = (target - lastPrice) / riskPerShare,
            lots = lots,
            shares = shares,
            positionValue = positionValue,
            moneyAtRisk = moneyAtRisk,
            ownFundsRequired = ownFunds,
            leverageUsed = leverageUsed,
            maxLeverageAvailable = maxLeverageAvailable,
            notes = notes,
            blocked = false,
        )
    }
}
