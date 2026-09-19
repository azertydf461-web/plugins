package com.tinvestanalyst.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tinvestanalyst.analysis.MarketAnalysis
import com.tinvestanalyst.analysis.MarketAnalyzer
import com.tinvestanalyst.data.AnalystRepository
import com.tinvestanalyst.data.AnalystSettingsStore
import com.tinvestanalyst.data.Instrument
import com.tinvestanalyst.data.InstrumentCategory
import com.tinvestanalyst.data.WatchedInstrument
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WatchRow(
    val instrument: WatchedInstrument,
    val lastPrice: Double? = null,
    val analysis: MarketAnalysis? = null,
)

data class AnalystUiState(
    val hasToken: Boolean = false,
    val rows: List<WatchRow> = emptyList(),
    val isRefreshing: Boolean = false,
    val lastUpdateMillis: Long? = null,
    val error: String? = null,
    val interval: String = "CANDLE_INTERVAL_15_MIN",
)

data class DetailUiState(
    val figi: String,
    val analysis: MarketAnalysis? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val orderBookNote: String? = null,
    val tradingStatus: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val results: List<Instrument> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class CatalogUiState(
    val category: InstrumentCategory = InstrumentCategory.SHARES,
    val visible: List<Instrument> = emptyList(),
    val totalInCategory: Int = 0,
    val matchedCount: Int = 0,
    val query: String = "",
    val rublesOnly: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val addedFigis: Set<String> = emptySet(),
)

class AnalystViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = AnalystSettingsStore(application)
    private val repository = AnalystRepository(settings)

    private val _uiState = MutableStateFlow(AnalystUiState())
    val uiState: StateFlow<AnalystUiState> = _uiState.asStateFlow()

    private val _detail = MutableStateFlow<DetailUiState?>(null)
    val detail: StateFlow<DetailUiState?> = _detail.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _catalog = MutableStateFlow(CatalogUiState())
    val catalog: StateFlow<CatalogUiState> = _catalog.asStateFlow()

    /** Полный загруженный список текущей категории — фильтры применяются к нему локально. */
    private var catalogSource: List<Instrument> = emptyList()

    private var priceTicker: Job? = null
    private var detailTicker: Job? = null

    init {
        reloadSettings()
        if (_uiState.value.hasToken && _uiState.value.rows.isNotEmpty()) refreshAll()
    }

    fun reloadSettings() {
        _uiState.value = _uiState.value.copy(
            hasToken = !settings.apiToken.isNullOrBlank(),
            interval = settings.candleInterval,
            rows = settings.watchlist.map { watched ->
                _uiState.value.rows.firstOrNull { it.instrument.figi == watched.figi }
                    ?: WatchRow(watched)
            },
        )
    }

    /** Полный пересчёт: свечи и анализ по каждому инструменту списка. */
    fun refreshAll() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            val interval = settings.candleInterval
            val updated = mutableListOf<WatchRow>()
            var failure: String? = null

            for (row in _uiState.value.rows) {
                val result = runCatching {
                    val candles = repository.loadCandles(row.instrument.figi, interval)
                    MarketAnalyzer.analyze(row.instrument, candles)
                }
                result.onSuccess { analysis ->
                    updated += row.copy(analysis = analysis, lastPrice = analysis.lastPrice)
                }.onFailure { error ->
                    failure = failure ?: error.message
                    updated += row
                }
            }

            _uiState.value = _uiState.value.copy(
                rows = updated,
                isRefreshing = false,
                lastUpdateMillis = System.currentTimeMillis(),
                error = failure,
            )
        }
    }

    /**
     * Лёгкое обновление цен одним запросом на весь список — гоняется часто,
     * в отличие от полного анализа, чтобы не упереться в лимиты API.
     */
    fun startPricePolling(intervalSeconds: Long = 15) {
        priceTicker?.cancel()
        priceTicker = viewModelScope.launch {
            while (isActive) {
                val figis = _uiState.value.rows.map { it.instrument.figi }
                if (figis.isNotEmpty() && _uiState.value.hasToken) {
                    runCatching { repository.loadLastPrices(figis) }
                        .onSuccess { prices ->
                            _uiState.value = _uiState.value.copy(
                                rows = _uiState.value.rows.map { row ->
                                    prices[row.instrument.figi]?.let { row.copy(lastPrice = it) } ?: row
                                },
                                lastUpdateMillis = System.currentTimeMillis(),
                            )
                        }
                }
                delay(intervalSeconds * 1000)
            }
        }
    }

    fun stopPricePolling() {
        priceTicker?.cancel()
        priceTicker = null
    }

    fun openInstrument(figi: String) {
        _detail.value = DetailUiState(figi = figi, loading = true)
        detailTicker?.cancel()
        detailTicker = viewModelScope.launch {
            while (isActive) {
                loadDetail(figi)
                delay(15_000)
            }
        }
    }

    fun closeInstrument() {
        detailTicker?.cancel()
        detailTicker = null
        _detail.value = null
    }

    private suspend fun loadDetail(figi: String) {
        val instrument = settings.watchlist.firstOrNull { it.figi == figi } ?: return
        runCatching {
            val candles = repository.loadCandles(figi, settings.candleInterval)
            val analysis = MarketAnalyzer.analyze(instrument, candles)
            val status = runCatching { repository.loadTradingStatus(figi) }.getOrNull()
            val book = runCatching { repository.loadOrderBook(figi) }.getOrNull()
            Triple(analysis, status, book)
        }.onSuccess { (analysis, status, book) ->
            val bidVolume = book?.bids?.sumOf { it.quantity.toDoubleOrNull() ?: 0.0 } ?: 0.0
            val askVolume = book?.asks?.sumOf { it.quantity.toDoubleOrNull() ?: 0.0 } ?: 0.0
            val bookNote = if (book == null || bidVolume + askVolume == 0.0) {
                null
            } else {
                val bidShare = (bidVolume / (bidVolume + askVolume) * 100).toInt()
                "В стакане ${bidShare}% заявок на покупку против ${100 - bidShare}% на продажу " +
                    if (bidShare >= 60) "— спрос преобладает." else if (bidShare <= 40) "— давят продавцы." else "— силы примерно равны."
            }
            _detail.value = DetailUiState(
                figi = figi,
                analysis = analysis,
                loading = false,
                orderBookNote = bookNote,
                tradingStatus = status?.tradingStatus?.let(::humanTradingStatus),
            )
            // Держим список согласованным с только что пересчитанной карточкой.
            _uiState.value = _uiState.value.copy(
                rows = _uiState.value.rows.map { row ->
                    if (row.instrument.figi == figi) {
                        row.copy(analysis = analysis, lastPrice = analysis.lastPrice)
                    } else {
                        row
                    }
                },
            )
        }.onFailure { error ->
            _detail.value = DetailUiState(figi = figi, loading = false, error = error.message)
        }
    }

    fun saveToken(token: String) {
        settings.apiToken = token.trim()
        reloadSettings()
    }

    fun setInterval(interval: String) {
        settings.candleInterval = interval
        reloadSettings()
        refreshAll()
    }

    fun searchInstruments(query: String) {
        _search.value = _search.value.copy(query = query)
        if (query.length < 2) {
            _search.value = _search.value.copy(results = emptyList(), error = null)
            return
        }
        viewModelScope.launch {
            _search.value = _search.value.copy(loading = true, error = null)
            runCatching { repository.searchInstruments(query) }
                .onSuccess { _search.value = _search.value.copy(results = it, loading = false) }
                .onFailure { _search.value = _search.value.copy(loading = false, error = it.message) }
        }
    }

    fun addInstrument(instrument: Instrument) {
        settings.addToWatchlist(
            WatchedInstrument(
                figi = instrument.figi,
                ticker = instrument.ticker.ifBlank { instrument.figi },
                name = instrument.name.ifBlank { instrument.ticker },
            ),
        )
        reloadSettings()
        syncCatalogSelection()
    }

    fun removeInstrument(figi: String) {
        settings.removeFromWatchlist(figi)
        reloadSettings()
        syncCatalogSelection()
    }

    /** Загружает каталог выбранного типа активов, доступных к торгам через API. */
    fun loadCatalog(category: InstrumentCategory = _catalog.value.category, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _catalog.value = _catalog.value.copy(category = category, loading = true, error = null)
            runCatching { repository.loadCatalog(category, forceRefresh) }
                .onSuccess { instruments ->
                    catalogSource = instruments
                    _catalog.value = _catalog.value.copy(
                        loading = false,
                        totalInCategory = instruments.size,
                        addedFigis = settings.watchlist.map { it.figi }.toSet(),
                    )
                    applyCatalogFilter()
                }
                .onFailure { error ->
                    catalogSource = emptyList()
                    _catalog.value = _catalog.value.copy(
                        loading = false,
                        visible = emptyList(),
                        totalInCategory = 0,
                        error = error.message,
                    )
                }
        }
    }

    fun setCatalogQuery(query: String) {
        _catalog.value = _catalog.value.copy(query = query)
        applyCatalogFilter()
    }

    fun toggleRublesOnly() {
        _catalog.value = _catalog.value.copy(rublesOnly = !_catalog.value.rublesOnly)
        applyCatalogFilter()
    }

    fun toggleCatalogInstrument(instrument: Instrument) {
        if (_catalog.value.addedFigis.contains(instrument.figi)) {
            removeInstrument(instrument.figi)
        } else {
            addInstrument(instrument)
        }
    }

    /**
     * Пересчитывает только те бумаги, по которым анализа ещё нет: после
     * добавления десятка позиций из каталога полный пересчёт всего списка был
     * бы десятком лишних запросов.
     */
    fun analyzeMissing() {
        if (_uiState.value.rows.none { it.analysis == null }) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            val interval = settings.candleInterval
            val updated = _uiState.value.rows.map { row ->
                if (row.analysis != null) {
                    row
                } else {
                    runCatching {
                        val candles = repository.loadCandles(row.instrument.figi, interval)
                        MarketAnalyzer.analyze(row.instrument, candles)
                    }.map { row.copy(analysis = it, lastPrice = it.lastPrice) }.getOrDefault(row)
                }
            }
            _uiState.value = _uiState.value.copy(
                rows = updated,
                isRefreshing = false,
                lastUpdateMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun syncCatalogSelection() {
        _catalog.value = _catalog.value.copy(addedFigis = settings.watchlist.map { it.figi }.toSet())
    }

    private fun applyCatalogFilter() {
        val state = _catalog.value
        val query = state.query.trim()
        val matched = catalogSource.filter { instrument ->
            (!state.rublesOnly || instrument.currency.equals("rub", ignoreCase = true)) &&
                (
                    query.isEmpty() ||
                        instrument.ticker.contains(query, ignoreCase = true) ||
                        instrument.name.contains(query, ignoreCase = true)
                    )
        }
        // Списком в несколько тысяч строк пользоваться невозможно, поэтому
        // показываем первые 400 и подсказываем сузить поиск.
        _catalog.value = state.copy(visible = matched.take(400), matchedCount = matched.size)
    }

    private fun humanTradingStatus(raw: String): String = when (raw) {
        "SECURITY_TRADING_STATUS_NORMAL_TRADING" -> "Идут торги"
        "SECURITY_TRADING_STATUS_NOT_AVAILABLE_FOR_TRADING" -> "Торги закрыты"
        "SECURITY_TRADING_STATUS_OPENING_AUCTION",
        "SECURITY_TRADING_STATUS_OPENING_PERIOD",
        -> "Аукцион открытия"
        "SECURITY_TRADING_STATUS_CLOSING_AUCTION",
        "SECURITY_TRADING_STATUS_CLOSING_PERIOD",
        -> "Аукцион закрытия"
        "SECURITY_TRADING_STATUS_BREAK_IN_TRADING" -> "Перерыв в торгах"
        "SECURITY_TRADING_STATUS_DEALER_NORMAL_TRADING" -> "Идут торги (внебиржевой)"
        else -> raw.removePrefix("SECURITY_TRADING_STATUS_")
    }

    override fun onCleared() {
        priceTicker?.cancel()
        detailTicker?.cancel()
        super.onCleared()
    }
}
