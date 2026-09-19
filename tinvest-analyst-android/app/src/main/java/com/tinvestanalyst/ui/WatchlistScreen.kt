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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tinvestanalyst.analysis.Verdict
import com.tinvestanalyst.analysis.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val STRONG_BUY_COLOR = Color(0xFF1B5E20)
val BUY_COLOR = Color(0xFF2E7D32)
val HOLD_COLOR = Color(0xFF616161)
val SELL_COLOR = Color(0xFFC62828)
val STRONG_SELL_COLOR = Color(0xFFB71C1C)

fun colorFor(verdict: Verdict): Color = when (verdict) {
    Verdict.STRONG_BUY -> STRONG_BUY_COLOR
    Verdict.BUY -> BUY_COLOR
    Verdict.HOLD -> HOLD_COLOR
    Verdict.SELL -> SELL_COLOR
    Verdict.STRONG_SELL -> STRONG_SELL_COLOR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    viewModel: AnalystViewModel,
    onOpenSettings: () -> Unit,
    onOpenInstrument: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    // Цены тикают, только пока экран открыт — фоновый опрос биржи без нужды
    // сажал бы батарею и упирался в лимиты API.
    DisposableEffect(Unit) {
        viewModel.startPricePolling()
        onDispose { viewModel.stopPricePolling() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Аналитик рынка") }) },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        "Технический анализ для самостоятельного решения. Приложение не отправляет " +
                            "заявки и не является индивидуальной инвестиционной рекомендацией.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (!state.hasToken) {
                item {
                    Column(Modifier.padding(vertical = 16.dp)) {
                        Text("Токен не задан", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Вставьте токен T-Инвестиций в настройках — достаточно токена только для чтения.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Открыть настройки")
                        }
                    }
                }
                return@LazyColumn
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.refreshAll() },
                        enabled = !state.isRefreshing,
                    ) {
                        Text(if (state.isRefreshing) "Считаю..." else "Пересчитать анализ")
                    }
                    OutlinedButton(onClick = onOpenSettings) { Text("Настройки") }
                }
            }

            item {
                Column(Modifier.padding(bottom = 8.dp)) {
                    state.lastUpdateMillis?.let {
                        Text(
                            "Обновлено: ${formatClock(it)} · интервал свечей ${intervalLabel(state.interval)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    state.error?.let {
                        Text(
                            "Ошибка запроса: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (state.rows.isEmpty()) {
                item {
                    Text(
                        "Список наблюдения пуст. Добавьте бумаги в настройках — найти можно по тикеру, " +
                            "например SBER или GAZP.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            items(state.rows) { row ->
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
                            Column(Modifier.fillMaxWidth(0.55f)) {
                                Text(
                                    row.instrument.ticker,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    row.instrument.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(
                                    row.lastPrice?.let(::fmt) ?: "—",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                row.analysis?.let { analysis ->
                                    Text(
                                        "${fmt(analysis.changePercent)}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (analysis.changePercent >= 0) BUY_COLOR else SELL_COLOR,
                                    )
                                }
                            }
                        }

                        val analysis = row.analysis
                        if (analysis == null) {
                            Text(
                                "Анализ ещё не рассчитан",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    analysis.verdict.label,
                                    color = colorFor(analysis.verdict),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    "уверенность ${analysis.confidencePercent}%",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Text(
                                "За рост ${analysis.bullishFactors}, за снижение ${analysis.bearishFactors} " +
                                    "· нажмите для разбора",
                                style = MaterialTheme.typography.labelSmall,
                                color = HOLD_COLOR,
                            )
                        }
                    }
                }
            }

            item { Text("", Modifier.padding(bottom = 24.dp)) }
        }
    }
}

fun formatClock(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

fun intervalLabel(interval: String): String = when (interval) {
    "CANDLE_INTERVAL_1_MIN" -> "1 мин"
    "CANDLE_INTERVAL_5_MIN" -> "5 мин"
    "CANDLE_INTERVAL_15_MIN" -> "15 мин"
    "CANDLE_INTERVAL_HOUR" -> "1 час"
    "CANDLE_INTERVAL_DAY" -> "1 день"
    else -> interval
}
