package com.tinvestanalyst.analysis

import com.tinvestanalyst.data.Candle
import com.tinvestanalyst.data.WatchedInstrument
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

enum class Verdict(val label: String) {
    STRONG_BUY("АКТИВНО ПОКУПАТЬ"),
    BUY("ПОКУПАТЬ"),
    HOLD("ДЕРЖАТЬ / ЖДАТЬ"),
    SELL("ПРОДАВАТЬ"),
    STRONG_SELL("АКТИВНО ПРОДАВАТЬ"),
}

/**
 * Один фактор анализа: что измерили, какие числа получили и как это
 * трактуется. [score] в диапазоне [-weight; +weight], минус — медвежий сигнал.
 */
data class AnalysisFactor(
    val name: String,
    val score: Int,
    val weight: Int,
    val reading: String,
    val interpretation: String,
)

data class MarketAnalysis(
    val instrument: WatchedInstrument,
    val lastPrice: Double,
    val changePercent: Double,
    val verdict: Verdict,
    val confidencePercent: Int,
    val totalScore: Int,
    val maxScore: Int,
    val factors: List<AnalysisFactor>,
    val summary: String,
    val volatilityNote: String,
    val suggestedStop: Double?,
    val suggestedTarget: Double?,
    val candles: List<Candle>,
    val fastSmaSeries: List<Double?>,
    val slowSmaSeries: List<Double?>,
    val generatedAtMillis: Long,
    val dataNote: String? = null,
) {
    val bullishFactors: Int get() = factors.count { it.score > 0 }
    val bearishFactors: Int get() = factors.count { it.score < 0 }
}

/**
 * Считает сводную рекомендацию как сумму независимых факторов. Каждый фактор
 * возвращает и число, и его трактовку, поэтому итог всегда разложим на
 * составляющие — пользователь видит не только «покупать», но и почему.
 *
 * Это технический анализ, а не индивидуальная инвестиционная рекомендация:
 * модель не знает ни целей, ни горизонта, ни риск-профиля инвестора.
 */
object MarketAnalyzer {

    private const val FAST_PERIOD = 9
    private const val SLOW_PERIOD = 21
    private const val LONG_PERIOD = 50

