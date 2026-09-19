package com.tinvesttrader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tinvesttrader.data.SecureTokenStore
import com.tinvesttrader.data.TInvestRepository
import com.tinvesttrader.trading.DecisionJournal
import com.tinvesttrader.trading.DecisionRecord
import com.tinvesttrader.trading.RiskManagerHolder
import com.tinvesttrader.trading.SmaCrossoverStrategy
import com.tinvesttrader.trading.TradingEngine
import com.tinvesttrader.trading.TradingWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val botEnabled: Boolean = false,
    val liveMode: Boolean = false,
    val killSwitchActive: Boolean = false,
    val accountId: String? = null,
    val instrumentFigi: String? = null,
    val statusMessage: String? = null,
    val checkInProgress: Boolean = false,
)

class TradingViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = SecureTokenStore(application)
    private val repository = TInvestRepository(tokenStore)
    private val journal = DecisionJournal.get(application)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    val decisions: StateFlow<List<DecisionRecord>> = journal.records

    init {
        refreshFromStore()
    }

    fun refreshFromStore() {
        _uiState.value = _uiState.value.copy(
            liveMode = tokenStore.liveTradingEnabled,
            accountId = tokenStore.accountId,
            instrumentFigi = tokenStore.instrumentFigi,
            killSwitchActive = RiskManagerHolder.getOrCreate().isKillSwitchActive,
        )
    }

    fun toggleBot(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled) TradingWorker.enable(app) else TradingWorker.disable(app)
        _uiState.value = _uiState.value.copy(botEnabled = enabled)
    }

    fun resetKillSwitch() {
        RiskManagerHolder.getOrCreate().resetKillSwitch()
        refreshFromStore()
    }

    /**
     * Прогоняет полный цикл принятия решения прямо сейчас, не дожидаясь
     * фонового запуска — так ход рассуждений бота виден сразу.
     */
    fun runDecisionCycleNow() {
        if (_uiState.value.checkInProgress) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(checkInProgress = true, statusMessage = "Анализирую рынок...")
            val engine = TradingEngine(
                repository = repository,
                strategy = SmaCrossoverStrategy(),
                riskManager = RiskManagerHolder.getOrCreate(),
                tokenStore = tokenStore,
                journal = journal,
            )
            val message = runCatching { engine.tick().headline }
                .getOrElse { "Ошибка: ${it.message}" }
            _uiState.value = _uiState.value.copy(checkInProgress = false, statusMessage = message)
            refreshFromStore()
        }
    }

    fun clearJournal() = journal.clear()
}
