package com.tinvestanalyst.analysis

import com.tinvestanalyst.data.AssetFundamental
import com.tinvestanalyst.data.AssetReportEvent
import com.tinvestanalyst.data.Candle
import com.tinvestanalyst.data.Dividend
import com.tinvestanalyst.data.NewsItem
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

/** Блок анализа: техника, отчётность или дивиденды. */
data class AnalysisBlock(
    val title: String,
    val factors: List<AnalysisFactor>,
    val weight: Double,
) {
    val available: Boolean get() = factors.isNotEmpty()
    val maxScore: Int get() = factors.sumOf { it.weight }
    val rawScore: Int get() = factors.sumOf { it.score }

    /** Приведённый к [-1; 1] балл, чтобы блоки разного размера были сравнимы. */
    val normalized: Double get() = if (maxScore == 0) 0.0 else rawScore.toDouble() / maxScore
}

data class MarketAnalysis(
    val instrument: WatchedInstrument,
    val lastPrice: Double,
    val changePercent: Double,
    val verdict: Verdict,
    val confidencePercent: Int,
    val weightedScore: Double,
    val blocks: List<AnalysisBlock>,
    val summary: String,
    val volatilityNote: String,
    val events: List<UpcomingEvent>,
    val positionPlan: PositionPlan?,
    val coverageNote: String,
    val news: List<ScoredNews>,
    val newsTone: Double?,
    val candles: List<Candle>,
    val fastSmaSeries: List<Double?>,
    val slowSmaSeries: List<Double?>,
    val generatedAtMillis: Long,
    val dataNote: String? = null,
) {
    val factors: List<AnalysisFactor> get() = blocks.flatMap { it.factors }
    val bullishFactors: Int get() = factors.count { it.score > 0 }
    val bearishFactors: Int get() = factors.count { it.score < 0 }
}

/**
 * Сводит вместе технический анализ, отчётность эмитента и дивиденды. Вес
 * блоков зависит от горизонта: на днях решает техника, на годах — бизнес,
 * поэтому один и тот же набор цифр даёт разные выводы спекулянту и
 * долгосрочному инвестору.
 *
 * Четвёртый блок — новостной фон: брокерский API новостей не отдаёт, поэтому
 * ленты читаются отдельно, а тональность считается по словарю. Она намеренно
 * весит меньше цены и отчётности и всегда показывается вместе с исходными
 * заголовками, чтобы вывод можно было перепроверить.
 */
object MarketAnalyzer {

    private const val FAST_PERIOD = 9
    private const val SLOW_PERIOD = 21
    private const val LONG_PERIOD = 50

    fun analyze(
        instrument: WatchedInstrument,
        candles: List<Candle>,
        fundamental: AssetFundamental? = null,
        dividends: List<Dividend> = emptyList(),
        reports: List<AssetReportEvent> = emptyList(),
        profile: RiskProfile? = null,
        news: List<NewsItem> = emptyList(),
    ): MarketAnalysis {
        val closes = candles.map { it.close.toDouble() }
        val lastPrice = closes.lastOrNull() ?: 0.0
        val fastSmaSeries = Indicators.smaSeries(closes, FAST_PERIOD)
        val slowSmaSeries = Indicators.smaSeries(closes, SLOW_PERIOD)
        val horizon = profile?.horizon ?: Horizon.SWING
        val events = DividendAnalyzer.upcomingEvents(dividends, reports)

        if (closes.size < SLOW_PERIOD + 2) {
            return MarketAnalysis(
                instrument = instrument,
                lastPrice = lastPrice,
                changePercent = 0.0,
                verdict = Verdict.HOLD,
                confidencePercent = 0,
                weightedScore = 0.0,
                blocks = emptyList(),
                summary = "Недостаточно истории для анализа.",
                volatilityNote = "",
                events = events,
                positionPlan = null,
                coverageNote = "Технический блок недоступен: мало свечей.",
                news = emptyList(),
                newsTone = null,
                candles = candles,
                fastSmaSeries = fastSmaSeries,
                slowSmaSeries = slowSmaSeries,
                generatedAtMillis = System.currentTimeMillis(),
                dataNote = "Получено ${closes.size} свечей, нужно минимум ${SLOW_PERIOD + 2}. " +
                    "Возможно, биржа закрыта или инструмент малоликвиден.",
            )
        }

        val technicalFactors = technicalFactors(candles)
        val fundamentalFactors = FundamentalAnalyzer.analyze(fundamental)
        val dividendFactors = listOfNotNull(DividendAnalyzer.analyze(dividends, fundamental))
        val newsAssessment = NewsAnalyzer.assess(instrument, news)

        val blocks = listOf(
            AnalysisBlock("Технический анализ", technicalFactors, horizon.technicalWeight),
            AnalysisBlock("Отчётность эмитента", fundamentalFactors, horizon.fundamentalWeight),
            AnalysisBlock("Дивиденды", dividendFactors, horizon.dividendWeight),
            AnalysisBlock("Новостной фон", listOfNotNull(newsAssessment?.factor), horizon.newsWeight),
        )

        // Недоступный блок не обнуляет итог, а перераспределяет вес на
        // остальные: иначе бумага без отчётности всегда выглядела бы хуже.
        val availableWeight = blocks.filter { it.available }.sumOf { it.weight }
        val weightedScore = if (availableWeight == 0.0) {
            0.0
        } else {
            blocks.filter { it.available }.sumOf { it.normalized * it.weight } / availableWeight
        }

        val atr = Indicators.atr(candles)
        val atrPercent = if (atr != null && lastPrice > 0) atr / lastPrice * 100 else null
        val changePercent = if (closes.size >= 2 && closes.first() > 0) {
            (lastPrice - closes.first()) / closes.first() * 100
        } else {
            0.0
        }

        val nearestEvent = events.firstOrNull()
        val verdict = verdictFor(weightedScore)
        val confidence = confidenceFor(weightedScore, blocks, nearestEvent)

        val plan = profile?.let {
            RiskCalculator.plan(instrument, it, lastPrice, atr)
        }

        return MarketAnalysis(
            instrument = instrument,
            lastPrice = lastPrice,
            changePercent = changePercent,
            verdict = verdict,
            confidencePercent = confidence,
            weightedScore = weightedScore,
            blocks = blocks.filter { it.available },
            summary = buildSummary(verdict, blocks, horizon, weightedScore),
            volatilityNote = volatilityNote(atr, atrPercent),
            events = events,
            positionPlan = plan,
            coverageNote = coverageNote(blocks),
            news = newsAssessment?.items.orEmpty(),
            newsTone = newsAssessment?.tone,
            candles = candles,
            fastSmaSeries = fastSmaSeries,
            slowSmaSeries = slowSmaSeries,
            generatedAtMillis = System.currentTimeMillis(),
        )
    }

