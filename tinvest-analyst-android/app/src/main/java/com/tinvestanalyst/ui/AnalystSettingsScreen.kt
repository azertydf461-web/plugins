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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tinvestanalyst.analysis.Horizon
import com.tinvestanalyst.data.CheckStatus
import com.tinvestanalyst.data.DiagnosticStep

private val INTERVALS = listOf(
    "CANDLE_INTERVAL_5_MIN",
    "CANDLE_INTERVAL_15_MIN",
    "CANDLE_INTERVAL_HOUR",
    "CANDLE_INTERVAL_DAY",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnalystSettingsScreen(viewModel: AnalystViewModel) {
    val state by viewModel.uiState.collectAsState()
    val steps by viewModel.diagnosticSteps.collectAsState()
    val diagnosticsRunning by viewModel.diagnosticsRunning.collectAsState()
    var tokenDraft by remember { mutableStateOf("") }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Column(Modifier.padding(top = 12.dp)) {
                Text("Токен T-Инвестиций", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.hasToken) {
                        "Токен сохранён. Введите новый, чтобы заменить."
                    } else {
                        "Нужен токен боевого контура — достаточно прав «только чтение». " +
                            "Выпускается в веб-кабинете: tbank.ru/invest/settings"
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.saveToken(tokenDraft)
                            tokenDraft = ""
                            viewModel.runDiagnostics()
                        },
                        enabled = tokenDraft.isNotBlank(),
                    ) { Text("Сохранить") }
                    OutlinedButton(
                        onClick = { viewModel.runDiagnostics() },
                        enabled = !diagnosticsRunning,
                    ) { Text("Проверить подключение") }
                }
            }
        }

        if (diagnosticsRunning) {
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Проверяю связь...", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        items(steps) { step -> DiagnosticCard(step) }

        item {
            Column(Modifier.padding(top = 16.dp)) {
                HorizontalDivider()
                Text(
                    "Таймфрейм анализа",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "На каких свечах считаются индикаторы.",
                    style = MaterialTheme.typography.bodySmall,
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
                    "Риск-профиль",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Из этих цифр считаются размер позиции, стоп и потребность в плече.",
                    style = MaterialTheme.typography.bodySmall,
                )

                NumberField(
                    label = "Капитал, ₽",
                    value = state.capital,
                    onValueChange = { viewModel.setCapital(it) },
                )
                NumberField(
                    label = "Риск на сделку, % от капитала",
                    value = state.riskPerTradePercent,
                    onValueChange = { viewModel.setRiskPerTrade(it) },
                )
                NumberField(
                    label = "Максимальное плечо (1 = без плеча)",
                    value = state.maxLeverage,
                    onValueChange = { viewModel.setMaxLeverage(it) },
                )

                Text(
                    "Горизонт",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Определяет, что важнее в итоговом выводе: техника или бизнес эмитента.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Horizon.entries.forEach { horizon ->
                        FilterChip(
                            selected = state.horizon == horizon,
                            onClick = { viewModel.setHorizon(horizon) },
                            label = { Text(horizon.title.substringBefore(" (")) },
                        )
                    }
                }
                Text(
                    "Веса: техника ${(state.horizon.technicalWeight * 100).toInt()}%, " +
                        "отчётность ${(state.horizon.fundamentalWeight * 100).toInt()}%, " +
                        "дивиденды ${(state.horizon.dividendWeight * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        item {
            Column(Modifier.padding(top = 16.dp)) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Список наблюдения (${state.rows.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.rows.isNotEmpty()) {
                        Text(
                            "Очистить всё",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clickable { viewModel.clearWatchlist() },
                        )
                    }
                }
                if (state.rows.isEmpty()) {
                    Text(
                        "Пусто. Откройте вкладку «Каталог» и выберите бумаги или целую группу.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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

/**
 * Число правится в локальном черновике и сохраняется только когда строка
 * разбирается в число: иначе стирание последней цифры сбрасывало бы
 * настройку в ноль прямо во время ввода.
 */
@Composable
private fun NumberField(label: String, value: Double, onValueChange: (Double) -> Unit) {
    var draft by remember(value) { mutableStateOf(if (value == 0.0) "" else trimZeros(value)) }
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            draft = text.replace(',', '.').filter { it.isDigit() || it == '.' }
            draft.toDoubleOrNull()?.let(onValueChange)
            if (draft.isBlank()) onValueChange(0.0)
        },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        label = { Text(label) },
    )
}

private fun trimZeros(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

@Composable
private fun DiagnosticCard(step: DiagnosticStep) {
    val color = when (step.status) {
        CheckStatus.OK -> BUY_COLOR
        CheckStatus.WARN -> STRONG_SELL_COLOR
        CheckStatus.FAIL -> MaterialTheme.colorScheme.error
    }
    val mark = when (step.status) {
        CheckStatus.OK -> "✓"
        CheckStatus.WARN -> "!"
        CheckStatus.FAIL -> "✕"
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(mark, color = color, fontWeight = FontWeight.Bold)
                Text(step.title, fontWeight = FontWeight.Medium)
            }
            Text(step.detail, style = MaterialTheme.typography.bodySmall)
            step.hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
