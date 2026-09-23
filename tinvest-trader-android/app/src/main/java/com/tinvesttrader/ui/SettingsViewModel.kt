package com.tinvesttrader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tinvesttrader.data.Account
import com.tinvesttrader.data.CheckStatus
import com.tinvesttrader.data.DiagnosticStep
import com.tinvesttrader.data.Instrument
import com.tinvesttrader.data.InstrumentCategory
import com.tinvesttrader.data.NetworkDiagnostics
import com.tinvesttrader.data.SecureTokenStore
import com.tinvesttrader.data.TInvestRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val SANDBOX_START_CAPITAL_RUB = 1_000_000L

data class SettingsUiState(
    val liveMode: Boolean = false,
    val sandboxTokenSet: Boolean = false,
    val liveTokenSet: Boolean = false,
    val accountId: String? = null,
    val accountLabel: String? = null,
    val instrumentFigi: String? = null,
    val instrumentLabel: String? = null,
    val accounts: List<Account> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val searchQuery: String = "",
    val category: InstrumentCategory = InstrumentCategory.SHARES,
    val catalog: List<Instrument> = emptyList(),
    val catalogMatched: Int = 0,
    val catalogTotal: Int = 0,
    val catalogBusy: Boolean = false,
    val diagnostics: List<DiagnosticStep> = emptyList(),
    val diagnosticsRunning: Boolean = false,
    val candleInterval: String = "CANDLE_INTERVAL_DAY",
    val stopMode: String = "ATR",
    val stopLossPercent: Double = 3.0,
    val atrMultiplier: Double = 2.0,
    val protectiveStopEnabled: Boolean = true,
    val strategyMode: String = "TREND",
    val trailingStopEnabled: Boolean = true,
    val baseLots: Long = 3,
)

