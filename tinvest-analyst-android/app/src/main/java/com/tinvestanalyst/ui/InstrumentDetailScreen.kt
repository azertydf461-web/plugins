package com.tinvestanalyst.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tinvestanalyst.analysis.AnalysisFactor
import com.tinvestanalyst.analysis.fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstrumentDetailScreen(viewModel: AnalystViewModel, onBack: () -> Unit) {
    val detail by viewModel.detail.collectAsState()
    val analysis = detail?.analysis

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(analysis?.instrument?.ticker ?: "Разбор инструмента") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (detail?.loading == true && analysis == null) {
                item { Text("Загружаю данные с биржи...", Modifier.padding(16.dp)) }
            }

            detail?.error?.let { error ->
                item {
                    Text(
                        "Ошибка: $error",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            if (analysis == null) return@LazyColumn

            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(analysis.instrument.name, style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(fmt(analysis.lastPrice), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${fmt(analysis.changePercent)}% за период",
                            color = if (analysis.changePercent >= 0) BUY_COLOR else SELL_COLOR,
                        )
                    }
                    Text(
                        listOfNotNull(
                            detail?.tradingStatus,
                            "обновлено ${formatClock(analysis.generatedAtMillis)}",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            analysis.verdict.label,
                            color = colorFor(analysis.verdict),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Уверенность ${analysis.confidencePercent}% " +
                                "(итог ${analysis.totalScore} из ±${analysis.maxScore})",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            analysis.summary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        analysis.dataNote?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            item {
                CandleChart(
                    candles = analysis.candles,
                    fastSmaSeries = analysis.fastSmaSeries,
                    slowSmaSeries = analysis.slowSmaSeries,
                )
            }

            item {
                Text(
                    "Из чего сложился вывод",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }

            items(analysis.factors) { factor -> FactorCard(factor) }

            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Риск и уровни", style = MaterialTheme.typography.titleSmall)
                        Text(
                            analysis.volatilityNote,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        analysis.suggestedStop?.let {
                            Text(
                                "Ориентир стоп-лосса: ${fmt(it)} (1.5 ATR ниже цены)",
                                style = MaterialTheme.typography.bodySmall,
                                color = SELL_COLOR,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        analysis.suggestedTarget?.let {
                            Text(
                                "Ориентир цели: ${fmt(it)} (2.5 ATR выше цены)",
                                style = MaterialTheme.typography.bodySmall,
                                color = BUY_COLOR,
                            )
                        }
                        detail?.orderBookNote?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Расчёт основан только на ценах и объёмах. Он не учитывает отчётность эмитента, " +
                        "новости, дивиденды и ваш риск-профиль. Решение принимаете вы.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun FactorCard(factor: AnalysisFactor) {
    val color = when {
        factor.score > 0 -> BUY_COLOR
        factor.score < 0 -> SELL_COLOR
        else -> HOLD_COLOR
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(factor.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${if (factor.score > 0) "+" else ""}${factor.score} / ±${factor.weight}",
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(factor.reading, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(factor.interpretation, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}
