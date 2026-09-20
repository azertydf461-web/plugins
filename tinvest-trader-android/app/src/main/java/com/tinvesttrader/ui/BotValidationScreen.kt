package com.tinvesttrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tinvesttrader.trading.BotBacktestResult
import com.tinvesttrader.trading.BotTrade
import com.tinvesttrader.trading.ClosedTrade
import com.tinvesttrader.trading.LedgerStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GOOD_COLOR = Color(0xFF2E7D32)
private val BAD_COLOR = Color(0xFFC62828)
private val MUTED_COLOR = Color(0xFF616161)
private val WARNING_COLOR = Color(0xFFE65100)

/**
 * Экран, который проверяет самого бота: что у него уже получилось на реальных
 * сделках и что получилось бы на истории. Без этих двух ответов включать
 * автоматическую торговлю — значит ставить деньги на непроверенную программу.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BotValidationScreen(
    viewModel: TradingViewModel = viewModel(),
    onBack: () -> Unit,
) {
    val decisions by viewModel.decisions.collectAsState()
    val backtest by viewModel.backtest.collectAsState()
    val stats = remember(decisions) { viewModel.ledgerStats() }
    val trades = remember(decisions) { viewModel.closedTrades().reversed().take(30) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Проверка бота") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            item { LedgerCard(stats) }

            if (trades.isNotEmpty()) {
                item {
                    Text(
                        "Закрытые сделки бота",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(trades) { trade -> TradeRow(trade) }
            }

            item { HorizontalDivider(Modifier.padding(top = 16.dp)) }

            item {
                Column(Modifier.padding(top = 12.dp)) {
                    Text("Прогон на истории", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "По историческим свечам прогоняется та же стратегия и то же правило " +
                            "стоп-лосса, которыми бот торгует вживую — вместе с тем, что он " +
                            "смотрит на рынок по расписанию, а не непрерывно.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BotBacktestRange.entries.forEach { range ->
                            FilterChip(
                                selected = backtest.range == range,
                                onClick = { viewModel.setBacktestRange(range) },
                                label = { Text(range.title) },
                            )
                        }
                    }
                    Button(
                        onClick = { viewModel.runBacktest() },
                        enabled = !backtest.running,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(if (backtest.running) "Считаю..." else "Прогнать на истории") }

                    if (backtest.running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        backtest.stage?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        Text(
                            "Может занять до минуты: история качается кусками, а стратегия " +
                                "пересчитывается на каждой проверке заново.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    backtest.error?.let {
                        Text(
                            it,
                            color = BAD_COLOR,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            backtest.result?.let { result ->
                item { BacktestSummaryCard(result) }
                item { BacktestNumbersCard(result) }
                item { ComparisonCard(result) }
                item { CaveatsCard(result) }
                if (result.trades.isNotEmpty()) {
                    item {
                        Text(
                            "Последние сделки прогона",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(result.trades) { trade -> BacktestTradeRow(trade) }
                }
            }

            item { Text("", Modifier.padding(bottom = 32.dp)) }
        }
    }
}

@Composable
private fun LedgerCard(stats: LedgerStats) {
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Что получилось у бота на самом деле", style = MaterialTheme.typography.titleMedium)
            if (stats.closedTrades == 0) {
                Text(
                    "Закрытых сделок пока нет. Строки появятся, когда бот купит и затем " +
                        "закроет позицию — по сигналу или по стоп-лоссу.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                StatRow("Закрытых сделок", stats.closedTrades.toString())
                StatRow("Прибыльных", stats.winners.toString(), GOOD_COLOR)
                StatRow("Убыточных", stats.losers.toString(), BAD_COLOR)
                StatRow("Закрыто стоп-лоссом", stats.stopLossExits.toString(), WARNING_COLOR)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                StatRow(
                    "Средняя сделка",
                    percent(stats.averageResultPercent),
                    if (stats.averageResultPercent >= 0) GOOD_COLOR else BAD_COLOR,
                )
                StatRow("Лучшая", percent(stats.bestPercent), GOOD_COLOR)
                StatRow("Худшая", percent(stats.worstPercent), BAD_COLOR)
                StatRow(
                    "Итог по счёту",
                    percent(stats.totalReturnPercent),
                    if (stats.totalReturnPercent >= 0) GOOD_COLOR else BAD_COLOR,
                )
                if (stats.closedTrades < 10) {
                    Text(
                        "Сделок меньше десяти — по такой выборке нельзя отличить работающую " +
                            "стратегию от везения.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WARNING_COLOR,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            stats.openSinceMillis?.let { since ->
                Text(
                    "Открытая позиция с ${clock(since)}" +
                        (stats.openEntryPrice?.let { ", вход по ${money(it)}" } ?: "") +
                        " — в статистику не входит, пока не закрыта.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (stats.blockedCount > 0 || stats.errorCount > 0) {
                Text(
                    "Кроме сделок: ${stats.blockedCount} раз риск-контроль запретил ордер, " +
                        "${stats.errorCount} раз была ошибка. Бот, который чаще спотыкается, " +
                        "чем торгует, статистикой сделок не описывается.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stats.errorCount > 0) WARNING_COLOR else MUTED_COLOR,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Text(
                "Результат считается по ценам, которые бот видел в момент решения: реальную " +
                    "цену исполнения и комиссию журнал не хранит, поэтому это оценка, " +
                    "а не выписка брокера.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun BacktestSummaryCard(result: BotBacktestResult) {
    val good = result.expectancyPercent > 0 && result.totalReturnPercent >= result.buyHoldReturnPercent
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(result.intervalTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                "${result.periodFrom} — ${result.periodTo}, ${result.barsTested} свечей",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                result.verdict,
                style = MaterialTheme.typography.bodyMedium,
                color = if (good) GOOD_COLOR else BAD_COLOR,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun BacktestNumbersCard(result: BotBacktestResult) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Цифры прогона", style = MaterialTheme.typography.titleMedium)
            StatRow("Сделок", result.tradeCount.toString())
            StatRow(
                "Средняя сделка (матожидание)",
                percent(result.expectancyPercent),
                if (result.expectancyPercent >= 0) GOOD_COLOR else BAD_COLOR,
            )
            StatRow("Доля прибыльных", percent(result.winRatePercent))
            StatRow("Средняя прибыль", percent(result.averageWinPercent), GOOD_COLOR)
            StatRow("Средний убыток", percent(result.averageLossPercent), BAD_COLOR)
            StatRow("Профит-фактор", result.profitFactor?.let { money(it) } ?: "убыточных сделок не было")
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            StatRow(
                "Итог стратегии",
                percent(result.totalReturnPercent),
                if (result.totalReturnPercent >= 0) GOOD_COLOR else BAD_COLOR,
            )
            StatRow(
                "Купить и держать",
                percent(result.buyHoldReturnPercent),
                if (result.buyHoldReturnPercent >= 0) GOOD_COLOR else BAD_COLOR,
            )
            StatRow("Максимальная просадка", percent(result.maxDrawdownPercent), BAD_COLOR)
            StatRow("Худшая сделка", percent(result.worstTradePercent), BAD_COLOR)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            StatRow("Выходов по стоп-лоссу", result.stopLossExits.toString(), WARNING_COLOR)
            if (result.worstStopOvershootPercent > 0.05) {
                StatRow(
                    "Стоп пробивало глубже на",
                    percent(result.worstStopOvershootPercent),
                    WARNING_COLOR,
                )
            }
            StatRow("Издержки на сделку", percent(result.costPerTradePercent))
            if (result.tradeCountIfPolledEveryBar != result.tradeCount) {
                StatRow(
                    "Сделок при непрерывном наблюдении",
                    result.tradeCountIfPolledEveryBar.toString(),
                    MUTED_COLOR,
                )
            }
        }
    }
}

/**
 * Сравнение с прежним поведением на тех же свечах: слепой к пропускам бот с
 * мягким стопом в фиксированные 3%. Без этой колонки «стало лучше» пришлось
 * бы принимать на веру.
 */
