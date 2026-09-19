package com.tinvestanalyst

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tinvestanalyst.ui.AnalystSettingsScreen
import com.tinvestanalyst.ui.AnalystViewModel
import com.tinvestanalyst.ui.InstrumentCatalogScreen
import com.tinvestanalyst.ui.InstrumentDetailScreen
import com.tinvestanalyst.ui.WatchlistScreen

private enum class Screen { WATCHLIST, DETAIL, SETTINGS, CATALOG }

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

@Composable
private fun AnalystApp() {
    val viewModel: AnalystViewModel = viewModel()
    var screen by remember { mutableStateOf(Screen.WATCHLIST) }

    BackHandler(enabled = screen != Screen.WATCHLIST) {
        when (screen) {
            Screen.DETAIL -> viewModel.closeInstrument()
            Screen.CATALOG -> viewModel.analyzeMissing()
            else -> Unit
        }
        screen = Screen.WATCHLIST
    }

    when (screen) {
        Screen.WATCHLIST -> WatchlistScreen(
            viewModel = viewModel,
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenCatalog = { screen = Screen.CATALOG },
            onOpenInstrument = { figi ->
                viewModel.openInstrument(figi)
                screen = Screen.DETAIL
            },
        )

        Screen.CATALOG -> InstrumentCatalogScreen(
            viewModel = viewModel,
            onBack = {
                viewModel.analyzeMissing()
                screen = Screen.WATCHLIST
            },
        )

        Screen.DETAIL -> InstrumentDetailScreen(
            viewModel = viewModel,
            onBack = {
                viewModel.closeInstrument()
                screen = Screen.WATCHLIST
            },
        )

        Screen.SETTINGS -> AnalystSettingsScreen(
            viewModel = viewModel,
            onOpenCatalog = { screen = Screen.CATALOG },
            onBack = {
                viewModel.reloadSettings()
                viewModel.analyzeMissing()
                screen = Screen.WATCHLIST
            },
        )
    }
}
