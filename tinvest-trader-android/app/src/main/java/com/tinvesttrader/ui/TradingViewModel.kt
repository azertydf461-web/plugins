package com.tinvesttrader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tinvesttrader.data.SecureTokenStore
import com.tinvesttrader.data.TInvestRepository
import com.tinvesttrader.trading.DecisionJournal
import com.tinvesttrader.trading.DecisionRecord
import com.tinvesttrader.trading.BotBacktestResult
import com.tinvesttrader.trading.BotBacktestSettings
import com.tinvesttrader.trading.LedgerStats
import com.tinvesttrader.trading.RiskManagerHolder
import com.tinvesttrader.trading.StrategyBacktest
import com.tinvesttrader.trading.DonchianBreakoutStrategy
import com.tinvesttrader.trading.channelFor
import com.tinvesttrader.trading.TradeLedger
import com.tinvesttrader.trading.strategyFor
import com.tinvesttrader.trading.TradingEngine
import com.tinvesttrader.trading.TradingWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardUiState(
    val botEnabled: Boolean = false,
    val liveMode: Boolean = false,
    val killSwitchActive: Boolean = false,
    val accountId: String? = null,
    val accountLabel: String? = null,
    val instrumentFigi: String? = null,
    val instrumentLabel: String? = null,
    val statusMessage: String? = null,
    val checkInProgress: Boolean = false,
)

/**
 * Периоды прогона. Первый повторяет то, как бот работает сейчас: свечи по
 * пять минут, а проверка рынка раз в пятнадцать — то есть каждая третья
 * свеча. Остальные показывают, что было бы на других таймфреймах.
 */
enum class BotBacktestRange(
    val title: String,
    val interval: String,
    val days: Long,
    val pollEveryNBars: Int,
) {
    DAY("Дневные, 5 лет", "CANDLE_INTERVAL_DAY", 1825, 1),
    HOUR("Часовые, 1 год", "CANDLE_INTERVAL_HOUR", 365, 1),
}

data class BotBacktestUiState(
    val range: BotBacktestRange = BotBacktestRange.DAY,
    val running: Boolean = false,
    val stage: String? = null,
    val result: BotBacktestResult? = null,
    val error: String? = null,
)

class TradingViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = SecureTokenStore(application)
    private val repository = TInvestRepository(tokenStore)
    private val journal = DecisionJournal.get(application)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    val decisions: StateFlow<List<DecisionRecord>> = journal.records

    private val _backtest = MutableStateFlow(BotBacktestUiState())
    val backtest: StateFlow<BotBacktestUiState> = _backtest.asStateFlow()

    /** Итог реальной работы бота: собирается из журнала, отдельного хранилища не нужно. */
    fun ledgerStats(): LedgerStats = TradeLedger.stats(journal.records.value)

    fun closedTrades() = TradeLedger.closedTrades(journal.records.value)

    fun setBacktestRange(range: BotBacktestRange) {
        _backtest.value = _backtest.value.copy(range = range, result = null, error = null)
    }

    /**
     * Прогоняет по истории ту же стратегию и то же правило стоп-лосса,
     * которыми бот торгует вживую. Отдельной «модельной» копии логики нет
     * намеренно: иначе проверялась бы не та программа, что выставляет ордера.
     */
    fun runBacktest() {
        if (_backtest.value.running) return
        val figi = tokenStore.instrumentFigi
        if (figi.isNullOrBlank()) {
            _backtest.value = _backtest.value.copy(
                error = "Сначала выберите инструмент в настройках — прогонять нечего.",
            )
            return
        }
        viewModelScope.launch {
            val range = _backtest.value.range
            _backtest.value = _backtest.value.copy(
                running = true,
                error = null,
                result = null,
                stage = "Загружаю историю...",
            )
            runCatching {
                val candles = repository.getHistory(figi, range.interval, range.days)
                _backtest.value = _backtest.value.copy(stage = "Прогоняю ${candles.size} свечей...")
                withContext(Dispatchers.Default) {
                    StrategyBacktest.run(
                        intervalTitle = range.title,
                        candles = candles,
                        // Та же логика, что у живого бота: чистый пробой, без стопов.
                        settings = BotBacktestSettings(
                            pollEveryNBars = range.pollEveryNBars,
                            strategy = channelFor(range.interval).let {
                                DonchianBreakoutStrategy(it.entry, it.exit)
                            },
                            stops = false,
                            trailingStop = false,
                        ),
                    )
                } to candles.size
            }.onSuccess { (result, bars) ->
                _backtest.value = _backtest.value.copy(
                    running = false,
                    stage = null,
                    result = result,
                    error = if (result == null) {
                        "Истории не хватило: получено $bars свечей. Биржа могла не отдать данные " +
                            "за выбранный период — попробуйте другой."
                    } else {
                        null
                    },
                )
            }.onFailure { error ->
                _backtest.value = _backtest.value.copy(
                    running = false,
                    stage = null,
                    error = error.message ?: "Не удалось загрузить историю.",
                )
            }
        }
    }

    init {
        refreshFromStore()
    }

    fun refreshFromStore() {
        _uiState.value = _uiState.value.copy(
            liveMode = tokenStore.liveTradingEnabled,
            accountId = tokenStore.accountId,
            accountLabel = tokenStore.accountLabel,
            instrumentFigi = tokenStore.instrumentFigi,
            instrumentLabel = tokenStore.instrumentLabel,
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
                strategy = strategyFor(tokenStore),
                riskManager = RiskManagerHolder.configure(RiskManagerHolder.limitsFrom(tokenStore)),
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
