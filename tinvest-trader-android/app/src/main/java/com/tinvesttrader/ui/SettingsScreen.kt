package com.tinvesttrader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tinvesttrader.data.SecureTokenStore

private const val LIVE_CONFIRMATION_PHRASE = "ТОРГОВАТЬ РЕАЛЬНЫМИ ДЕНЬГАМИ"

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tokenStore = remember { SecureTokenStore(context) }

    var sandboxToken by remember { mutableStateOf(tokenStore.sandboxToken.orEmpty()) }
    var liveToken by remember { mutableStateOf(tokenStore.liveToken.orEmpty()) }
    var accountId by remember { mutableStateOf(tokenStore.accountId.orEmpty()) }
    var figi by remember { mutableStateOf(tokenStore.instrumentFigi.orEmpty()) }
    var liveEnabled by remember { mutableStateOf(tokenStore.liveTradingEnabled) }
    var showLiveConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Настройки") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {

            Text("Sandbox-токен (по умолчанию)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = sandboxToken,
                onValueChange = { sandboxToken = it; tokenStore.sandboxToken = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            Text("Live-токен (реальный счёт)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = liveToken,
                onValueChange = { liveToken = it; tokenStore.liveToken = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "Включить LIVE-режим — бот начнёт выставлять реальные ордера",
                    color = MaterialTheme.colorScheme.error,
                )
                Switch(
                    checked = liveEnabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            // Только показываем диалог подтверждения — самую настройку
                            // не меняем, пока пользователь не введёт фразу целиком.
                            showLiveConfirmDialog = true
                        } else {
                            liveEnabled = false
                            tokenStore.liveTradingEnabled = false
                        }
                    },
                )
            }

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            Text("ID счёта", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = accountId,
                onValueChange = { accountId = it; tokenStore.accountId = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Text("FIGI инструмента", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp))
            OutlinedTextField(
                value = figi,
                onValueChange = { figi = it; tokenStore.instrumentFigi = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                Text("Назад")
            }
        }
    }

    if (showLiveConfirmDialog) {
        LiveModeConfirmDialog(
            onConfirm = {
                liveEnabled = true
                tokenStore.liveTradingEnabled = true
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
