package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import kotlin.math.abs

enum class BotExitReason(val title: String) {
    SIGNAL("сигнал на продажу"),
    STOP_LOSS("стоп-лосс"),
    END("конец истории"),
}

data class BotTrade(
    val entryTime: String,
    val entryPrice: Double,
    val exitTime: String,
    val exitPrice: Double,
    val reason: BotExitReason,
    /** Результат с учётом комиссии и спреда, в процентах от цены входа. */
    val resultPercent: Double,
    val barsHeld: Int,
)

data class BotBacktestSettings(
    /** Комиссия брокера за одну сторону, % от оборота. */
    val commissionPercent: Double = 0.05,
    /** Половина спреда, теряемая на входе и на выходе, % от цены. */
    val spreadPercent: Double = 0.05,
    /**
     * Через сколько свечей бот просыпается. У живого бота период задаётся
     * фоновым планировщиком Android, и на пятиминутных свечах он смотрит на
     * рынок лишь каждую третью — это и моделируется.
     */
    val pollEveryNBars: Int = 3,
    val stopLossPercent: Double = 3.0,
    /** Сколько свечей бот передаёт стратегии за раз. */
    val windowBars: Int = 150,
)

data class BotBacktestResult(
    val intervalTitle: String,
    val barsTested: Int,
    val periodFrom: String,
    val periodTo: String,
    val tradeCount: Int,
    val trades: List<BotTrade>,
    val winRatePercent: Double,
    val averageWinPercent: Double,
    val averageLossPercent: Double,
    val expectancyPercent: Double,
    val profitFactor: Double?,
    val totalReturnPercent: Double,
    val buyHoldReturnPercent: Double,
    val maxDrawdownPercent: Double,
    val worstTradePercent: Double,
    val stopLossExits: Int,
    /** Насколько глубже стоп-лосса уходили закрытия: мера «мягкости» стопа. */
    val worstStopOvershootPercent: Double,
    val costPerTradePercent: Double,
    /** Сколько сделок было бы, проверяй бот каждую свечу — мера пропущенных сигналов. */
    val tradeCountIfPolledEveryBar: Int,
    val verdict: String,
    val caveats: List<String>,
)

/**
 * Прогон реальной логики бота по истории: та же [SmaCrossoverStrategy] и то
 * же правило стоп-лосса, которыми он торгует, без отдельной «модельной»
 * копии — иначе проверялась бы не та программа, которая выставляет ордера.
 *
 * Моделируется и то, как бот работает на самом деле: он просыпается по
 * расписанию, а не живёт на бирже непрерывно. Поэтому стоп-лосс здесь
 * «мягкий» — он срабатывает не в момент касания уровня, а на ближайшей
 * проверке, и закрытие может оказаться заметно ниже. Биржевой стоп-заявки
 * бот не выставляет.
 */
object StrategyBacktest {

    fun run(
        strategy: SmaCrossoverStrategy,
        intervalTitle: String,
        candles: List<Candle>,
        settings: BotBacktestSettings = BotBacktestSettings(),
    ): BotBacktestResult? {
        val warmup = settings.windowBars
        if (candles.size < warmup + 40) return null

        val trades = simulate(strategy, candles, settings)
        val idealTrades = if (settings.pollEveryNBars == 1) {
            trades
        } else {
            simulate(strategy, candles, settings.copy(pollEveryNBars = 1))
        }

        val costPerTrade = (settings.commissionPercent + settings.spreadPercent) * 2
        val wins = trades.filter { it.resultPercent > 0 }
        val losses = trades.filter { it.resultPercent <= 0 }
        val grossProfit = wins.sumOf { it.resultPercent }
        val grossLoss = abs(losses.sumOf { it.resultPercent })

        var equity = 1.0
        var peak = 1.0
        var drawdown = 0.0
        trades.forEach { trade ->
            equity *= (1 + trade.resultPercent / 100)
            if (equity > peak) peak = equity
            val current = (peak - equity) / peak * 100
            if (current > drawdown) drawdown = current
        }

        val stopExits = trades.filter { it.reason == BotExitReason.STOP_LOSS }
        // Насколько убыток по стопу вышел за заявленные 3%: именно это и есть
        // цена того, что бот смотрит на рынок по расписанию.
        val overshoot = stopExits.minOfOrNull { it.resultPercent + costPerTrade }
            ?.let { -it - settings.stopLossPercent }
            ?.coerceAtLeast(0.0) ?: 0.0

        val firstClose = candles[warmup].close.toDouble()
        val lastClose = candles.last().close.toDouble()
        val buyHold = if (firstClose > 0) (lastClose - firstClose) / firstClose * 100 else 0.0
        val expectancy = if (trades.isEmpty()) 0.0 else trades.sumOf { it.resultPercent } / trades.size

        return BotBacktestResult(
            intervalTitle = intervalTitle,
            barsTested = candles.size - warmup,
            periodFrom = shortTime(candles[warmup].time),
            periodTo = shortTime(candles.last().time),
            tradeCount = trades.size,
            trades = trades.takeLast(25).reversed(),
            winRatePercent = if (trades.isEmpty()) 0.0 else wins.size * 100.0 / trades.size,
            averageWinPercent = if (wins.isEmpty()) 0.0 else grossProfit / wins.size,
            averageLossPercent = if (losses.isEmpty()) 0.0 else -grossLoss / losses.size,
            expectancyPercent = expectancy,
            profitFactor = if (grossLoss <= 0.0001) null else grossProfit / grossLoss,
            totalReturnPercent = (equity - 1) * 100,
            buyHoldReturnPercent = buyHold,
            maxDrawdownPercent = drawdown,
            worstTradePercent = trades.minOfOrNull { it.resultPercent } ?: 0.0,
            stopLossExits = stopExits.size,
            worstStopOvershootPercent = overshoot,
            costPerTradePercent = costPerTrade,
            tradeCountIfPolledEveryBar = idealTrades.size,
            verdict = verdictFor(trades.size, expectancy, (equity - 1) * 100, buyHold, drawdown),
            caveats = caveats(settings, trades.size, idealTrades.size),
        )
    }

