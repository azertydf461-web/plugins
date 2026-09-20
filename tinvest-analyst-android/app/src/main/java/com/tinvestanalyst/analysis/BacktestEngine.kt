package com.tinvestanalyst.analysis

import com.tinvestanalyst.data.Candle
import kotlin.math.abs
import kotlin.math.max

enum class ExitReason(val title: String) {
    TARGET("цель"),
    STOP("стоп"),
    SIGNAL("сигнал на выход"),
    TIME("истёк срок удержания"),
    END("конец истории"),
}

data class BacktestTrade(
    val entryTime: String,
    val entryPrice: Double,
    val exitTime: String,
    val exitPrice: Double,
    val reason: ExitReason,
    /** Результат с учётом комиссии и спреда, в процентах от цены входа. */
    val resultPercent: Double,
    val barsHeld: Int,
)

data class BacktestSettings(
    /** Комиссия брокера за одну сторону сделки, % от оборота. */
    val commissionPercent: Double = 0.05,
    /** Половина спреда, которую теряешь на входе и на выходе, % от цены. */
    val spreadPercent: Double = 0.05,
    val entryThreshold: Double = 0.30,
    val exitThreshold: Double = -0.15,
    val stopMultiplier: Double = 1.5,
    val targetMultiplier: Double = 2.5,
    val maxBarsHeld: Int = 30,
)

data class BacktestResult(
    val ticker: String,
    val intervalTitle: String,
    val barsTested: Int,
    val periodFrom: String,
    val periodTo: String,
    /** Всего сделок за прогон; в [trades] лежат только последние для показа. */
    val tradeCount: Int,
    val trades: List<BacktestTrade>,
    val winRatePercent: Double,
    val averageWinPercent: Double,
    val averageLossPercent: Double,
    /** Матожидание на сделку: главное число отчёта. Отрицательное — стратегия теряет деньги. */
    val expectancyPercent: Double,
    val profitFactor: Double?,
    val totalReturnPercent: Double,
    val buyHoldReturnPercent: Double,
    val maxDrawdownPercent: Double,
    val averageBarsHeld: Double,
    val costPerTradePercent: Double,
    val verdict: String,
    val caveats: List<String>,
)

/**
 * Прогон той же самой торговой логики, что показывается в карточке бумаги, по
 * исторническим свечам. Смысл не в красивой доходности, а в ответе на вопрос
 * «стоит ли вообще верить сигналам этого приложения на этой бумаге».
 *
 * Все допущения намеренно сдвинуты не в пользу стратегии: вход по цене
 * следующей свечи (а не по той, на которой увидели сигнал), комиссия и спред
 * на обеих сторонах, а если свеча задела и стоп, и цель — засчитывается стоп.
 * Заниженный результат честнее завышенного: на завышенный ставят деньги.
 */
object BacktestEngine {

