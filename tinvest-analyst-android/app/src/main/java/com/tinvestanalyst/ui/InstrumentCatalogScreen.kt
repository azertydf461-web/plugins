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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tinvestanalyst.data.Instrument
import com.tinvestanalyst.data.InstrumentCategory

/**
 * Каталог инструментов брокера: вид актива выбирается выпадающим списком,
 * дальше можно взять в анализ как отдельную бумагу, так и всю отфильтрованную
 * группу целиком. Показываются только активы, доступные к торгам через API.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstrumentCatalogScreen(viewModel: AnalystViewModel) {
    val state by viewModel.catalog.collectAsState()
    var categoryMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (state.visible.isEmpty() && !state.loading) viewModel.loadCatalog()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        ExposedDropdownMenuBox(
            expanded = categoryMenuOpen,
            onExpandedChange = { categoryMenuOpen = !categoryMenuOpen },
            modifier = Modifier.padding(top = 12.dp),
        ) {
            OutlinedTextField(
                value = state.category.title,
                onValueChange = {},
                readOnly = true,
                label = { Text("Вид актива") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuOpen) },
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
            value = state.query,
            onValueChange = { viewModel.setCatalogQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
            label = { Text("Поиск по тикеру или названию") },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.rublesOnly,
                onClick = { viewModel.toggleRublesOnly() },
                label = { Text("Только рубли") },
            )
            Button(
                onClick = { viewModel.addVisibleGroup() },
                enabled = state.visible.isNotEmpty() && !state.loading,
            ) {
                Text("Взять группу (${state.visible.count { !state.addedFigis.contains(it.figi) }})")
            }
        }

        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            Text(
                "Загружаю каталог «${state.category.title}» с сервера брокера...",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        state.error?.let { error ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Каталог не загрузился",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(error, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Откройте вкладку «Настройки» и нажмите «Проверить подключение» — " +
                            "диагностика покажет, дело в интернете, VPN или токене.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (!state.loading && state.totalInCategory > 0) {
            Text(
                buildString {
                    append("Показано ${state.visible.size} из ${state.matchedCount}")
                    append(" · доступно к торгам ${state.totalInCategory}")
                    if (state.matchedCount > state.visible.size) append(" · уточните поиск")
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(state.visible) { instrument ->
                CatalogRow(
                    instrument = instrument,
                    added = state.addedFigis.contains(instrument.figi),
                    onToggle = { viewModel.toggleCatalogInstrument(instrument) },
                )
            }
            item { Text("", Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun CatalogRow(instrument: Instrument, added: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onToggle() },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.fillMaxWidth(0.85f)) {
                Text(
                    instrument.ticker,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(instrument.name, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Text(
                    buildString {
                        append(instrument.currency.uppercase())
                        if (instrument.lot > 1) append(" · лот ${instrument.lot}")
                        if (instrument.exchange.isNotBlank()) append(" · ${instrument.exchange}")
                        if (instrument.forQualInvestorFlag) append(" · только для квалов")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (instrument.forQualInvestorFlag) SELL_COLOR else HOLD_COLOR,
                )
            }
            Text(
                if (added) "✓" else "+",
                style = MaterialTheme.typography.headlineSmall,
                color = if (added) BUY_COLOR else MaterialTheme.colorScheme.primary,
            )
        }
    }
}
