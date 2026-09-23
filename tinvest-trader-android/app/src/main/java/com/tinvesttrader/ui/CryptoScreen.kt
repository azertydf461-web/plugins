package com.tinvesttrader.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tinvesttrader.data.SecureTokenStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CryptoScreen(
    viewModel: CryptoViewModel = viewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val decisions by viewModel.decisions.collectAsState()
    var keyDraft by remember { mutableStateOf("") }
    var secretDraft by remember { mutableStateOf("") }
    var symbolDraft by remember(state.symbol) { mutableStateOf(state.symbol) }
    var amountDraft by remember(state.quoteAmount) { mutableStateOf(state.quoteAmount.toString()) }
    var showLiveDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Криптовалюта — Bybit") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (state.live) "БОЕВОЙ РЕЖИМ — настоящие деньги" else "Тестовая сеть Bybit — ненастоящие деньги",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Правило: покупка на пробое максимума канала, продажа под минимумом канала " +
                                "выхода. Только покупка, без плеча, без стоп-заявок. Бот продаёт только " +
                                "монеты, купленные им самим.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Ключ создавайте в кабинете Bybit с правом только на спотовую торговлю — без " +
                                "вывода и переводов. Доступность биржи, пополнение, вывод и налоги для " +
                                "резидента РФ в приложении не проверяются: выясните это до боевого режима.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            item {
                Text("Ключи биржи", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text(
                    if (state.hasKeys) "Ключи сохранены." else "Ключи не заданы.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = keyDraft, onValueChange = { keyDraft = it },
                    label = { Text("API Key") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secretDraft, onValueChange = { secretDraft = it },
                    label = { Text("API Secret") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        viewModel.saveKeys(keyDraft, secretDraft)
                        keyDraft = ""
                        secretDraft = ""
                    }) { Text("Сохранить") }
                    OutlinedButton(onClick = { viewModel.clearKeys() }, enabled = state.hasKeys) { Text("Удалить") }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Боевой режим", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = state.live,
                        onCheckedChange = { wantLive -> if (wantLive) showLiveDialog = true else viewModel.setLive(false) },
                    )
                }

                OutlinedTextField(
                    value = symbolDraft, onValueChange = { symbolDraft = it.uppercase() },
                    label = { Text("Пара, например BTCUSDT") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = amountDraft, onValueChange = { amountDraft = it },
                    label = { Text("Сумма одной покупки, USDT") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        viewModel.setSymbol(symbolDraft)
                        amountDraft.replace(',', '.').toDoubleOrNull()?.let(viewModel::setQuoteAmount)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Применить") }

                Text("Таймфрейм", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.interval == SecureTokenStore.INTERVAL_DAY,
                        onClick = { viewModel.setInterval(SecureTokenStore.INTERVAL_DAY) },
                        label = { Text("1 день, канал 20/10 (рекомендуется)") },
                    )
                    FilterChip(
                        selected = state.interval == SecureTokenStore.INTERVAL_HOUR,
                        onClick = { viewModel.setInterval(SecureTokenStore.INTERVAL_HOUR) },
                        label = { Text("1 час, канал 120/60") },
                    )
                }
            }

            item {
                HorizontalDivider(Modifier.padding(top = 16.dp))
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Криптобот включён", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = state.botEnabled, onCheckedChange = { viewModel.toggleBot(it) })
                }
                Text(
                    if (state.inBotPosition) "У бота открыта позиция." else "У бота нет позиции.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { viewModel.checkConnection() }, enabled = !state.busy) { Text("Проверить ключи") }
                    Button(onClick = { viewModel.runNow() }, enabled = !state.busy) { Text("Проверить рынок") }
                }
                state.message?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }

            item {
                Text("Журнал криптобота", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            }
            items(decisions.take(30)) { record ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(record.timestampMillis)) +
                                " · " + record.headline,
                            fontWeight = FontWeight.Bold,
                        )
                        (record.marketReasoning + record.riskReasoning).forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall)
                        }
                        record.executionNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    if (showLiveDialog) {
        LiveModeConfirmDialog(
            onConfirm = {
                viewModel.setLive(true)
                showLiveDialog = false
            },
            onDismiss = { showLiveDialog = false },
        )
    }
}
