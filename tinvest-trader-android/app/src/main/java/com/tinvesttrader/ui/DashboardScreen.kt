package com.tinvesttrader.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tinvesttrader.trading.EngineEvent

@Composable
fun DashboardScreen(
    viewModel: TradingViewModel = viewModel(),
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("T-Invest Trader") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {

            ModeBanner(liveMode = state.liveMode)

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

            if (state.killSwitchActive) {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Kill switch активен: дневной лимит убытка превышен, новые ордера заблокированы.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = { viewModel.resetKillSwitch() }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Сбросить (осознанно)")
                        }
                    }
                }
            }

            Text("Счёт: ${state.accountId ?: "не выбран"}")
            Text("Инструмент (FIGI): ${state.instrumentFigi ?: "не выбран"}")

            Button(onClick = onOpenSettings, modifier = Modifier.padding(vertical = 8.dp)) {
                Text("Настройки / токены")
            }
            Button(onClick = { viewModel.runTickNow() }, modifier = Modifier.padding(bottom = 8.dp)) {
                Text("Проверить подключение")
            }
            state.statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Text("Журнал сигналов и ордеров", style = MaterialTheme.typography.titleMedium)
            LazyColumn {
                items(state.events) { event -> EventRow(event) }
            }
        }
    }
}

@Composable
private fun ModeBanner(liveMode: Boolean) {
    val (label, color) = if (liveMode) {
        "LIVE — торговля реальными деньгами" to MaterialTheme.colorScheme.error
        } else {
        "SANDBOX — виртуальный счёт, деньги не расходуются" to Color(0xFF2E7D32)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.padding(12.dp),
            color = color,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun EventRow(event: EngineEvent) {
    val text = when (event) {
        is EngineEvent.Signal -> "Сигнал: ${event.signal} по цене ${event.price}"
        is EngineEvent.OrderPlaced -> "Ордер ${event.direction} ${event.lots} лот(ов), id=${event.orderId}"
        is EngineEvent.RiskBlocked -> "Заблокировано риск-менеджером: ${event.reason}"
        is EngineEvent.Error -> "Ошибка: ${event.message}"
        EngineEvent.Idle -> "Нет действия"
    }
    val color = when (event) {
        is EngineEvent.Error, is EngineEvent.RiskBlocked -> MaterialTheme.colorScheme.error
        is EngineEvent.OrderPlaced -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(text, color = color, modifier = Modifier.padding(vertical = 4.dp))
}
