package com.tinvesttrader.ui

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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tinvesttrader.trading.DecisionAction
import com.tinvesttrader.trading.DecisionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BUY_COLOR = Color(0xFF2E7D32)
private val SELL_COLOR = Color(0xFFC62828)
private val NEUTRAL_COLOR = Color(0xFF616161)
private val WARN_COLOR = Color(0xFFE65100)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: TradingViewModel = viewModel(),
    onOpenSettings: () -> Unit,
    onOpenValidation: () -> Unit,
    onOpenCrypto: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val decisions by viewModel.decisions.collectAsState()

    // Возврат из настроек мог сменить счёт, инструмент или режим — перечитываем.
    LaunchedEffect(Unit) { viewModel.refreshFromStore() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("T-Invest Trader") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            item { ModeBanner(liveMode = state.liveMode) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Бот включён", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = state.botEnabled,
                        onCheckedChange = { viewModel.toggleBot(it) },
                        enabled = !state.killSwitchActive,
                    )
                }
            }

            if (state.killSwitchActive) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Аварийная блокировка: дневной лимит убытка превышен, новые ордера запрещены.",
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(
                                onClick = { viewModel.resetKillSwitch() },
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text("Снять блокировку") }
                        }
                    }
                }
            }

            item {
                Column {
                    val notConfigured = state.accountId.isNullOrBlank() || state.instrumentFigi.isNullOrBlank()
                    Text("Счёт: ${state.accountLabel ?: state.accountId ?: "не выбран"}")
                    Text("Инструмент: ${state.instrumentLabel ?: state.instrumentFigi ?: "не выбран"}")
                    if (notConfigured) {
                        Text(
                            "Бот не начнёт работу, пока не выбраны счёт и инструмент — откройте настройки.",
                            style = MaterialTheme.typography.bodySmall,
                            color = WARN_COLOR,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(modifier = Modifier.padding(vertical = 8.dp)) {
                        Button(
                            onClick = { viewModel.runDecisionCycleNow() },
                            enabled = !state.checkInProgress,
                        ) {
                            Text(if (state.checkInProgress) "Анализирую..." else "Проанализировать сейчас")
                        }
                        OutlinedButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("Настройки") }
                    }
                    Row {
                        OutlinedButton(onClick = onOpenValidation) { Text("Проверка бота") }
                        OutlinedButton(
                            onClick = onOpenCrypto,
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("Криптовалюта") }
                    }
                    state.statusMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Ход принятия решений", style = MaterialTheme.typography.titleMedium)
                    if (decisions.isNotEmpty()) {
                        Text(
                            "Очистить",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { viewModel.clearJournal() },
                        )
                    }
                }
            }

            if (decisions.isEmpty()) {
                item {
                    Text(
                        "Записей пока нет. Нажмите «Проанализировать сейчас», чтобы увидеть, " +
                            "как бот принимает решение, или включите бота — он проверяет рынок каждые 15 минут.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            items(decisions) { record -> DecisionCard(record) }

            item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun ModeBanner(liveMode: Boolean) {
    val (label, color) = if (liveMode) {
        "LIVE — торговля реальными деньгами" to MaterialTheme.colorScheme.error
    } else {
        "SANDBOX — виртуальный счёт, деньги не расходуются" to BUY_COLOR
    }
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            label,
            modifier = Modifier.padding(12.dp),
            color = color,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/**
 * Свёрнутая карточка показывает итог решения, развёрнутая — весь путь к нему:
 * показания индикаторов, разбор рынка, вердикт риск-контроля и результат
 * исполнения.
 */
@Composable
private fun DecisionCard(record: DecisionRecord) {
    var expanded by remember(record.timestampMillis) { mutableStateOf(false) }
    val accent = accentFor(record.decisionAction)

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    actionLabel(record.decisionAction),
                    color = accent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(formatTime(record.timestampMillis), style = MaterialTheme.typography.labelSmall)
            }
            Text(record.headline, style = MaterialTheme.typography.bodyLarge)

            if (!expanded) {
                Text(
                    "Нажмите, чтобы увидеть обоснование",
                    style = MaterialTheme.typography.labelSmall,
                    color = NEUTRAL_COLOR,
                    modifier = Modifier.padding(top = 4.dp),
                )
                return@Column
            }

            if (record.indicators.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Показания индикаторов", style = MaterialTheme.typography.labelLarge)
                record.indicators.forEach { reading ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(reading.label, style = MaterialTheme.typography.bodySmall)
                        Text(
                            reading.value,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            ReasoningBlock("Что увидел на рынке", record.marketReasoning)
            ReasoningBlock("Риск-контроль", record.riskReasoning)

            record.executionNote?.let { note ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Исполнение", style = MaterialTheme.typography.labelLarge)
                Text(note, style = MaterialTheme.typography.bodySmall, color = accent)
            }
        }
    }
}

@Composable
private fun ReasoningBlock(title: String, steps: List<String>) {
    if (steps.isEmpty()) return
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(title, style = MaterialTheme.typography.labelLarge)
    steps.forEachIndexed { index, step ->
        Text(
            "${index + 1}. $step",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun accentFor(action: DecisionAction): Color = when (action) {
    DecisionAction.BUY -> BUY_COLOR
    DecisionAction.SELL, DecisionAction.STOP_LOSS -> SELL_COLOR
    DecisionAction.BLOCKED, DecisionAction.ERROR -> WARN_COLOR
    DecisionAction.HOLD -> NEUTRAL_COLOR
}

private fun actionLabel(action: DecisionAction): String = when (action) {
    DecisionAction.BUY -> "ПОКУПКА"
    DecisionAction.SELL -> "ПРОДАЖА"
    DecisionAction.STOP_LOSS -> "СТОП-ЛОСС"
    DecisionAction.BLOCKED -> "ЗАБЛОКИРОВАНО"
    DecisionAction.ERROR -> "ОШИБКА"
    DecisionAction.HOLD -> "БЕЗ ДЕЙСТВИЙ"
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(millis))
