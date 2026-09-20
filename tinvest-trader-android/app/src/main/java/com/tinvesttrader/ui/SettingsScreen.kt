package com.tinvesttrader.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tinvesttrader.data.CheckStatus
import com.tinvesttrader.data.DiagnosticStep
import com.tinvesttrader.data.InstrumentCategory

private const val LIVE_CONFIRMATION_PHRASE = "ТОРГОВАТЬ РЕАЛЬНЫМИ ДЕНЬГАМИ"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var sandboxDraft by remember { mutableStateOf("") }
    var liveDraft by remember { mutableStateOf("") }
    var showLiveConfirmDialog by remember { mutableStateOf(false) }
    var categoryMenuOpen by remember { mutableStateOf(false) }

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
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Шаг 1 — токен, шаг 2 — счёт, шаг 3 — инструмент. " +
                                "Пока не выбраны счёт и инструмент, бот ничего не делает.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            state.message?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            if (state.diagnosticsRunning) {
                item {
                    Column(Modifier.padding(top = 8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Проверяю связь...", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            items(state.diagnostics) { step -> DiagnosticCard(step) }

            // --- Шаг 1: токены -------------------------------------------------
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text("1. Токен", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.sandboxTokenSet) "Sandbox-токен сохранён" else "Sandbox-токен не задан",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    OutlinedTextField(
                        value = sandboxDraft,
                        onValueChange = { sandboxDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Новый sandbox-токен") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                viewModel.saveSandboxToken(sandboxDraft)
                                sandboxDraft = ""
                            },
                            enabled = sandboxDraft.isNotBlank(),
                        ) { Text("Сохранить") }
                        OutlinedButton(
                            onClick = { viewModel.runDiagnostics() },
                            enabled = !state.diagnosticsRunning,
                        ) { Text("Проверить подключение") }
                    }
                }
            }

            item {
                Column(Modifier.padding(top = 12.dp)) {
                    Text(
                        if (state.liveTokenSet) "Live-токен сохранён" else "Live-токен не задан",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    OutlinedTextField(
                        value = liveDraft,
                        onValueChange = { liveDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Новый live-токен (реальный счёт)") },
                    )
                    Button(
                        onClick = {
                            viewModel.saveLiveToken(liveDraft)
                            liveDraft = ""
                        },
                        enabled = liveDraft.isNotBlank(),
                    ) { Text("Сохранить live-токен") }

                    Text(
                        "LIVE-режим — бот выставляет реальные ордера",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Switch(
                        checked = state.liveMode,
                        onCheckedChange = { checked ->
                            if (checked) showLiveConfirmDialog = true else viewModel.setLiveMode(false)
                        },
                    )
                }
            }

            // --- Шаг 2: счёт ---------------------------------------------------
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    HorizontalDivider()
                    Text(
                        "2. Счёт",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        state.accountLabel?.let { "Выбран: $it (${state.accountId})" } ?: "Счёт не выбран",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Button(onClick = { viewModel.loadAccounts() }, enabled = !state.busy) {
                            Text("Загрузить счета")
                        }
                        if (!state.liveMode) {
                            OutlinedButton(
                                onClick = { viewModel.createSandboxAccount() },
                                enabled = !state.busy,
                            ) { Text("Создать счёт") }
                        }
                    }
                    if (!state.liveMode) {
                        Text(
                            "В песочнице счёт нужно создать — он появляется только после этого " +
                                "и сразу пополняется виртуальным миллионом рублей.",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            items(state.accounts) { account ->
                val selected = account.id == state.accountId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { viewModel.selectAccount(account.id, account.name.ifBlank { "Счёт" }) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.fillMaxWidth(0.85f)) {
                            Text(
                                account.name.ifBlank { "Счёт без названия" },
                                fontWeight = FontWeight.Medium,
                            )
                            Text(account.id, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(if (selected) "✓" else "", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            // --- Шаг 3: инструмент ---------------------------------------------
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    HorizontalDivider()
                    Text(
                        "3. Инструмент",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        state.instrumentLabel ?: "Инструмент не выбран",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )

                    ExposedDropdownMenuBox(
                        expanded = categoryMenuOpen,
                        onExpandedChange = { categoryMenuOpen = !categoryMenuOpen },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.category.title,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Вид актива") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuOpen)
                            },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = categoryMenuOpen,
                            onDismissRequest = { categoryMenuOpen = false },
                        ) {
                            InstrumentCategory.entries.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.title) },
                                    onClick = {
                                        categoryMenuOpen = false
                                        viewModel.loadCatalog(category)
                                    },
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { viewModel.filterCatalog(it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true,
                        label = { Text("Поиск по тикеру или названию") },
                    )

                    if (state.catalogBusy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        Text("Загружаю каталог...", style = MaterialTheme.typography.labelSmall)
                    } else if (state.catalogTotal == 0) {
                        Button(
                            onClick = { viewModel.loadCatalog() },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Показать список активов") }
                    } else {
                        Text(
                            "Показано ${state.catalog.size} из ${state.catalogMatched} " +
                                "· доступно к торгам ${state.catalogTotal}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            items(state.catalog) { instrument ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { viewModel.selectInstrument(instrument) },
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "${instrument.ticker} · ${instrument.name}",
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            buildString {
                                append(instrument.currency.uppercase())
                                if (instrument.lot > 1) append(" · лот ${instrument.lot}")
                                if (instrument.forQualInvestorFlag) append(" · только для квалов")
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            item { RiskSettingsSection(state, viewModel) }

            item {
                Button(onClick = onBack, modifier = Modifier.padding(vertical = 24.dp)) {
                    Text("Готово")
                }
            }
        }
    }

    if (showLiveConfirmDialog) {
        LiveModeConfirmDialog(
            onConfirm = {
                viewModel.setLiveMode(true)
                showLiveConfirmDialog = false
            },
            onDismiss = { showLiveConfirmDialog = false },
        )
    }
}

private val BOT_INTERVALS = listOf(
    "CANDLE_INTERVAL_15_MIN" to "15 минут (рекомендуется)",
    "CANDLE_INTERVAL_HOUR" to "1 час",
    "CANDLE_INTERVAL_5_MIN" to "5 минут",
)

/**
 * Раздел про риск и таймфрейм. Вынесен в настройки, потому что оба параметра
 * меняют поведение бота сильнее, чем что-либо ещё: слишком мелкие свечи он
 * не успевает отсматривать, а стоп определяет, сколько он теряет на неудачной
 * сделке.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RiskSettingsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(Modifier.padding(top = 16.dp)) {
        HorizontalDivider()
        Text(
            "Таймфрейм бота",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "Бот просыпается раз в 15 минут — это ограничение Android. Свечи мельче " +
                "этого он видит не все, поэтому 5 минут оставлены только для " +
                "сравнения на истории.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BOT_INTERVALS.forEach { (value, title) ->
                FilterChip(
                    selected = state.candleInterval == value,
                    onClick = { viewModel.setCandleInterval(value) },
                    label = { Text(title) },
                )
            }
        }

        Text(
            "Стоп-лосс",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.stopMode == "ATR",
                onClick = { viewModel.setStopMode("ATR") },
                label = { Text("По волатильности") },
            )
            FilterChip(
                selected = state.stopMode == "PERCENT",
                onClick = { viewModel.setStopMode("PERCENT") },
                label = { Text("Фиксированный %") },
            )
        }
        Text(
            if (state.stopMode == "ATR") {
                "Расстояние до стопа считается от размаха свечей этой бумаги. " +
                    "Одинаковый для всех процент либо режет позицию на обычном шуме, " +
                    "либо пропускает реальное падение."
            } else {
                "Одно и то же расстояние для любой бумаги — просто, но не учитывает, " +
                    "насколько она подвижна."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        if (state.stopMode == "ATR") {
            NumberSetting(
                label = "Множитель ATR",
                value = state.atrMultiplier,
                onValueChange = { viewModel.setAtrMultiplier(it) },
            )
        } else {
            NumberSetting(
                label = "Стоп-лосс, % от цены входа",
                value = state.stopLossPercent,
                onValueChange = { viewModel.setStopLossPercent(it) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Стоп-заявка у брокера", style = MaterialTheme.typography.titleSmall)
            Switch(
                checked = state.protectiveStopEnabled,
                onCheckedChange = { viewModel.setProtectiveStopEnabled(it) },
            )
        }
        Text(
            "Включено: после покупки бот выставляет стоп-заявку на сервере брокера, " +
                "и она срабатывает сама, даже когда приложение выгружено из памяти. " +
                "Выключено: стоп сработает только на очередной проверке — на разрыве " +
                "цены убыток окажется больше. В песочнице стоп-заявки может не быть, " +
                "тогда бот честно напишет об этом в журнале.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Значение правится в черновике: стирание последней цифры не должно обнулять настройку. */
@Composable
private fun NumberSetting(label: String, value: Double, onValueChange: (Double) -> Unit) {
    var draft by remember(value) { mutableStateOf(if (value == 0.0) "" else value.toString()) }
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            draft = text.replace(',', '.').filter { it.isDigit() || it == '.' }
            draft.toDoubleOrNull()?.let(onValueChange)
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        label = { Text(label) },
    )
}

@Composable
private fun DiagnosticCard(step: DiagnosticStep) {
    val color = when (step.status) {
        CheckStatus.OK -> Color(0xFF2E7D32)
        CheckStatus.WARN -> Color(0xFFE65100)
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

/**
 * Живой режим торгует реальными деньгами — включение требует набрать
 * фразу целиком, а не просто нажать "ОК". Это сознательное трение,
 * а не UX-недоработка: случайный тап не должен переключать реальный счёт.
 */
@Composable
private fun LiveModeConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подтвердите включение LIVE-режима") },
        text = {
            Column {
                Text(
                    "Бот будет выставлять реальные ордера на вашем брокерском счёте. " +
                        "Вы можете потерять деньги. Введите фразу \"$LIVE_CONFIRMATION_PHRASE\", " +
                        "чтобы продолжить.",
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = input.trim() == LIVE_CONFIRMATION_PHRASE,
            ) { Text("Включить LIVE") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
