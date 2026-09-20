package com.tinvestanalyst

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tinvestanalyst.ui.AnalystSettingsScreen
import com.tinvestanalyst.ui.AnalystViewModel
import com.tinvestanalyst.ui.IdeasScreen
import com.tinvestanalyst.ui.InstrumentCatalogScreen
import com.tinvestanalyst.ui.InstrumentDetailScreen
import com.tinvestanalyst.ui.WatchlistScreen

private enum class Tab(val title: String, val icon: String) {
    IDEAS("Идеи", "★"),
    OVERVIEW("Обзор", "◆"),
    CATALOG("Каталог", "☰"),
    SETTINGS("Настройки", "⚙"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { AnalystApp() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalystApp() {
    val viewModel: AnalystViewModel = viewModel()
    var tab by remember { mutableStateOf(Tab.IDEAS) }
    var detailOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = detailOpen || tab != Tab.IDEAS) {
        if (detailOpen) {
            viewModel.closeInstrument()
            detailOpen = false
        } else {
            tab = Tab.IDEAS
        }
    }

    if (detailOpen) {
        InstrumentDetailScreen(
            viewModel = viewModel,
            onBack = {
                viewModel.closeInstrument()
                detailOpen = false
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            Tab.IDEAS -> "Идеи рынка"
                            Tab.OVERVIEW -> "Наблюдение"
                            Tab.CATALOG -> "Каталог активов"
                            Tab.SETTINGS -> "Настройки"
                        },
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = {
                            // Возврат из каталога — момент, когда уместно досчитать
                            // анализ по только что добавленным бумагам.
                            if (tab == Tab.CATALOG && entry != Tab.CATALOG) viewModel.analyzeMissing()
                            tab = entry
                        },
                        icon = { Text(entry.icon) },
                        label = { Text(entry.title) },
                    )
                }
            }
        },
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.IDEAS -> IdeasScreen(
                    viewModel = viewModel,
                    onOpenInstrument = { figi ->
                        viewModel.openInstrument(figi)
                        detailOpen = true
                    },
                )

                Tab.OVERVIEW -> WatchlistScreen(
                    viewModel = viewModel,
                    onOpenCatalog = { tab = Tab.CATALOG },
                    onOpenSettings = { tab = Tab.SETTINGS },
                    onOpenInstrument = { figi ->
                        viewModel.openInstrument(figi)
                        detailOpen = true
                    },
                )

                Tab.CATALOG -> InstrumentCatalogScreen(viewModel = viewModel)

                Tab.SETTINGS -> AnalystSettingsScreen(viewModel = viewModel)
            }
        }
    }
}