/**
 * Настройки перестали быть четырьмя текстовыми полями: счёт и бумага
 * выбираются из того, что реально вернул брокер. В песочнице счёт вдобавок
 * приходится создавать — до этого вызова вводить в поле «ID счёта» просто
 * нечего.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = SecureTokenStore(application)
    private val repository = TInvestRepository(tokenStore)
    private val diagnostics = NetworkDiagnostics(tokenStore)

    /** Полный каталог текущего вида активов — фильтр применяется локально. */
    private var catalogSource: List<Instrument> = emptyList()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _uiState.value = _uiState.value.copy(
            liveMode = tokenStore.liveTradingEnabled,
            sandboxTokenSet = !tokenStore.sandboxToken.isNullOrBlank(),
            liveTokenSet = !tokenStore.liveToken.isNullOrBlank(),
            accountId = tokenStore.accountId,
            accountLabel = tokenStore.accountLabel,
            instrumentFigi = tokenStore.instrumentFigi,
            instrumentLabel = tokenStore.instrumentLabel,
            candleInterval = tokenStore.candleInterval,
            stopMode = tokenStore.stopMode,
            stopLossPercent = tokenStore.stopLossPercent,
            atrMultiplier = tokenStore.atrMultiplier,
            protectiveStopEnabled = tokenStore.protectiveStopEnabled,
            strategyMode = tokenStore.strategyMode,
            trailingStopEnabled = tokenStore.trailingStopEnabled,
            baseLots = tokenStore.baseLots,
        )
    }

    /**
     * Таймфрейм свечей должен быть не мельче периода пробуждения бота,
     * иначе часть свечей он просто не увидит. Смена таймфрейма сбрасывает
     * отметку последней разобранной свечи: старая относится к другой сетке.
     */
    fun setCandleInterval(interval: String) {
        tokenStore.candleInterval = interval
        tokenStore.lastProcessedCandleTime = null
        reload()
    }

    fun setStopMode(mode: String) {
        tokenStore.stopMode = mode
        reload()
    }

    fun setStopLossPercent(value: Double) {
        tokenStore.stopLossPercent = value
        reload()
    }

    fun setAtrMultiplier(value: Double) {
        tokenStore.atrMultiplier = value
        reload()
    }

    fun setStrategyMode(mode: String) {
        tokenStore.strategyMode = mode
        reload()
    }

    fun setBaseLots(value: Double) {
        tokenStore.baseLots = value.toLong().coerceAtLeast(1)
        reload()
    }

    fun setTrailingStopEnabled(enabled: Boolean) {
        tokenStore.trailingStopEnabled = enabled
        reload()
    }

    fun setProtectiveStopEnabled(enabled: Boolean) {
        tokenStore.protectiveStopEnabled = enabled
        reload()
    }

    fun saveSandboxToken(token: String) {
        tokenStore.sandboxToken = token.trim()
        reload()
        report("Sandbox-токен сохранён. Теперь загрузите или создайте счёт.", isError = false)
    }

    fun saveLiveToken(token: String) {
        tokenStore.liveToken = token.trim()
        reload()
        report("Live-токен сохранён.", isError = false)
    }

    fun setLiveMode(enabled: Boolean) {
        tokenStore.liveTradingEnabled = enabled
        // Счёт из песочницы не существует на боевом контуре и наоборот,
        // поэтому при смене режима выбор сбрасывается, а не тащится дальше.
        tokenStore.accountId = null
        tokenStore.accountLabel = null
        reload()
        _uiState.value = _uiState.value.copy(accounts = emptyList())
        report(
            if (enabled) "LIVE-режим включён. Выберите счёт заново." else "Вернулись в песочницу. Выберите счёт заново.",
            isError = false,
        )
    }

    fun loadAccounts() {
        launchBusy("Запрашиваю счета...") {
            val accounts = repository.getAccounts()
            _uiState.value = _uiState.value.copy(accounts = accounts)
            if (accounts.isEmpty() && !_uiState.value.liveMode) {
                report("Счетов в песочнице нет — создайте счёт кнопкой ниже.", isError = false)
            } else if (accounts.isEmpty()) {
                report("Брокер не вернул ни одного счёта для этого токена.", isError = true)
            } else {
                report("Найдено счетов: ${accounts.size}. Выберите нужный.", isError = false)
            }
        }
    }

    fun createSandboxAccount() {
        launchBusy("Создаю счёт в песочнице...") {
            val accountId = repository.openSandboxAccount()
            val balance = runCatching { repository.payInSandbox(accountId, SANDBOX_START_CAPITAL_RUB) }
                .getOrNull()
            selectAccount(accountId, "Песочница")
            val accounts = runCatching { repository.getAccounts() }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(accounts = accounts)
            report(
                if (balance != null) {
                    "Счёт создан и пополнен на ${balance.toLong()} ₽ виртуальных денег."
                } else {
                    "Счёт создан, но пополнить не удалось — пополните позже."
                },
                isError = false,
            )
        }
    }

    fun selectAccount(accountId: String, label: String) {
        tokenStore.accountId = accountId
        tokenStore.accountLabel = label
        reload()
    }

    fun selectInstrument(instrument: Instrument) {
        tokenStore.instrumentFigi = instrument.figi
        tokenStore.instrumentLabel = "${instrument.ticker} · ${instrument.name}"
        reload()
        report("Инструмент выбран: ${instrument.ticker}.", isError = false)
    }

    /** Полная диагностика связи: интернет -> сервер брокера -> токен. */
    fun runDiagnostics() {
        if (_uiState.value.diagnosticsRunning) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(diagnosticsRunning = true, diagnostics = emptyList())
            val steps = runCatching { diagnostics.run() }.getOrElse {
                listOf(
                    DiagnosticStep(
                        title = "Проверка",
                        status = CheckStatus.FAIL,
                        detail = "Диагностика не завершилась: ${it.message}",
                    ),
                )
            }
            _uiState.value = _uiState.value.copy(diagnostics = steps, diagnosticsRunning = false)
        }
    }

    /** Загружает каталог выбранного вида активов. */
    fun loadCatalog(category: InstrumentCategory = _uiState.value.category) {
        if (_uiState.value.catalogBusy) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(category = category, catalogBusy = true)
            runCatching { repository.loadCatalog(category) }
                .onSuccess { instruments ->
                    catalogSource = instruments
                    _uiState.value = _uiState.value.copy(
                        catalogBusy = false,
                        catalogTotal = instruments.size,
                    )
                    applyCatalogFilter()
                }
                .onFailure {
                    catalogSource = emptyList()
                    _uiState.value = _uiState.value.copy(
                        catalogBusy = false,
                        catalog = emptyList(),
                        catalogTotal = 0,
                    )
                    report("Каталог не загрузился: ${humanError(it)}", isError = true)
                }
        }
    }

    fun filterCatalog(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        applyCatalogFilter()
    }

    private fun applyCatalogFilter() {
        val query = _uiState.value.searchQuery.trim()
        val matched = catalogSource.filter {
            query.isEmpty() ||
                it.ticker.contains(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true)
        }
        _uiState.value = _uiState.value.copy(
            catalog = matched.take(200),
            catalogMatched = matched.size,
        )
    }

    private fun launchBusy(progressMessage: String, block: suspend () -> Unit) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, message = progressMessage, isError = false)
            runCatching { block() }
                .onFailure { report(humanError(it), isError = true) }
            _uiState.value = _uiState.value.copy(busy = false)
        }
    }

    private fun report(message: String, isError: Boolean) {
        _uiState.value = _uiState.value.copy(message = message, isError = isError)
    }

    /** Ответы брокера малопонятны сами по себе — переводим частые случаи. */
    private fun humanError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("401") || raw.contains("40003") ->
                "Токен не принят (401). Проверьте, что вставлен токен нужного контура: " +
                    "для песочницы — sandbox-токен, для LIVE — боевой."
            raw.contains("429") -> "Слишком частые запросы к API (429). Подождите минуту."
            raw.contains("Unable to resolve host") || raw.contains("timeout") ->
                "Нет связи с сервером брокера. Проверьте интернет и отключите VPN, если он блокирует доступ."
            raw.isBlank() -> "Неизвестная ошибка запроса."
            else -> raw
        }
    }
}
