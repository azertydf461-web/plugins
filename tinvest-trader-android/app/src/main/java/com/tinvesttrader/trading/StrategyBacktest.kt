package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import kotlin.math.abs

enum class BotExitReason(val title: String) {
    SIGNAL("сигнал на продажу"),
    STOP_LOSS("стоп-лосс"),
    END("конец истории"),
    SESSION_END("закрытие дня"),
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
    /** Фильтры входа: тренд, его сила, перегретость и размах свечей. */
    val useFilters: Boolean = true,
    /**
     * Фильтры режут объём вместо того, чтобы запрещать вход. Прибыль
     * трендовой системы делают одна-две крупные сделки, поэтому отменённый
     * вход стоит дороже, чем уменьшенный.
     */
    val sizingMode: Boolean = false,
    /** Пробой канала вместо пересечения средних: вход в начале движения. */
    val donchian: Boolean = false,
    /**
     * Не входить, когда инструмент за год вырос выше порога. На таком рынке
     * система зарабатывает ноль, а держать позицию всё равно приходится —
     * см. MarketRegime.
     */
    val skipRisingMarket: Boolean = false,
    /** Подтягивать ли стоп вслед за ценой, пока сделка в прибыли. */
    val trailingStop: Boolean = true,
    /**
     * Стратегия, заданная напрямую. Перекрывает выбор по флагам выше — для
     * прогонов, где сравниваются подходы, которых флаги не описывают.
     */
    val strategy: Strategy? = null,
    /**
     * Не переносить позицию через ночь: закрывать на последней свече дня и не
     * входить на ней. Для внутридневных прогонов, где ночной разрыв цены —
     * риск, который стратегия не контролирует.
     */
    val flatAtSessionEnd: Boolean = false,
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
        intervalTitle: String,
        candles: List<Candle>,
        settings: BotBacktestSettings = BotBacktestSettings(),
    ): BotBacktestResult? {
        val warmup = settings.windowBars
        if (candles.size < warmup + 40) return null

        val trades = simulate(candles, settings)
        val idealTrades = if (settings.pollEveryNBars == 1) {
            trades
        } else {
            simulate(candles, settings.copy(pollEveryNBars = 1))
        }
        // Старое поведение на тех же свечах: так видно, что именно дали
        // досмотр пропущенных свечей и стоп-заявка у брокера.
        val legacyTrades = simulate(
            candles,
            settings.copy(
                scanMissedBars = false,
                brokerStopOrder = false,
                atrStop = false,
                useFilters = false,
                sizingMode = false,
                donchian = false,
                trailingStop = false,
            ),
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

    /**
     * Прогон идёт свеча за свечой, а решения принимаются только на тех, где
     * бот просыпается. Это не мелочь: подтянутый стоп существует лишь с
     * момента подтягивания, и применять его к свечам, которые были раньше,
     * значит закрывать сделки по уровню, которого тогда не существовало.
     */
    private fun simulate(
        candles: List<Candle>,
        settings: BotBacktestSettings,
    ): List<BotTrade> {
        val strategy: Strategy = settings.strategy ?: when {
            settings.donchian -> DonchianBreakoutStrategy()
            !settings.useFilters -> SmaCrossoverStrategy()
            settings.sizingMode -> TrendFollowingStrategy(mode = FilterMode.SIZE)
            else -> TrendFollowingStrategy()
        }
        val costPerTrade = (settings.commissionPercent + settings.spreadPercent) * 2
        val step = settings.pollEveryNBars.coerceAtLeast(1)
        val trades = mutableListOf<BotTrade>()

        var entryBar = -1
        var entryPrice = 0.0
        var entryAtr = 0.0
        var entryConviction = 1.0
        var stopPrice = 0.0
        var highWater = 0.0
        var lowSinceLastPoll = Double.MAX_VALUE
        var bar = settings.windowBars

        while (bar < candles.size - 1) {
            val isPollBar = (bar - settings.windowBars) % step == 0
            val candle = candles[bar]

            if (entryBar >= 0) {
                highWater = maxOf(highWater, candle.high.toDouble())
                lowSinceLastPoll = minOf(lowSinceLastPoll, candle.low.toDouble())

                // Стоп-заявка у брокера срабатывает в момент касания, на любой
                // свече. Разрыв цены она не перепрыгивает: если открылись ниже
                // стопа, исполнение идёт по этому худшему открытию.
                if (settings.brokerStopOrder && bar > entryBar && candle.low.toDouble() <= stopPrice) {
                    val fill = minOf(stopPrice, candle.open.toDouble())
                    trades += trade(
                        candles, entryBar, entryPrice, bar, fill,
                        BotExitReason.STOP_LOSS, costPerTrade, entryConviction,
                    )
                    entryBar = -1
                    bar++
                    continue
                }
            }

            val lastBarOfDay = settings.flatAtSessionEnd && day(candles[bar].time) != day(candles[bar + 1].time)
            if (entryBar >= 0 && lastBarOfDay) {
                trades += trade(
                    candles, entryBar, entryPrice, bar, candles[bar].close.toDouble(),
                    BotExitReason.SESSION_END, costPerTrade, entryConviction,
                )
                entryBar = -1
                bar++
                continue
            }

            if (!isPollBar) {
                bar++
                continue
            }

            val window = candles.subList(bar - settings.windowBars + 1, bar + 1)
            val decision = strategy.evaluate(window, if (settings.scanMissedBars) step else 1)
            val fillPrice = candles[bar + 1].open.toDouble()

            if (entryBar < 0) {
                val risingMarket = settings.skipRisingMarket && MarketRegime.isRising(candles, bar)
                if (decision.signal == Signal.BUY && fillPrice > 0 && !risingMarket && !lastBarOfDay) {
                    entryBar = bar + 1
                    entryPrice = fillPrice
                    entryAtr = Volatility.atr(window) ?: 0.0
                    entryConviction = decision.conviction.coerceIn(0.0, 1.0)
                    highWater = fillPrice
                    lowSinceLastPoll = Double.MAX_VALUE
                    stopPrice = stopPriceFor(entryPrice, window, settings)
                }
                bar++
                continue
            }

            // Мягкий стоп: бот замечает просадку только проснувшись и
            // закрывается по следующей цене, какой бы она ни была.
            if (!settings.brokerStopOrder && bar > entryBar && lowSinceLastPoll <= stopPrice) {
                trades += trade(candles, entryBar, entryPrice, bar + 1, fillPrice, BotExitReason.STOP_LOSS, costPerTrade, entryConviction)
                entryBar = -1
                bar++
                continue
            }
            lowSinceLastPoll = Double.MAX_VALUE

            if (decision.signal == Signal.SELL && bar > entryBar) {
                trades += trade(candles, entryBar, entryPrice, bar + 1, fillPrice, BotExitReason.SIGNAL, costPerTrade, entryConviction)
                entryBar = -1
                bar++
                continue
            }

            // Стоп подтягивается на пробуждении и только вверх — и действует
            // со следующей свечи, а не задним числом.
            if (settings.trailingStop && entryAtr > 0) {
                stopPrice = maxOf(stopPrice, highWater - settings.atrMultiplier * entryAtr)
            }
            bar++
        }

        if (entryBar >= 0) {
            val exitPrice = candles.last().close.toDouble()
            trades += trade(
                candles, entryBar, entryPrice, candles.size - 1, exitPrice,
                BotExitReason.END, costPerTrade, entryConviction,
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
        conviction: Double,
    ): BotTrade = BotTrade(
        entryTime = shortTime(candles[entryBar].time),
        entryPrice = entryPrice,
        exitTime = shortTime(candles[exitBar.coerceAtMost(candles.size - 1)].time),
        exitPrice = exitPrice,
        reason = reason,
        // Половинным объёмом получаешь половину движения и платишь половину
        // комиссии, поэтому весь результат сделки масштабируется долей.
        resultPercent = ((exitPrice - entryPrice) / entryPrice * 100 - costPerTrade) * conviction,
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
        if (settings.donchian) {
            add(
                "Периоды канала (20 на вход, 10 на выход) — классические значения, не " +
                    "подобранные под эти бумаги. Подбор дал бы результат красивее, но " +
                    "проверять его пришлось бы заново на данных, которых мы не видели.",
            )
        }
        add(
            if (settings.donchian) {
                "Фильтров входа нет: система входит на каждом пробое, риск ограничен стопом."
            } else if (settings.useFilters && settings.sizingMode) {
                "Фильтры не запрещают вход, а режут объём: непройденный фильтр уменьшает " +
                    "долю позиции, минимум треть. Пороги (ADX 20, RSI 70, средняя 50) заданы " +
                    "заранее и не подбирались под эту бумагу."
            } else if (settings.useFilters) {
                "Фильтры входа заданы заранее (ADX 20, RSI 70, длинная средняя 50) и не " +
                    "подбирались под эту бумагу. Подбор дал бы результат красивее, но " +
                    "бесполезнее: так настраивают стратегию на прошлое, а торгуют в будущем."
            } else {
                "Фильтры входа выключены: покупка на каждом пересечении, включая те, что " +
                    "случились в боковике."
            },
        )
        if (settings.trailingStop) {
            add(
                "Подтянутый стоп считается по закрытым свечам: вживую цена может " +
                    "сходить к нему и внутри свечи, и тогда выход окажется раньше.",
            )
        }
        add("Шорт не моделируется: бот только покупает и закрывает позицию, как и вживую.")
        if (tradeCount in 10..29) {
            add("Сделок $tradeCount — мало: пара удачных исходов заметно двигает всю статистику.")
        }
    }

    private fun day(time: String): String = time.take(10)

    private fun shortTime(raw: String): String = raw.take(16).replace('T', ' ').ifBlank { "—" }
}
