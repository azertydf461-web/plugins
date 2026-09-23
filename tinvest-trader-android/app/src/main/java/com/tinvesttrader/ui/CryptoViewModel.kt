package com.tinvesttrader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tinvesttrader.data.SecureTokenStore
import com.tinvesttrader.trading.CryptoTradingEngine
import com.tinvesttrader.trading.CryptoWorker
import com.tinvesttrader.trading.DecisionJournal
import com.tinvesttrader.trading.DecisionRecord
import com.tinvesttrader.trading.StoreCryptoBotSettings
import com.tinvesttrader.trading.cryptoExchangeFrom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CryptoUiState(
    val hasKeys: Boolean = false,
    val live: Boolean = false,
    val symbol: String = "BTCUSDT",
    val quoteAmount: Double = 50.0,
    val interval: String = SecureTokenStore.INTERVAL_DAY,
    val botEnabled: Boolean = false,
    val inBotPosition: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

class CryptoViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SecureTokenStore(application)
    private val journal = DecisionJournal.get(application)

    private val _state = MutableStateFlow(CryptoUiState())
    val state: StateFlow<CryptoUiState> = _state.asStateFlow()

    val decisions: StateFlow<List<DecisionRecord>> = journal.records
        .map { all -> all.filter { it.figi.startsWith("BYBIT:") } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        refresh()
    }

    private fun refresh(message: String? = _state.value.message) {
        _state.value = _state.value.copy(
            hasKeys = !store.cryptoApiKey.isNullOrBlank() && !store.cryptoApiSecret.isNullOrBlank(),
            live = store.cryptoLiveEnabled,
            symbol = store.cryptoSymbol,
            quoteAmount = store.cryptoOrderQuoteAmount,
            interval = store.cryptoInterval,
            inBotPosition = store.cryptoPositionBaseline != null,
            message = message,
        )
    }

    fun saveKeys(key: String, secret: String) {
        if (key.isBlank() || secret.isBlank()) {
            refresh("Нужны и ключ, и секрет.")
            return
        }
        store.cryptoApiKey = key.trim()
        store.cryptoApiSecret = secret.trim()
        refresh("Ключи сохранены в зашифрованном хранилище.")
    }

    fun clearKeys() {
        store.cryptoApiKey = null
        store.cryptoApiSecret = null
        toggleBot(false)
        refresh("Ключи удалены.")
    }

    fun setLive(enabled: Boolean) {
        store.cryptoLiveEnabled = enabled
        // Позиция в другой сети — чужая: состояние бота начинается заново.
        store.cryptoPositionBaseline = null
        store.cryptoLastCandleTime = null
        refresh(if (enabled) "Боевой режим: заявки идут на настоящие деньги." else "Тестовая сеть биржи.")
    }

    fun setSymbol(symbol: String) {
        if (symbol.isBlank()) return
        store.cryptoSymbol = symbol
        store.cryptoPositionBaseline = null
        store.cryptoLastCandleTime = null
        refresh()
    }

    fun setQuoteAmount(amount: Double) {
        if (amount > 0) store.cryptoOrderQuoteAmount = amount
        refresh()
    }

    fun setInterval(interval: String) {
        store.cryptoInterval = interval
        store.cryptoLastCandleTime = null
        refresh()
    }

    fun toggleBot(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled && !_state.value.hasKeys) {
            refresh("Сначала сохраните ключи биржи.")
            return
        }
        if (enabled) CryptoWorker.enable(app) else CryptoWorker.disable(app)
        _state.value = _state.value.copy(botEnabled = enabled)
    }

    /** Проверка ключей: читает баланс, ничего не покупает. */
    fun checkConnection() = launchBusy {
        val exchange = cryptoExchangeFrom(store) ?: return@launchBusy "Ключи не заданы."
        val instrument = exchange.instrument(store.cryptoSymbol)
        val balances = exchange.availableBalances(listOf(instrument.baseCoin, instrument.quoteCoin))
        "Подключение работает (${exchange.modeLabel}). " + balances.entries.joinToString { "${it.key}: ${it.value.toPlainString()}" }
    }

    /** Полный цикл решения прямо сейчас — может выставить заявку, как и фоновый запуск. */
    fun runNow() = launchBusy {
        val exchange = cryptoExchangeFrom(store) ?: return@launchBusy "Ключи не заданы."
        CryptoTradingEngine(exchange, StoreCryptoBotSettings(store), journal::append).tick().headline
    }

    private fun launchBusy(block: suspend () -> String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = "Связываюсь с биржей...")
            val message = runCatching { block() }.getOrElse { "Ошибка: ${it.message}" }
            _state.value = _state.value.copy(busy = false)
            refresh(message)
        }
    }
}