    fun analyze(instrument: WatchedInstrument, candles: List<Candle>): MarketAnalysis {
        val closes = candles.map { it.close.toDouble() }
        val lastPrice = closes.lastOrNull() ?: 0.0
        val fastSmaSeries = Indicators.smaSeries(closes, FAST_PERIOD)
        val slowSmaSeries = Indicators.smaSeries(closes, SLOW_PERIOD)

        if (closes.size < SLOW_PERIOD + 2) {
            return MarketAnalysis(
                instrument = instrument,
                lastPrice = lastPrice,
                changePercent = 0.0,
                verdict = Verdict.HOLD,
                confidencePercent = 0,
                totalScore = 0,
                maxScore = 0,
                factors = emptyList(),
                summary = "Недостаточно истории для анализа.",
                volatilityNote = "",
                suggestedStop = null,
                suggestedTarget = null,
                candles = candles,
                fastSmaSeries = fastSmaSeries,
                slowSmaSeries = slowSmaSeries,
                generatedAtMillis = System.currentTimeMillis(),
                dataNote = "Получено ${closes.size} свечей, нужно минимум ${SLOW_PERIOD + 2}. " +
                    "Возможно, биржа закрыта или инструмент малоликвиден.",
            )
        }

        val factors = buildList {
            add(trendFactor(closes))
            add(longTrendFactor(closes, lastPrice))
            rsiFactor(closes)?.let(::add)
            macdFactor(closes)?.let(::add)
            bollingerFactor(closes, lastPrice)?.let(::add)
            volumeFactor(candles, closes)?.let(::add)
        }

        val totalScore = factors.sumOf { it.score }
        val maxScore = factors.sumOf { it.weight }
        val verdict = verdictFor(totalScore, maxScore)
        val confidence = if (maxScore == 0) 0 else min(95, (abs(totalScore).toDouble() / maxScore * 100).roundToInt())

        val atr = Indicators.atr(candles)
        val atrPercent = if (atr != null && lastPrice > 0) atr / lastPrice * 100 else null
        val changePercent = if (closes.size >= 2 && closes.first() > 0) {
            (lastPrice - closes.first()) / closes.first() * 100
        } else {
            0.0
        }

        val bullish = verdict == Verdict.BUY || verdict == Verdict.STRONG_BUY
        val suggestedStop = atr?.let { if (bullish) lastPrice - 1.5 * it else null }
        val suggestedTarget = atr?.let { if (bullish) lastPrice + 2.5 * it else null }

        return MarketAnalysis(
            instrument = instrument,
            lastPrice = lastPrice,
            changePercent = changePercent,
            verdict = verdict,
            confidencePercent = confidence,
            totalScore = totalScore,
            maxScore = maxScore,
            factors = factors,
            summary = buildSummary(verdict, factors, totalScore, maxScore),
            volatilityNote = volatilityNote(atr, atrPercent),
            suggestedStop = suggestedStop,
            suggestedTarget = suggestedTarget,
            candles = candles,
            fastSmaSeries = fastSmaSeries,
            slowSmaSeries = slowSmaSeries,
            generatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun trendFactor(closes: List<Double>): AnalysisFactor {
        val fast = Indicators.sma(closes, FAST_PERIOD) ?: 0.0
        val slow = Indicators.sma(closes, SLOW_PERIOD) ?: 0.0
        val previousFast = Indicators.sma(closes.dropLast(1), FAST_PERIOD) ?: fast
        val previousSlow = Indicators.sma(closes.dropLast(1), SLOW_PERIOD) ?: slow
        val spread = if (slow == 0.0) 0.0 else (fast - slow) / slow * 100

        val crossedUp = previousFast <= previousSlow && fast > slow
        val crossedDown = previousFast >= previousSlow && fast < slow

        val score = when {
            crossedUp -> 2
            crossedDown -> -2
            spread > 0.5 -> 1
            spread < -0.5 -> -1
            else -> 0
        }
        val interpretation = when {
            crossedUp -> "Только что произошло пересечение вверх — классический сигнал на вход в лонг."
            crossedDown -> "Пересечение вниз — сигнал на выход из позиции."
            score == 1 -> "Быстрая средняя устойчиво выше медленной: восходящий тренд продолжается."
            score == -1 -> "Быстрая средняя ниже медленной: нисходящий тренд продолжается."
            else -> "Средние почти сошлись — рынок без выраженного направления."
        }
        return AnalysisFactor(
            name = "Тренд (SMA $FAST_PERIOD/$SLOW_PERIOD)",
            score = score,
            weight = 2,
            reading = "SMA$FAST_PERIOD ${fmt(fast)} / SMA$SLOW_PERIOD ${fmt(slow)}, расхождение ${fmt(spread)}%",
            interpretation = interpretation,
        )
    }

    private fun longTrendFactor(closes: List<Double>, lastPrice: Double): AnalysisFactor {
        val longSma = Indicators.sma(closes, LONG_PERIOD)
        if (longSma == null) {
            return AnalysisFactor(
                name = "Долгосрочный тренд (SMA $LONG_PERIOD)",
                score = 0,
                weight = 1,
                reading = "недостаточно истории (нужно $LONG_PERIOD свечей)",
                interpretation = "Долгосрочный ориентир пока не рассчитан, фактор не учитывается.",
            )
        }
        val deviation = (lastPrice - longSma) / longSma * 100
        val score = when {
            deviation > 1.0 -> 1
            deviation < -1.0 -> -1
            else -> 0
        }
        return AnalysisFactor(
            name = "Долгосрочный тренд (SMA $LONG_PERIOD)",
            score = score,
            weight = 1,
            reading = "цена ${fmt(lastPrice)} против SMA$LONG_PERIOD ${fmt(longSma)} (${fmt(deviation)}%)",
            interpretation = when (score) {
                1 -> "Цена выше долгосрочной средней — покупатели контролируют рынок."
                -1 -> "Цена ниже долгосрочной средней — рынок в медвежьей фазе."
                else -> "Цена колеблется вокруг долгосрочной средней."
            },
        )
    }

    private fun rsiFactor(closes: List<Double>): AnalysisFactor? {
        val rsi = Indicators.rsi(closes) ?: return null
        val score = when {
            rsi < 30 -> 2
            rsi < 45 -> 1
            rsi > 70 -> -2
            rsi > 55 -> -1
            else -> 0
        }
        val interpretation = when {
            rsi < 30 -> "Перепроданность: актив распродан, вероятен отскок вверх."
            rsi < 45 -> "Умеренная слабость — цена ближе к нижней части диапазона."
            rsi > 70 -> "Перекупленность: рост зашёл далеко, растёт риск коррекции."
            rsi > 55 -> "Умеренная перегретость — покупать по текущей цене дороговато."
            else -> "RSI в нейтральной зоне, крайностей нет."
        }
        return AnalysisFactor(
            name = "Импульс (RSI 14)",
            score = score,
            weight = 2,
            reading = "RSI ${fmt(rsi)}",
            interpretation = interpretation,
        )
    }

    private fun macdFactor(closes: List<Double>): AnalysisFactor? {
        val macd = Indicators.macd(closes) ?: return null
        val crossedUp = macd.previousHistogram <= 0 && macd.histogram > 0
        val crossedDown = macd.previousHistogram >= 0 && macd.histogram < 0
        val growing = macd.histogram > macd.previousHistogram

        val score = when {
            crossedUp -> 2
            crossedDown -> -2
            macd.histogram > 0 && growing -> 1
            macd.histogram < 0 && !growing -> -1
            else -> 0
        }
        val interpretation = when {
            crossedUp -> "MACD пересёк сигнальную линию снизу вверх — импульс развернулся в пользу роста."
            crossedDown -> "MACD ушёл под сигнальную линию — импульс сломан, рост выдыхается."
            score == 1 -> "Гистограмма положительная и растёт — восходящий импульс усиливается."
            score == -1 -> "Гистограмма отрицательная и падает — давление продавцов растёт."
            else -> "Импульс неустойчив, чёткого направления MACD не даёт."
        }
        return AnalysisFactor(
            name = "MACD (12/26/9)",
            score = score,
            weight = 2,
            reading = "линия ${fmt(macd.macdLine)}, сигнал ${fmt(macd.signalLine)}, гистограмма ${fmt(macd.histogram)}",
            interpretation = interpretation,
        )
    }

    private fun bollingerFactor(closes: List<Double>, lastPrice: Double): AnalysisFactor? {
        val bands = Indicators.bollinger(closes) ?: return null
        val percentB = bands.percentB(lastPrice)
        val score = when {
            percentB < 0.1 -> 1
            percentB > 0.9 -> -1
            else -> 0
        }
        val interpretation = when {
            percentB < 0.1 -> "Цена у нижней полосы — статистически перепродана, возможен отскок."
            percentB > 0.9 -> "Цена у верхней полосы — движение растянуто, вероятен откат."
            else -> "Цена внутри полос, экстремумов нет."
        }
        return AnalysisFactor(
            name = "Полосы Боллинджера (20, 2σ)",
            score = score,
            weight = 1,
            reading = "нижняя ${fmt(bands.lower)} / средняя ${fmt(bands.middle)} / верхняя ${fmt(bands.upper)}, " +
                "положение ${(percentB * 100).roundToInt()}%",
            interpretation = interpretation,
        )
    }

    private fun volumeFactor(candles: List<Candle>, closes: List<Double>): AnalysisFactor? {
        val ratio = Indicators.volumeRatio(candles) ?: return null
        val lastMove = if (closes.size >= 2) closes.last() - closes[closes.size - 2] else 0.0
        val elevated = ratio > 1.3
        val score = when {
            elevated && lastMove > 0 -> 1
            elevated && lastMove < 0 -> -1
            else -> 0
        }
        val interpretation = when {
            score == 1 -> "Объём выше обычного на росте — движение подтверждено деньгами."
            score == -1 -> "Повышенный объём на падении — продавцы действуют агрессивно."
            ratio < 0.7 -> "Объём ниже среднего: движению не хватает участников, сигналам верить сложнее."
            else -> "Объём около обычного уровня, дополнительной информации не даёт."
        }
        return AnalysisFactor(
            name = "Объём торгов",
            score = score,
            weight = 1,
            reading = "последний объём ${fmt(ratio)}x от среднего за 20 свечей",
            interpretation = interpretation,
        )
    }

    private fun verdictFor(totalScore: Int, maxScore: Int): Verdict {
        if (maxScore == 0) return Verdict.HOLD
        val normalized = totalScore.toDouble() / maxScore
        return when {
            normalized >= 0.55 -> Verdict.STRONG_BUY
            normalized >= 0.22 -> Verdict.BUY
            normalized <= -0.55 -> Verdict.STRONG_SELL
            normalized <= -0.22 -> Verdict.SELL
            else -> Verdict.HOLD
        }
    }

    private fun buildSummary(
        verdict: Verdict,
        factors: List<AnalysisFactor>,
        totalScore: Int,
        maxScore: Int,
    ): String {
        val bullish = factors.count { it.score > 0 }
        val bearish = factors.count { it.score < 0 }
        val neutral = factors.count { it.score == 0 }
        val base = "Итог $totalScore из возможных ±$maxScore: за рост $bullish фактор(ов), " +
            "за снижение $bearish, нейтральных $neutral."
        val tail = when (verdict) {
            Verdict.STRONG_BUY -> "Сигналы сходятся в пользу покупки."
            Verdict.BUY -> "Перевес в пользу покупки, но единогласия нет."
            Verdict.HOLD -> "Факторы уравновешены — входить в позицию нет основания."
            Verdict.SELL -> "Перевес в пользу продажи или выхода из позиции."
            Verdict.STRONG_SELL -> "Сигналы сходятся против удержания позиции."
        }
        return "$base $tail"
    }

    private fun volatilityNote(atr: Double?, atrPercent: Double?): String {
        if (atr == null || atrPercent == null) return "Волатильность не рассчитана: мало данных."
        val level = when {
            atrPercent < 0.5 -> "низкая"
            atrPercent < 1.5 -> "умеренная"
            atrPercent < 3.0 -> "повышенная"
            else -> "высокая"
        }
        return "Волатильность $level: ATR(14) = ${fmt(atr)} (${fmt(atrPercent)}% от цены). " +
            "Стоп ближе 1 ATR будет выбивать шумом."
    }
}

internal fun fmt(value: Double): String = when {
    abs(value) >= 1000 -> String.format("%.0f", value)
    abs(value) >= 1 -> String.format("%.2f", value)
    else -> String.format("%.4f", value)
}
