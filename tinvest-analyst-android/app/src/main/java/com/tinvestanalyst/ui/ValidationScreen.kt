package com.tinvestanalyst.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tinvestanalyst.analysis.BacktestResult
import com.tinvestanalyst.analysis.fmt
import com.tinvestanalyst.data.RecommendationOutcome
import com.tinvestanalyst.data.RecommendationRecord

private enum class ValidationTab(val title: String) {
    JOURNAL("Журнал советов"),
    BACKTEST("Проверка на истории"),
}

/**
 * Экран, который проверяет само приложение. Журнал отвечает на вопрос «сбылось
 * ли то, что оно уже советовало», бэктест — «работала ли эта логика раньше».
 * Без обоих ответов любые индикаторы остаются просто красивыми графиками.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ValidationScreen(viewModel: AnalystViewModel) {
    var tab by remember { mutableStateOf(ValidationTab.JOURNAL) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            FlowRow(
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ValidationTab.entries.forEach { entry ->
                    FilterChip(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        label = { Text(entry.title) },
                    )
                }
            }
        }

        when (tab) {
            ValidationTab.JOURNAL -> journalSection(viewModel)
            ValidationTab.BACKTEST -> backtestSection(viewModel)
        }

        item { Text("", Modifier.padding(bottom = 32.dp)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.journalSection(viewModel: AnalystViewModel) {
    item {
        val records by viewModel.journalRecords.collectAsState()
        val stats = remember(records) { viewModel.journalStats() }

        Column {
            Text(
                "Каждый вердикт «покупать» или «продавать» записывается вместе с ценой, " +
                    "а дальше по котировкам видно, чем он закончился. Это единственный " +
                    "способ понять, стоит ли верить советам приложения.",
                style = MaterialTheme.typography.bodySmall,
            )

            Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Что вышло из советов", style = MaterialTheme.typography.titleMedium)
                    if (stats.total == 0) {
                        Text(
                            "Пока пусто. Записи появятся после первого сканирования, " +
                                "если хоть по одной бумаге будет вердикт кроме «держать».",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        StatRow("Всего советов", stats.total.toString())
                        StatRow("Завершено", stats.closed.toString())
                        StatRow("Дошли до цели", stats.reachedTarget.toString(), BUY_COLOR)
                        StatRow("Выбило стопом", stats.stopped.toString(), SELL_COLOR)
                        StatRow("Истёк срок", stats.expired.toString())
                        if (stats.closed > 0) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            StatRow(
                                "Доля сбывшихся",
                                "${fmt(stats.hitRatePercent)}%",
                                if (stats.hitRatePercent >= 50) BUY_COLOR else SELL_COLOR,
                            )
                            StatRow(
                                "Средний результат",
                                "${fmt(stats.averageResultPercent)}%",
                                if (stats.averageResultPercent >= 0) BUY_COLOR else SELL_COLOR,
                            )
                        }
                        if (stats.closed < 10) {
                            Text(
                                "Завершённых советов меньше десяти — статистике верить рано, " +
                                    "такая выборка не отличает работающую логику от везения.",
                                style = MaterialTheme.typography.labelSmall,
                                color = STRONG_SELL_COLOR,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Text(
                            "Судьба совета отслеживается по опросу цены раз в несколько секунд, " +
                                "а не поминутно: короткий заход к стопу между опросами журнал не увидит.",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            if (records.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("История советов", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Очистить",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable { viewModel.clearJournal() },
                    )
                }
            }

            records.reversed().take(60).forEach { record -> JournalCard(record) }
        }
    }
}

@Composable
private fun JournalCard(record: RecommendationRecord) {
    val bullish = record.verdict.contains("BUY")
    // Для совета «продавать» удачей считается падение цены, поэтому знак
    // результата разворачивается — иначе успешный совет выглядел бы убытком.
    val effective = if (bullish) record.resultPercent else -record.resultPercent
    val color = when (record.outcomeEnum) {
        RecommendationOutcome.TARGET -> BUY_COLOR
        RecommendationOutcome.STOP -> SELL_COLOR
        RecommendationOutcome.EXPIRED -> HOLD_COLOR
        RecommendationOutcome.OPEN -> if (effective >= 0) BUY_COLOR else SELL_COLOR
    }

    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.fillMaxWidth(0.6f)) {
                    Text(record.ticker, fontWeight = FontWeight.Bold)
                    Text(
                        "${record.verdictLabel} · ${formatClock(record.createdAtMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    record.outcomeEnum.title,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                "Цена совета ${fmt(record.priceAtIssue)} → сейчас ${fmt(record.lastPrice)} " +
                    "(${if (effective >= 0) "+" else ""}${fmt(effective)}%)",
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (record.stopPrice != null && record.targetPrice != null) {
                Text(
                    "План был: стоп ${fmt(record.stopPrice)}, цель ${fmt(record.targetPrice)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.backtestSection(viewModel: AnalystViewModel) {
    item {
        val state by viewModel.uiState.collectAsState()
        val backtest by viewModel.backtest.collectAsState()
        val selected = backtest.figi ?: state.rows.firstOrNull()?.instrument?.figi

        Column {
            Text(
                "Прогон той же логики, что стоит за вердиктом, по историческим свечам: " +
                    "заработала бы она на этой бумаге или нет. Вход считается по " +
                    "следующей свече, комиссия и спред вычитаются, а при касании " +
                    "и стопа, и цели за одну свечу засчитывается стоп.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (state.rows.isEmpty()) {
                Text(
                    "Добавьте бумагу в наблюдение — проверять пока нечего.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                return@Column
            }

            Text(
                "Бумага",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.rows.take(30).forEach { row ->
                    FilterChip(
                        selected = selected == row.instrument.figi,
                        onClick = { viewModel.selectBacktestInstrument(row.instrument.figi) },
                        label = { Text(row.instrument.ticker) },
                    )
                }
            }

            Text(
                "Глубина истории",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BacktestRange.entries.forEach { range ->
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
                modifier = Modifier.padding(top = 12.dp),
            ) { Text(if (backtest.running) "Считаю..." else "Прогнать на истории") }

            if (backtest.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                backtest.stage?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "Это может занять до минуты: история качается кусками, а индикаторы " +
                        "пересчитываются заново на каждой свече.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            backtest.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            backtest.result?.let { BacktestResultCard(it) }
        }
    }
}

@Composable
private fun BacktestResultCard(result: BacktestResult) {
    val positive = result.expectancyPercent > 0 && result.totalReturnPercent >= result.buyHoldReturnPercent

    Column(Modifier.padding(top = 12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "${result.ticker} · ${result.intervalTitle}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${result.periodFrom} — ${result.periodTo}, ${result.barsTested} свечей",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    result.verdict,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (positive) BUY_COLOR else SELL_COLOR,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Цифры прогона", style = MaterialTheme.typography.titleMedium)
                StatRow("Сделок", result.tradeCount.toString())
                StatRow(
                    "Средняя сделка (матожидание)",
                    "${fmt(result.expectancyPercent)}%",
                    if (result.expectancyPercent >= 0) BUY_COLOR else SELL_COLOR,
                )
                StatRow("Доля прибыльных", "${fmt(result.winRatePercent)}%")
                StatRow("Средняя прибыль", "${fmt(result.averageWinPercent)}%", BUY_COLOR)
                StatRow("Средний убыток", "${fmt(result.averageLossPercent)}%", SELL_COLOR)
                StatRow(
                    "Профит-фактор",
                    result.profitFactor?.let { fmt(it) } ?: "убыточных сделок не было",
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                StatRow(
                    "Итог стратегии",
                    "${fmt(result.totalReturnPercent)}%",
                    if (result.totalReturnPercent >= 0) BUY_COLOR else SELL_COLOR,
                )
                StatRow(
                    "Купить и держать",
                    "${fmt(result.buyHoldReturnPercent)}%",
                    if (result.buyHoldReturnPercent >= 0) BUY_COLOR else SELL_COLOR,
                )
                StatRow("Максимальная просадка", "${fmt(result.maxDrawdownPercent)}%", SELL_COLOR)
                StatRow("Среднее удержание", "${fmt(result.averageBarsHeld)} свечей")
                StatRow("Издержки на сделку", "${fmt(result.costPerTradePercent)}%")
            }
        }

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

        if (result.trades.isNotEmpty()) {
            Text(
                "Последние сделки",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            if (result.tradeCount > result.trades.size) {
                Text(
                    "Показаны последние ${result.trades.size} из ${result.tradeCount}.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            result.trades.forEach { trade ->
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
                            "вход ${fmt(trade.entryPrice)} · выход ${fmt(trade.exitPrice)} · " +
                                "${trade.barsHeld} свечей",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        "${if (trade.resultPercent >= 0) "+" else ""}${fmt(trade.resultPercent)}%",
                        color = if (trade.resultPercent >= 0) BUY_COLOR else SELL_COLOR,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, color: androidx.compose.ui.graphics.Color? = null) {
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