    // --- Технические факторы ----------------------------------------------

    /** Минимум свечей, при котором технический блок вообще считается. */
    const val MIN_BARS = SLOW_PERIOD + 2

    /**
     * Технический блок вынесен отдельно, потому что его считает не только
     * разбор бумаги, но и бэктест — прогоняя ту же самую логику по каждой
     * исторической свече. Две копии правил разошлись бы, и проверка перестала
     * бы проверять то, что показывается пользователю.
     */
    fun technicalFactors(candles: List<Candle>): List<AnalysisFactor> {
        val closes = candles.map { it.close.toDouble() }
        if (closes.size < MIN_BARS) return emptyList()
        val lastPrice = closes.last()
        return buildList {
            add(trendFactor(closes))
            add(longTrendFactor(closes, lastPrice))
            rsiFactor(closes)?.let(::add)
            macdFactor(closes)?.let(::add)
            bollingerFactor(closes, lastPrice)?.let(::add)
            volumeFactor(candles, closes)?.let(::add)
        }
    }

    /** Тот же блок, приведённый к [-1; 1] — в этом виде его использует бэктест. */
    fun technicalScore(candles: List<Candle>): Double? {
        val factors = technicalFactors(candles)
        if (factors.isEmpty()) return null
        val max = factors.sumOf { it.weight }
        return if (max == 0) null else factors.sumOf { it.score }.toDouble() / max
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
        return AnalysisFactor("Импульс (RSI 14)", score, 2, "RSI ${fmt(rsi)}", interpretation)
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

    // --- Свод ---------------------------------------------------------------

    private fun verdictFor(weighted: Double): Verdict = when {
        weighted >= 0.45 -> Verdict.STRONG_BUY
        weighted >= 0.18 -> Verdict.BUY
        weighted <= -0.45 -> Verdict.STRONG_SELL
        weighted <= -0.18 -> Verdict.SELL
        else -> Verdict.HOLD
    }

    private fun confidenceFor(
        weighted: Double,
        blocks: List<AnalysisBlock>,
        nearestEvent: UpcomingEvent?,
    ): Int {
        val base = min(95.0, abs(weighted) * 130)
        // Чем меньше блоков доступно, тем меньше оснований доверять выводу.
        val coverage = blocks.count { it.available }.toDouble() / blocks.size
        val eventPenalty = if (nearestEvent != null && nearestEvent.daysAway <= 5) 20 else 0
        return (base * (0.6 + 0.4 * coverage)).roundToInt().minus(eventPenalty).coerceIn(0, 95)
    }

    private fun buildSummary(
        verdict: Verdict,
        blocks: List<AnalysisBlock>,
        horizon: Horizon,
        weighted: Double,
    ): String {
        val parts = blocks.filter { it.available }.joinToString("; ") { block ->
            "${block.title.lowercase()} ${signed(block.normalized)}"
        }
        val tail = when (verdict) {
            Verdict.STRONG_BUY -> "Блоки сходятся в пользу покупки."
            Verdict.BUY -> "Перевес в пользу покупки, но единогласия нет."
            Verdict.HOLD -> "Блоки уравновешены — входить в позицию нет основания."
            Verdict.SELL -> "Перевес в пользу продажи или выхода из позиции."
            Verdict.STRONG_SELL -> "Блоки сходятся против удержания позиции."
        }
        return "Горизонт «${horizon.title}», итоговый балл ${signed(weighted)} " +
            "(диапазон от -1 до +1). По блокам: $parts. $tail"
    }

    private fun coverageNote(blocks: List<AnalysisBlock>): String {
        val missing = blocks.filterNot { it.available }.map { it.title.lowercase() }
        return if (missing.isEmpty()) {
            "Учтены все блоки: техника, отчётность, дивиденды и новости."
        } else {
            "Нет данных по блокам: ${missing.joinToString(", ")}. " +
                "Вес перераспределён на остальные, вывод менее полный."
        }
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

    private fun signed(value: Double): String = (if (value >= 0) "+" else "") + fmt(value)
}

internal fun fmt(value: Double): String = when {
    abs(value) >= 1000 -> String.format("%.0f", value)
    abs(value) >= 1 -> String.format("%.2f", value)
    else -> String.format("%.4f", value)
}
