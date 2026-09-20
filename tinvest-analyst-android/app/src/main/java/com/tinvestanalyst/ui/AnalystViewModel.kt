package com.tinvestanalyst.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tinvestanalyst.analysis.Horizon
import com.tinvestanalyst.analysis.MarketAnalysis
import com.tinvestanalyst.analysis.MarketAnalyzer
import com.tinvestanalyst.analysis.RiskProfile
import com.tinvestanalyst.data.AnalystRepository
import com.tinvestanalyst.data.AnalystSettingsStore
import com.tinvestanalyst.data.AssetFundamental
import com.tinvestanalyst.data.CheckStatus
import com.tinvestanalyst.data.DiagnosticStep
import com.tinvestanalyst.data.Instrument
import com.tinvestanalyst.data.NetworkDiagnostics
import com.tinvestanalyst.data.NewsItem
import com.tinvestanalyst.data.NewsRepository
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
    val progress: Pair<Int, Int>? = null,
    val capital: Double = 0.0,
    val riskPerTradePercent: Double = 1.0,
    val maxLeverage: Double = 1.0,
    val horizon: Horizon = Horizon.SWING,
    val newsEnabled: Boolean = true,
    val newsNote: String? = null,
) {
    /** Идеи — те же бумаги, отсортированные по силе сигнала. */
    val rankedIdeas: List<WatchRow>
        get() = rows.filter { it.analysis != null }
            .sortedByDescending { it.analysis?.weightedScore ?: 0.0 }
}

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

private val NEWS_SOURCE_COUNT = com.tinvestanalyst.data.NEWS_SOURCES.size

class AnalystViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = AnalystSettingsStore(application)
    private val repository = AnalystRepository(settings)
    private val newsRepository = NewsRepository()

    private val _uiState = MutableStateFlow(AnalystUiState())
    val uiState: StateFlow<AnalystUiState> = _uiState.asStateFlow()

    private val _detail = MutableStateFlow<DetailUiState?>(null)
    val detail: StateFlow<DetailUiState?> = _detail.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _catalog = MutableStateFlow(CatalogUiState())
    val catalog: StateFlow<CatalogUiState> = _catalog.asStateFlow()

    private val diagnostics = NetworkDiagnostics(settings)

    private val _diagnosticSteps = MutableStateFlow<List<DiagnosticStep>>(emptyList())
    val diagnosticSteps: StateFlow<List<DiagnosticStep>> = _diagnosticSteps.asStateFlow()

    private val _diagnosticsRunning = MutableStateFlow(false)
    val diagnosticsRunning: StateFlow<Boolean> = _diagnosticsRunning.asStateFlow()

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
            capital = settings.capital,
            riskPerTradePercent = settings.riskPerTradePercent,
            maxLeverage = settings.maxLeverage,
            horizon = Horizon.fromKey(settings.horizon),
            newsEnabled = settings.newsEnabled,
            rows = settings.watchlist.map { watched ->
                _uiState.value.rows.firstOrNull { it.instrument.figi == watched.figi }
                    ?: WatchRow(watched)
            },
        )
    }

    /** Полный пересчёт: свечи, отчётность, дивиденды и анализ по всему списку. */
    fun refreshAll() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            val rows = _uiState.value.rows
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null, progress = 0 to rows.size)
            val interval = settings.candleInterval
            val profile = riskProfile()
            // Отчётность запрашивается одним запросом на весь список: API
            // принимает массив активов, и это дешевле, чем запрос на бумагу.
            val fundamentals = runCatching {
                repository.loadFundamentals(rows.map { it.instrument.assetUid })
            }.getOrDefault(emptyMap())
            val news = loadNews()

            val updated = mutableListOf<WatchRow>()
            var failure: String? = null

            rows.forEachIndexed { index, row ->
                runCatching { analyzeRow(row, interval, profile, fundamentals[row.instrument.assetUid], news) }
                    .onSuccess { analysis ->
                        updated += row.copy(analysis = analysis, lastPrice = analysis.lastPrice)
                    }
                    .onFailure { error ->
                        failure = failure ?: error.message
                        updated += row
                    }
                _uiState.value = _uiState.value.copy(progress = (index + 1) to rows.size)
            }

            _uiState.value = _uiState.value.copy(
                rows = updated,
                isRefreshing = false,
                lastUpdateMillis = System.currentTimeMillis(),
                error = failure,
                progress = null,
            )
        }
    }

    private suspend fun analyzeRow(
        row: WatchRow,
        interval: String,
        profile: RiskProfile,
        fundamental: AssetFundamental?,
        news: List<NewsItem> = emptyList(),
    ): MarketAnalysis {
        val instrument = row.instrument
        val candles = repository.loadCandles(instrument.figi, interval)
        val dividends = repository.loadDividends(instrument.figi)
        val reports = repository.loadReports(instrument.uid.ifBlank { instrument.figi })
        return MarketAnalyzer.analyze(instrument, candles, fundamental, dividends, reports, profile, news)
    }

    private fun riskProfile(): RiskProfile = RiskProfile(
        capital = settings.capital,
        riskPerTradePercent = settings.riskPerTradePercent,
        maxLeverage = settings.maxLeverage,
        horizon = Horizon.fromKey(settings.horizon),
    )

    /**
     * Новости качаются один раз на весь прогон и переиспользуются для всех
     * бумаг: ленты общие, а тянуть их на каждую бумагу — лишний трафик.
     * Недоступная лента не должна ломать анализ, поэтому ошибка становится
     * пометкой в интерфейсе, а не исключением.
     */
    private suspend fun loadNews(forceRefresh: Boolean = false): List<NewsItem> {
        if (!settings.newsEnabled) {
            _uiState.value = _uiState.value.copy(newsNote = null)
            return emptyList()
        }
        val items = runCatching { newsRepository.load(forceRefresh) }.getOrDefault(emptyList())
        val failures = newsRepository.failures
        _uiState.value = _uiState.value.copy(
            newsNote = when {
                items.isEmpty() -> "Новостные ленты недоступны" +
                    (failures.firstOrNull()?.let { " ($it)" } ?: "") +
                    ". Анализ считается без новостного блока."
                failures.isEmpty() -> "Загружено ${items.size} новостей из ${NEWS_SOURCE_COUNT} лент."
                else -> "Загружено ${items.size} новостей, недоступны: ${failures.joinToString("; ")}."
            },
        )
        return items
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
            val fundamental = repository.loadFundamentals(listOf(instrument.assetUid))[instrument.assetUid]
            val news = loadNews()
            val analysis = analyzeRow(WatchRow(instrument), settings.candleInterval, riskProfile(), fundamental, news)
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
        settings.addToWatchlist(instrument.toWatched())
        reloadSettings()
        syncCatalogSelection()
    }

    /** Ставка риска и размер лота нужны расчёту позиции, поэтому сохраняются сразу. */
    private fun Instrument.toWatched() = WatchedInstrument(
        figi = figi,
        ticker = ticker.ifBlank { figi },
        name = name.ifBlank { ticker },
        uid = uid,
        assetUid = assetUid,
        lot = lot.coerceAtLeast(1),
        currency = currency.ifBlank { "rub" },
        riskRateLong = dlong.toDouble(),
        shortEnabled = shortEnabledFlag,
        instrumentType = instrumentType,
    )

    fun setCapital(value: Double) {
        settings.capital = value
        reloadSettings()
    }

    fun setRiskPerTrade(value: Double) {
        settings.riskPerTradePercent = value
        reloadSettings()
    }

    fun setMaxLeverage(value: Double) {
        settings.maxLeverage = value
        reloadSettings()
    }

    fun setNewsEnabled(enabled: Boolean) {
        settings.newsEnabled = enabled
        reloadSettings()
        refreshAll()
    }

    fun setHorizon(horizon: Horizon) {
        settings.horizon = horizon.name
        reloadSettings()
        refreshAll()
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
            val profile = riskProfile()
            val pending = _uiState.value.rows.filter { it.analysis == null }
            val fundamentals = runCatching {
                repository.loadFundamentals(pending.map { it.instrument.assetUid })
            }.getOrDefault(emptyMap())
            val news = loadNews()

            val updated = _uiState.value.rows.map { row ->
                if (row.analysis != null) {
                    row
                } else {
                    runCatching {
                        analyzeRow(row, interval, profile, fundamentals[row.instrument.assetUid], news)
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

    fun runDiagnostics() {
        if (_diagnosticsRunning.value) return
        viewModelScope.launch {
            _diagnosticsRunning.value = true
            _diagnosticSteps.value = emptyList()
            _diagnosticSteps.value = runCatching { diagnostics.run() }.getOrElse { error ->
                listOf(
                    DiagnosticStep(
                        title = "Проверка",
                        status = CheckStatus.FAIL,
                        detail = "Диагностика не завершилась: ${error.message}",
                    ),
                )
            }
            _diagnosticsRunning.value = false
            reloadSettings()
        }
    }

    /** Добавляет в наблюдение всю отфильтрованную группу — например, все рублёвые акции. */
    fun addVisibleGroup() {
        _catalog.value.visible
            .filterNot { _catalog.value.addedFigis.contains(it.figi) }
            .forEach { settings.addToWatchlist(it.toWatched()) }
        reloadSettings()
        syncCatalogSelection()
    }

    fun clearWatchlist() {
        settings.watchlist = emptyList()
        reloadSettings()
        syncCatalogSelection()
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
