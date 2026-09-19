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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

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
                            onClick = { viewModel.checkConnection() },
                            enabled = !state.busy,
                        ) { Text("Проверить связь") }
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
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { viewModel.searchInstruments(it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        singleLine = true,
                        label = { Text("Тикер или название, например SBER") },
                    )
                    if (state.searchBusy) {
                        Text("Ищу...", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            items(state.searchResults) { instrument ->
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
