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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tinvestanalyst.data.Instrument
import com.tinvestanalyst.data.InstrumentCategory

/**
 * Каталог инструментов брокера: пользователь выбирает бумаги из готового
 * списка вместо того, чтобы угадывать тикеры. Показываются только активы,
 * доступные к торгам через API, — остальное отфильтровано в репозитории.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InstrumentCatalogScreen(viewModel: AnalystViewModel, onBack: () -> Unit) {
    val state by viewModel.catalog.collectAsState()

    LaunchedEffect(Unit) {
        if (state.visible.isEmpty() && !state.loading) viewModel.loadCatalog()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Доступные активы") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InstrumentCategory.entries.forEach { category ->
                    FilterChip(
                        selected = state.category == category,
                        onClick = { viewModel.loadCatalog(category) },
                        label = { Text(category.title) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.rublesOnly,
                    onClick = { viewModel.toggleRublesOnly() },
                    label = { Text("Только в рублях") },
                )
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.setCatalogQuery(it) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                label = { Text("Фильтр по тикеру или названию") },
            )

            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(
                    "Загружаю каталог «${state.category.title}» с сервера брокера...",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            state.error?.let { error ->
                Text(
                    "Не удалось загрузить каталог: $error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (!state.loading && state.totalInCategory > 0) {
                Text(
                    buildString {
                        append("Показано ${state.visible.size} из ${state.matchedCount}")
                        append(" · всего доступно к торгам ${state.totalInCategory}")
                        if (state.matchedCount > state.visible.size) append(" · уточните фильтр")
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
