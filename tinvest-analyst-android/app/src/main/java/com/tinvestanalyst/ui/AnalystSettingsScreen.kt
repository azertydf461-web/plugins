package com.tinvestanalyst.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val INTERVALS = listOf(
    "CANDLE_INTERVAL_5_MIN",
    "CANDLE_INTERVAL_15_MIN",
    "CANDLE_INTERVAL_HOUR",
    "CANDLE_INTERVAL_DAY",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnalystSettingsScreen(viewModel: AnalystViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val search by viewModel.search.collectAsState()
    var tokenDraft by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text("Токен T-Инвестиций", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.hasToken) {
                            "Токен сохранён. Введите новый, чтобы заменить."
                        } else {
                            "Достаточно токена только для чтения — заявки приложение не отправляет."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = tokenDraft,
                        onValueChange = { tokenDraft = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        singleLine = true,
                        label = { Text("t.xxxxx...") },
                    )
                    Button(
                        onClick = {
                            viewModel.saveToken(tokenDraft)
                            tokenDraft = ""
                        },
                        enabled = tokenDraft.isNotBlank(),
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("Сохранить токен") }
                }
            }

            item {
                Column(Modifier.padding(top = 16.dp)) {
                    HorizontalDivider()
                    Text(
                        "Таймфрейм анализа",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        INTERVALS.forEach { interval ->
                            FilterChip(
                                selected = state.interval == interval,
                                onClick = { viewModel.setInterval(interval) },
                                label = { Text(intervalLabel(interval)) },
                            )
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(top = 16.dp)) {
                    HorizontalDivider()
                    Text(
                        "Добавить бумагу",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    OutlinedTextField(
                        value = search.query,
                        onValueChange = { viewModel.searchInstruments(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Тикер или название, например SBER") },
                    )
                    if (search.loading) Text("Ищу...", style = MaterialTheme.typography.labelSmall)
                    search.error?.let {
                        Text(
                            "Ошибка поиска: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            items(search.results) { instrument ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { viewModel.addInstrument(instrument) },
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "${instrument.ticker} · ${instrument.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${humanType(instrument.instrumentType)} · FIGI ${instrument.figi}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            item {
                Column(Modifier.padding(top = 16.dp)) {
                    HorizontalDivider()
                    Text(
                        "Список наблюдения (${state.rows.size})",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            items(state.rows) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.fillMaxWidth(0.8f)) {
                        Text(row.instrument.ticker, fontWeight = FontWeight.Medium)
                        Text(row.instrument.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    Text(
                        "Убрать",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable { viewModel.removeInstrument(row.instrument.figi) },
                    )
                }
            }

            item { Text("", Modifier.padding(bottom = 32.dp)) }
        }
    }
}

private fun humanType(type: String): String = when (type.lowercase()) {
    "share" -> "Акция"
    "bond" -> "Облигация"
    "etf" -> "Фонд"
    "currency" -> "Валюта"
    "futures" -> "Фьючерс"
    else -> type
}