    fun run(
        ticker: String,
        intervalTitle: String,
        candles: List<Candle>,
        settings: BacktestSettings = BacktestSettings(),
    ): BacktestResult? {
        val warmup = MarketAnalyzer.MIN_BARS + 30
        if (candles.size < warmup + 20) return null

        val trades = mutableListOf<BacktestTrade>()
        val costPerTrade = (settings.commissionPercent + settings.spreadPercent) * 2

        var index = warmup
        while (index < candles.size - 1) {
            val score = MarketAnalyzer.technicalScore(candles.subList(0, index + 1))
            if (score == null || score < settings.entryThreshold) {
                index++
                continue
            }
            val atr = Indicators.atr(candles.subList(0, index + 1))
            if (atr == null || atr <= 0) {
                index++
                continue
            }

            // Вход по открытию следующей свечи: на той свече, где сигнал
            // увиден, торговать уже поздно — её закрытие известно только
            // постфактум.
            val entryBar = index + 1
            val entryPrice = candles[entryBar].open.toDouble()
            if (entryPrice <= 0) {
                index++
                continue
            }
            val stop = entryPrice - settings.stopMultiplier * atr
            val target = entryPrice + settings.targetMultiplier * atr

            var exitBar = candles.size - 1
            var exitPrice = candles.last().close.toDouble()
            var reason = ExitReason.END

            var bar = entryBar
            while (bar < candles.size) {
                val candle = candles[bar]
                val low = candle.low.toDouble()
                val high = candle.high.toDouble()
                val held = bar - entryBar

                // Внутри свечи порядок событий неизвестен, поэтому при
                // касании обоих уровней считаем худший вариант — стоп.
                if (held > 0 && low <= stop) {
                    exitBar = bar; exitPrice = stop; reason = ExitReason.STOP; break
                }
                if (held > 0 && high >= target) {
                    exitBar = bar; exitPrice = target; reason = ExitReason.TARGET; break
                }
                if (held >= settings.maxBarsHeld) {
                    exitBar = bar; exitPrice = candle.close.toDouble(); reason = ExitReason.TIME; break
                }
                val currentScore = MarketAnalyzer.technicalScore(candles.subList(0, bar + 1))
                if (held > 0 && currentScore != null && currentScore <= settings.exitThreshold) {
                    exitBar = bar; exitPrice = candle.close.toDouble(); reason = ExitReason.SIGNAL; break
                }
                bar++
            }

            val gross = (exitPrice - entryPrice) / entryPrice * 100
            trades += BacktestTrade(
                entryTime = shortTime(candles[entryBar].time),
                entryPrice = entryPrice,
                exitTime = shortTime(candles[exitBar].time),
                exitPrice = exitPrice,
                reason = reason,
                resultPercent = gross - costPerTrade,
                barsHeld = exitBar - entryBar,
            )
            index = exitBar + 1
        }

        val wins = trades.filter { it.resultPercent > 0 }
        val losses = trades.filter { it.resultPercent <= 0 }
        val grossProfit = wins.sumOf { it.resultPercent }
        val grossLoss = abs(losses.sumOf { it.resultPercent })

        // Доходность считается сложным процентом: последовательность сделок,
        // каждая на весь капитал, — так же, как это выглядело бы на счёте.
        var equity = 1.0
        var peak = 1.0
        var maxDrawdown = 0.0
        trades.forEach { trade ->
            equity *= (1 + trade.resultPercent / 100)
            peak = max(peak, equity)
            maxDrawdown = max(maxDrawdown, (peak - equity) / peak * 100)
        }

        val firstClose = candles[warmup].close.toDouble()
        val lastClose = candles.last().close.toDouble()
        val buyHold = if (firstClose > 0) (lastClose - firstClose) / firstClose * 100 else 0.0
        val expectancy = if (trades.isEmpty()) 0.0 else trades.sumOf { it.resultPercent } / trades.size
        val totalReturn = (equity - 1) * 100

        return BacktestResult(
            ticker = ticker,
            intervalTitle = intervalTitle,
            barsTested = candles.size - warmup,
            periodFrom = shortTime(candles[warmup].time),
            periodTo = shortTime(candles.last().time),
            tradeCount = trades.size,
            trades = trades.takeLast(30).reversed(),
            winRatePercent = if (trades.isEmpty()) 0.0 else wins.size * 100.0 / trades.size,
            averageWinPercent = if (wins.isEmpty()) 0.0 else grossProfit / wins.size,
            averageLossPercent = if (losses.isEmpty()) 0.0 else -grossLoss / losses.size,
            expectancyPercent = expectancy,
            profitFactor = if (grossLoss <= 0.0001) null else grossProfit / grossLoss,
            totalReturnPercent = totalReturn,
            buyHoldReturnPercent = buyHold,
            maxDrawdownPercent = maxDrawdown,
            averageBarsHeld = if (trades.isEmpty()) 0.0 else trades.sumOf { it.barsHeld }.toDouble() / trades.size,
            costPerTradePercent = costPerTrade,
            verdict = verdictFor(trades.size, expectancy, totalReturn, buyHold, maxDrawdown),
            caveats = caveats(trades.size),
        )
    }

    /**
     * Словесный вывод пишется жёстко: пользователю нужен ответ «можно этому
     * верить или нет», а не таблица, из которой он сделает удобный ему вывод.
     */
    private fun verdictFor(
        tradeCount: Int,
        expectancy: Double,
        totalReturn: Double,
        buyHold: Double,
        drawdown: Double,
    ): String = when {
        tradeCount < 10 ->
            "Сделок слишком мало ($tradeCount) — статистики нет, любой вывод здесь случаен. " +
                "Возьмите более длинную историю или другой таймфрейм."
        expectancy <= 0 ->
            "Стратегия на этой бумаге убыточна: средняя сделка ${fmt(expectancy)}% после издержек. " +
                "Торговать по этим сигналам нельзя."
        totalReturn < buyHold ->
            "Стратегия прибыльна (${fmt(expectancy)}% на сделку), но проигрывает простой покупке " +
                "и удержанию (${fmt(totalReturn)}% против ${fmt(buyHold)}%). Смысла в активной торговле нет: " +
                "вы берёте на себя риск и издержки ради результата хуже, чем «купить и забыть»."
        drawdown > 25 ->
            "Стратегия обгоняет удержание, но просадка достигала ${fmt(drawdown)}% — " +
                "на таком падении счёта большинство выходит из стратегии в худшей точке. " +
                "Уменьшайте размер позиции."
        else ->
            "Стратегия на этой бумаге работала: ${fmt(expectancy)}% на сделку после издержек, " +
                "${fmt(totalReturn)}% против ${fmt(buyHold)}% у простой покупки, просадка ${fmt(drawdown)}%. " +
                "Это прошлое, а не обещание будущего."
    }

    private fun caveats(tradeCount: Int): List<String> = buildList {
        add(
            "Проверяется только технический блок. Отчётность, дивиденды и новости " +
                "в прошлое подставить нельзя: сегодняшние цифры отчётности в прошлом " +
                "ещё не были известны, и их подстановка дала бы фальшиво хороший результат.",
        )
        add(
            "Учтены комиссия и спред, но не учтены проскальзывание на неликвиде, " +
                "стоимость переноса маржинальной позиции и налог.",
        )
        add(
            "Пороги сигналов (вход 0.30, выход −0.15) и множители стопа заданы заранее и " +
                "не подбирались под эту бумагу. Подбор дал бы результат красивее, но бесполезнее.",
        )
        if (tradeCount in 10..29) {
            add("Сделок $tradeCount — это мало: результат сильно зависит от двух-трёх удачных исходов.")
        }
        add("Дивиденды не учтены, поэтому «купить и держать» в реальности был бы ещё выгоднее показанного.")
    }

    private fun shortTime(raw: String): String = raw.take(10).ifBlank { "—" }
}
