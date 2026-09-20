package com.tinvestanalyst.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tinvestanalyst.analysis.fmt

/**
 * Ранжированный список идей: те же бумаги из наблюдения, отсортированные по
 * силе сводного сигнала. Сканируется именно список наблюдения, а не вся
 * биржа: каждая бумага стоит запроса свечей, и «просканировать всё» упёрлось
 * бы в лимиты API.
 */
@Composable
fun IdeasScreen(viewModel: AnalystViewModel, onOpenInstrument: (String) -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val ideas = state.rankedIdeas

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Column(Modifier.padding(top = 12.dp)) {
                Text(
                    "Горизонт: ${state.horizon.title}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "От горизонта зависит вес блоков: на коротком решает техника, " +
                        "на длинном — отчётность и дивиденды. Меняется в настройках.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { viewModel.refreshAll() },
                        enabled = !state.isRefreshing && state.rows.isNotEmpty(),
                    ) {
                        Text(if (state.isRefreshing) "Сканирую..." else "Сканировать заново")
                    }
                    state.lastUpdateMillis?.let {
                        Text("обновлено ${formatClock(it)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                state.newsNote?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = HOLD_COLOR)
                }
                state.progress?.let { (done, total) ->
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Обработано $done из $total", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (state.rows.isEmpty()) {
            item {
                Text(
                    "Список пуст. Откройте «Каталог» и добавьте бумаги или целую группу — " +
                        "приложение проранжирует их по привлекательности.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else if (ideas.isEmpty()) {
            item {
                Text(
                    "Анализ ещё не считался. Нажмите «Сканировать заново».",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }

        items(ideas) { row ->
            val analysis = row.analysis ?: return@items
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onOpenInstrument(row.instrument.figi) },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.fillMaxWidth(0.6f)) {
                            Text(
                                row.instrument.ticker,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                row.instrument.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                analysis.verdict.label,
                                color = colorFor(analysis.verdict),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                "балл ${fmt(analysis.weightedScore)} · ${analysis.confidencePercent}%",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    Text(
                        analysis.blocks.joinToString(" · ") {
                            "${it.title.substringBefore(' ')} ${fmt(it.normalized)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    analysis.positionPlan?.takeIf { !it.blocked }?.let { plan ->
                        Text(
                            "Вход ${fmt(plan.entryPrice)} · стоп ${fmt(plan.stopPrice)} · " +
                                "цель ${fmt(plan.targetPrice)} · ${plan.lots} лот(ов)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    analysis.events.firstOrNull()?.let { event ->
                        Text(
                            "${event.title} через ${event.daysAway} дн. (${event.date})",
                            style = MaterialTheme.typography.labelSmall,
                            color = STRONG_SELL_COLOR,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        item { Text("", Modifier.padding(bottom = 24.dp)) }
    }
}
