package com.tinvesttrader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tinvesttrader.data.SecureTokenStore
import com.tinvesttrader.data.TInvestRepository
import com.tinvesttrader.trading.EngineEvent
import com.tinvesttrader.trading.EngineEventLog
import com.tinvesttrader.trading.RiskManagerHolder
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
    val events: List<EngineEvent> = emptyList(),
    val statusMessage: String? = null,
)

class TradingViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = SecureTokenStore(application)
    private val repository = TInvestRepository(tokenStore)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        refreshFromStore()
    }

    fun refreshFromStore() {
        _uiState.value = _uiState.value.copy(
            liveMode = tokenStore.liveTradingEnabled,
            accountId = tokenStore.accountId,
            instrumentFigi = tokenStore.instrumentFigi,
            killSwitchActive = RiskManagerHolder.getOrCreate().isKillSwitchActive,
            events = EngineEventLog.events,
        )
    }

    fun toggleBot(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled) {
            TradingWorker.enable(app)
        } else {
            TradingWorker.disable(app)
        }
        _uiState.value = _uiState.value.copy(botEnabled = enabled)
    }

    fun resetKillSwitch() {
        RiskManagerHolder.getOrCreate().resetKillSwitch()
        refreshFromStore()
    }

    fun runTickNow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(statusMessage = "Выполняется тик...")
            try {
                repository.getAccounts() // проверка токена/сети перед полноценным тиком
                _uiState.value = _uiState.value.copy(statusMessage = "Подключение к API работает")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(statusMessage = "Ошибка: ${e.message}")
            }
            refreshFromStore()
        }
    }
}