@Composable
private fun ComparisonCard(result: BotBacktestResult) {
    val betterExpectancy = result.expectancyPercent - result.legacyExpectancyPercent
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Что дали правки", style = MaterialTheme.typography.titleMedium)
            Text(
                "Слева — как бот работает сейчас: досматривает пропущенные свечи, " +
                    "стоп-заявка у брокера, расстояние от волатильности. Справа — как " +
                    "было: только последняя свеча и мягкий стоп в 3%.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            ComparisonRow("Сделок", result.tradeCount.toString(), result.legacyTradeCount.toString())
            ComparisonRow(
                "Средняя сделка",
                percent(result.expectancyPercent),
                percent(result.legacyExpectancyPercent),
            )
            ComparisonRow(
                "Итог",
                percent(result.totalReturnPercent),
                percent(result.legacyTotalReturnPercent),
            )
            ComparisonRow(
                "Худшая сделка",
                percent(result.worstTradePercent),
                percent(result.legacyWorstTradePercent),
            )
            Text(
                when {
                    betterExpectancy > 0.05 ->
                        "Правки улучшили среднюю сделку на ${percent(betterExpectancy)}."
                    betterExpectancy < -0.05 ->
                        "На этой бумаге правки среднюю сделку ухудшили на " +
                            "${percent(-betterExpectancy)} — значит, прежние пропуски случайно " +
                            "уберегали от части убыточных входов, а не помогали."
                    else -> "На этой бумаге правки почти не изменили результат."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (betterExpectancy >= 0) GOOD_COLOR else WARNING_COLOR,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ComparisonRow(label: String, now: String, before: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row {
            Text(now, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(
                "  было $before",
                style = MaterialTheme.typography.bodySmall,
                color = MUTED_COLOR,
            )
        }
    }
}

@Composable
private fun CaveatsCard(result: BotBacktestResult) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Чего этот прогон не доказывает", style = MaterialTheme.typography.titleMedium)
            result.caveats.forEach { caveat ->
                Text(
                    "• $caveat",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun BacktestTradeRow(trade: BotTrade) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.fillMaxWidth(0.7f)) {
            Text(
                "${trade.entryTime} → ${trade.exitTime} (${trade.reason.title})",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "вход ${money(trade.entryPrice)} · выход ${money(trade.exitPrice)} · " +
                    "${trade.barsHeld} свечей",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            percent(trade.resultPercent),
            color = if (trade.resultPercent >= 0) GOOD_COLOR else BAD_COLOR,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TradeRow(trade: ClosedTrade) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.fillMaxWidth(0.7f)) {
            Text(
                "${clock(trade.entryMillis)} → ${clock(trade.exitMillis)}" +
                    if (trade.closedByStopLoss) " (стоп-лосс)" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "вход ${money(trade.entryPrice)} · выход ${money(trade.exitPrice)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            percent(trade.resultPercent),
            color = if (trade.resultPercent >= 0) GOOD_COLOR else BAD_COLOR,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StatRow(label: String, value: String, color: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun percent(value: Double): String =
    (if (value > 0) "+" else "") + String.format(Locale.US, "%.2f", value) + "%"

private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun clock(millis: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(millis))