    private fun simulate(
        strategy: SmaCrossoverStrategy,
        candles: List<Candle>,
        settings: BotBacktestSettings,
    ): List<BotTrade> {
        val costPerTrade = (settings.commissionPercent + settings.spreadPercent) * 2
        val trades = mutableListOf<BotTrade>()
        val step = settings.pollEveryNBars.coerceAtLeast(1)

        var entryBar = -1
        var entryPrice = 0.0
        var poll = settings.windowBars

        while (poll < candles.size - 1) {
            val window = candles.subList(poll - settings.windowBars + 1, poll + 1)
            val decision = strategy.evaluate(window)
            val closeNow = candles[poll].close.toDouble()
            // Бот всегда исполняет по рынку уже после того, как увидел
            // картину, поэтому ценой сделки берётся открытие следующей свечи.
            val fillPrice = candles[poll + 1].open.toDouble()

            if (entryBar < 0) {
                if (decision.signal == Signal.BUY && fillPrice > 0) {
                    entryBar = poll + 1
                    entryPrice = fillPrice
                }
            } else {
                val dropPercent = (entryPrice - closeNow) / entryPrice * 100
                val stopTriggered = dropPercent >= settings.stopLossPercent
                if (stopTriggered || decision.signal == Signal.SELL) {
                    trades += BotTrade(
                        entryTime = shortTime(candles[entryBar].time),
                        entryPrice = entryPrice,
                        exitTime = shortTime(candles[poll + 1].time),
                        exitPrice = fillPrice,
                        reason = if (stopTriggered) BotExitReason.STOP_LOSS else BotExitReason.SIGNAL,
                        resultPercent = (fillPrice - entryPrice) / entryPrice * 100 - costPerTrade,
                        barsHeld = poll + 1 - entryBar,
                    )
                    entryBar = -1
                }
            }
            poll += step
        }

        if (entryBar >= 0) {
            val exitPrice = candles.last().close.toDouble()
            trades += BotTrade(
                entryTime = shortTime(candles[entryBar].time),
                entryPrice = entryPrice,
                exitTime = shortTime(candles.last().time),
                exitPrice = exitPrice,
                reason = BotExitReason.END,
                resultPercent = (exitPrice - entryPrice) / entryPrice * 100 - costPerTrade,
                barsHeld = candles.size - 1 - entryBar,
            )
        }
        return trades
    }

    private fun verdictFor(
        tradeCount: Int,
        expectancy: Double,
        totalReturn: Double,
        buyHold: Double,
        drawdown: Double,
    ): String = when {
        tradeCount < 10 ->
            "Сделок слишком мало ($tradeCount) — статистики нет, вывод здесь был бы случайным. " +
                "Возьмите более длинный период."
        expectancy <= 0 ->
            "Бот на этой бумаге убыточен: средняя сделка ${fmt(expectancy)}% после комиссии и спреда. " +
                "Запускать его на реальных деньгах нельзя."
        totalReturn < buyHold ->
            "Бот зарабатывает (${fmt(expectancy)}% на сделку), но проигрывает простой покупке и " +
                "удержанию: ${fmt(totalReturn)}% против ${fmt(buyHold)}%. Вы берёте на себя риск " +
                "автоматической торговли ради результата хуже, чем «купить и не трогать»."
        drawdown > 20 ->
            "Бот обгоняет удержание, но просадка доходила до ${fmt(drawdown)}%. " +
                "Уменьшайте размер позиции — на такой просадке бота обычно выключают в худшей точке."
        else ->
            "На этой истории бот работал: ${fmt(expectancy)}% на сделку после издержек, " +
                "${fmt(totalReturn)}% против ${fmt(buyHold)}% у простой покупки, просадка ${fmt(drawdown)}%. " +
                "Это прошлое, а не гарантия будущего."
    }

    private fun caveats(
        settings: BotBacktestSettings,
        tradeCount: Int,
        idealTradeCount: Int,
    ): List<String> = buildList {
        add(
            "Стоп-лосс у бота мягкий: биржевой стоп-заявки он не выставляет, а закрывает " +
                "позицию на ближайшей проверке. На разрыве цены убыток окажется больше " +
                "заявленных ${fmt(settings.stopLossPercent)}%.",
        )
        if (settings.pollEveryNBars > 1) {
            add(
                "Бот смотрит на рынок каждую ${settings.pollEveryNBars}-ю свечу. Проверяй он каждую, " +
                    "сделок было бы $idealTradeCount вместо $tradeCount — разница и есть " +
                    "пропущенные пересечения.",
            )
        }
        add(
            "Фоновая задача Android запускается не раньше чем раз в 15 минут и может " +
                "задержаться сильнее при экономии батареи, так что вживую пропусков будет больше.",
        )
        add(
            "Учтены комиссия и спред, но не проскальзывание, частичное исполнение и налог. " +
                "Сделки считаются по открытию следующей свечи, а не по реальной цене исполнения.",
        )
        add("Шорт не моделируется: бот только покупает и закрывает позицию, как и вживую.")
        if (tradeCount in 10..29) {
            add("Сделок $tradeCount — мало: пара удачных исходов заметно двигает всю статистику.")
        }
    }

    private fun shortTime(raw: String): String = raw.take(16).replace('T', ' ').ifBlank { "—" }
}
