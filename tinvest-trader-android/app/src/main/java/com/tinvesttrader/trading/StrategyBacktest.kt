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
    /**
     * Досматривать ли пропущенные свечи на пересечения. Выключено — это
     * поведение бота до правки: он смотрел только на последнюю свечу.
     */
    val scanMissedBars: Boolean = true,
    /**
     * Стоп-заявка у брокера. Включено — стоп срабатывает в момент касания
     * уровня; выключено — только на ближайшей проверке, как было раньше.
     */
    val brokerStopOrder: Boolean = true,
    /** Считать расстояние до стопа от волатильности, а не фиксированным процентом. */
    val atrStop: Boolean = true,
    val atrMultiplier: Double = 2.0,
    val maxStopPercent: Double = 8.0,
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
    /** Те же свечи на старой логике: слепой к пропускам бот с мягким стопом в 3%. */
    val legacyTradeCount: Int,
    val legacyExpectancyPercent: Double,
    val legacyTotalReturnPercent: Double,
    val legacyWorstTradePercent: Double,
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
        // Старое поведение на тех же свечах: так видно, что именно дали
        // досмотр пропущенных свечей и стоп-заявка у брокера.
        val legacyTrades = simulate(
            strategy,
            candles,
            settings.copy(scanMissedBars = false, brokerStopOrder = false, atrStop = false),
        )
        var legacyEquity = 1.0
        legacyTrades.forEach { legacyEquity *= (1 + it.resultPercent / 100) }

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
        // Насколько глубже стопа оказался худший выход. Со стоп-заявкой это
        // цена разрывов цены, без неё — цена того, что бот смотрит на рынок
        // по расписанию.
        val nominalStopPercent = if (settings.atrStop) settings.maxStopPercent else settings.stopLossPercent
        val overshoot = stopExits.minOfOrNull { it.resultPercent + costPerTrade }
            ?.let { -it - nominalStopPercent }
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
            legacyTradeCount = legacyTrades.size,
            legacyExpectancyPercent = if (legacyTrades.isEmpty()) {
                0.0
            } else {
                legacyTrades.sumOf { it.resultPercent } / legacyTrades.size
            },
            legacyTotalReturnPercent = (legacyEquity - 1) * 100,
            legacyWorstTradePercent = legacyTrades.minOfOrNull { it.resultPercent } ?: 0.0,
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
        var stopPrice = 0.0
        var poll = settings.windowBars

        while (poll < candles.size - 1) {
            val window = candles.subList(poll - settings.windowBars + 1, poll + 1)
            val barsToScan = if (settings.scanMissedBars) step else 1
            val decision = strategy.evaluate(window, barsToScan)
            val fillPrice = candles[poll + 1].open.toDouble()

            if (entryBar < 0) {
                if (decision.signal == Signal.BUY && fillPrice > 0) {
                    entryBar = poll + 1
                    entryPrice = fillPrice
                    stopPrice = stopPriceFor(entryPrice, window, settings)
                }
                poll += step
                continue
            }

            if (settings.brokerStopOrder) {
                // Стоп-заявка живёт у брокера и срабатывает в момент касания,
                // поэтому проверяется каждая свеча интервала, а не только та,
                // на которой бот проснулся.
                val touchBar = (entryBar..minOf(poll, candles.size - 1))
                    .firstOrNull { it > entryBar && candles[it].low.toDouble() <= stopPrice }
                if (touchBar != null) {
                    // Если свеча открылась ниже стопа, брокер исполнит заявку
                    // по открытию, а не по уровню: разрыв цены не перепрыгнуть
                    // даже биржевой заявкой, и обещать обратное нельзя.
                    val fill = minOf(stopPrice, candles[touchBar].open.toDouble())
                    trades += trade(candles, entryBar, entryPrice, touchBar, fill, BotExitReason.STOP_LOSS, costPerTrade)
                    entryBar = -1
                    poll += step
                    continue
                }
            } else {
                // Мягкий стоп: бот замечает просадку только на проверке и
                // закрывается по следующей цене, какой бы она ни была.
                val lowSinceLastPoll = ((poll - step + 1).coerceAtLeast(entryBar)..poll)
                    .minOfOrNull { candles[it].low.toDouble() } ?: candles[poll].low.toDouble()
                if (lowSinceLastPoll <= stopPrice) {
                    trades += trade(candles, entryBar, entryPrice, poll + 1, fillPrice, BotExitReason.STOP_LOSS, costPerTrade)
                    entryBar = -1
                    poll += step
                    continue
                }
            }

            if (decision.signal == Signal.SELL) {
                trades += trade(candles, entryBar, entryPrice, poll + 1, fillPrice, BotExitReason.SIGNAL, costPerTrade)
                entryBar = -1
            }
            poll += step
        }

        if (entryBar >= 0) {
            val exitPrice = candles.last().close.toDouble()
            trades += trade(
                candles, entryBar, entryPrice, candles.size - 1, exitPrice,
                BotExitReason.END, costPerTrade,
            )
        }
        return trades
    }

    private fun trade(
        candles: List<Candle>,
        entryBar: Int,
        entryPrice: Double,
        exitBar: Int,
        exitPrice: Double,
        reason: BotExitReason,
        costPerTrade: Double,
    ): BotTrade = BotTrade(
        entryTime = shortTime(candles[entryBar].time),
        entryPrice = entryPrice,
        exitTime = shortTime(candles[exitBar.coerceAtMost(candles.size - 1)].time),
        exitPrice = exitPrice,
        reason = reason,
        resultPercent = (exitPrice - entryPrice) / entryPrice * 100 - costPerTrade,
        barsHeld = exitBar - entryBar,
    )

    private fun stopPriceFor(
        entryPrice: Double,
        window: List<Candle>,
        settings: BotBacktestSettings,
    ): Double {
        val atr = if (settings.atrStop) Volatility.atr(window) else null
        if (atr == null || atr <= 0) return entryPrice * (1 - settings.stopLossPercent / 100)
        val byAtr = entryPrice - settings.atrMultiplier * atr
        val floor = entryPrice * (1 - settings.maxStopPercent / 100)
        return maxOf(byAtr, floor)
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
            if (settings.brokerStopOrder) {
                "Стоп-заявка стоит у брокера и срабатывает в момент касания уровня. " +
                    "Разрыв цены она всё равно не перепрыгнет: если торги открылись ниже " +
                    "стопа, в расчёте взято именно это худшее открытие."
            } else {
                "Стоп мягкий: биржевой заявки нет, позиция закрывается на ближайшей " +
                    "проверке, и на разрыве цены убыток окажется больше заявленного."
            },
        )
        add(
            if (settings.atrStop) {
                "Расстояние до стопа считается от волатильности (${fmt(settings.atrMultiplier)} x ATR) " +
                    "с потолком ${fmt(settings.maxStopPercent)}%, а не одинаковым для всех процентом."
            } else {
                "Стоп задан фиксированным процентом ${fmt(settings.stopLossPercent)}% независимо " +
                    "от того, насколько бумага подвижна."
            },
        )
        if (settings.pollEveryNBars > 1) {
            add(
                if (settings.scanMissedBars) {
                    "Бот просыпается каждую ${settings.pollEveryNBars}-ю свечу, но досматривает " +
                        "пропущенные: сделок $tradeCount против $idealTradeCount при непрерывном " +
                        "наблюдении — остаток разницы это сигналы, отменившиеся до его пробуждения."
                } else {
                    "Бот смотрит только на последнюю свечу и пропускает пересечения: " +
                        "$tradeCount сделок против $idealTradeCount при проверке каждой свечи."
                },
            )
        }
        add(
            "Фоновая задача Android запускается не раньше чем раз в 15 минут и может " +
                "задержаться сильнее при экономии батареи. Пропуск сигналов это теперь " +
                "не ломает, но вход всё равно случится позже, чем в прогоне.",
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
