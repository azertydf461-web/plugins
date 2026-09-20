package com.tinvestanalyst.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Чем закончилась выданная рекомендация. */
enum class RecommendationOutcome(val title: String) {
    OPEN("в работе"),
    TARGET("дошла до цели"),
    STOP("выбило стопом"),
    EXPIRED("срок вышел"),
}

@Serializable
data class RecommendationRecord(
    val id: String,
    val figi: String,
    val ticker: String,
    val name: String,
    val verdict: String,
    val verdictLabel: String,
    val score: Double,
    val confidence: Int,
    val horizon: String,
    val priceAtIssue: Double,
    val stopPrice: Double? = null,
    val targetPrice: Double? = null,
    val createdAtMillis: Long,
    val outcome: String = "OPEN",
    val lastPrice: Double = 0.0,
    val maxPriceSeen: Double = 0.0,
    val minPriceSeen: Double = 0.0,
    val closedAtMillis: Long? = null,
) {
    val outcomeEnum: RecommendationOutcome
        get() = runCatching { RecommendationOutcome.valueOf(outcome) }
            .getOrDefault(RecommendationOutcome.OPEN)

    /** Результат в процентах от цены на момент выдачи рекомендации. */
    val resultPercent: Double
        get() = if (priceAtIssue <= 0 || lastPrice <= 0) 0.0 else
            (lastPrice - priceAtIssue) / priceAtIssue * 100
}

data class JournalStats(
    val total: Int,
    val closed: Int,
    val reachedTarget: Int,
    val stopped: Int,
    val expired: Int,
    val averageResultPercent: Double,
    val hitRatePercent: Double,
)

/**
 * Журнал выданных рекомендаций. Приложение, которое советует и тут же
 * забывает свой совет, проверить невозможно — поэтому каждый нехолдовый
 * вердикт записывается с ценой и датой, а дальше по котировкам видно, чем он
 * закончился. Это единственный способ узнать, стоит ли доверять советчику.
 *
 * Судьба отслеживается по опросу цены, а не поминутно: если между опросами
 * цена успела сходить к стопу и вернуться, журнал этого не увидит. Поэтому
 * его статистика — оценка сверху, а не точный учёт сделок.
 */
class RecommendationJournal private constructor(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, FILE_NAME)

    private val _records = MutableStateFlow<List<RecommendationRecord>>(emptyList())
    val records: StateFlow<List<RecommendationRecord>> = _records.asStateFlow()

    init {
        _records.value = read()
    }

    /**
     * Новая запись создаётся, только если это действительно новый совет:
     * тот же вердикт по той же бумаге в течение суток — это та же самая
     * рекомендация, а не подтверждение, и засорять ей статистику нельзя.
     */
    fun record(record: RecommendationRecord): Boolean {
        val duplicate = _records.value.any { existing ->
            existing.figi == record.figi &&
                existing.verdict == record.verdict &&
                (
                    existing.outcomeEnum == RecommendationOutcome.OPEN ||
                        record.createdAtMillis - existing.createdAtMillis < DEDUPE_WINDOW_MILLIS
                    )
        }
        if (duplicate) return false
        val updated = (_records.value + record).takeLast(MAX_RECORDS)
        _records.value = updated
        write(updated)
        return true
    }

    /**
     * Подтягивает судьбу открытых рекомендаций к текущим ценам: фиксирует
     * достижение цели или стопа и закрывает записи, у которых вышел срок.
     */
    fun updateOutcomes(prices: Map<String, Double>, maxAgeDays: Int = 45) {
        if (_records.value.isEmpty()) return
        val now = System.currentTimeMillis()
        var changed = false

        val updated = _records.value.map { record ->
            if (record.outcomeEnum != RecommendationOutcome.OPEN) return@map record
            val price = prices[record.figi] ?: return@map record
            if (price <= 0) return@map record

            val high = if (record.maxPriceSeen <= 0) price else maxOf(record.maxPriceSeen, price)
            val low = if (record.minPriceSeen <= 0) price else minOf(record.minPriceSeen, price)
            val ageDays = (now - record.createdAtMillis) / 86_400_000L

            val bullish = record.verdict.contains("BUY")
            val outcome = when {
                bullish && record.targetPrice != null && price >= record.targetPrice ->
                    RecommendationOutcome.TARGET
                bullish && record.stopPrice != null && price <= record.stopPrice ->
                    RecommendationOutcome.STOP
                // Для рекомендации на продажу «цель» — это падение цены:
                // совет считается сбывшимся, если бумага действительно упала.
                !bullish && price <= record.priceAtIssue * (1 - SELL_TARGET_PERCENT / 100) ->
                    RecommendationOutcome.TARGET
                !bullish && price >= record.priceAtIssue * (1 + SELL_TARGET_PERCENT / 100) ->
                    RecommendationOutcome.STOP
                ageDays >= maxAgeDays -> RecommendationOutcome.EXPIRED
                else -> RecommendationOutcome.OPEN
            }

            changed = true
            record.copy(
                lastPrice = price,
                maxPriceSeen = high,
                minPriceSeen = low,
                outcome = outcome.name,
                closedAtMillis = if (outcome == RecommendationOutcome.OPEN) null else now,
            )
        }

        if (changed) {
            _records.value = updated
            write(updated)
        }
    }

    fun stats(): JournalStats {
        val all = _records.value
        val closed = all.filter { it.outcomeEnum != RecommendationOutcome.OPEN }
        val target = closed.count { it.outcomeEnum == RecommendationOutcome.TARGET }
        val stopped = closed.count { it.outcomeEnum == RecommendationOutcome.STOP }
        val expired = closed.count { it.outcomeEnum == RecommendationOutcome.EXPIRED }
        val scored = closed.filter { it.lastPrice > 0 }
        // Рекомендация на продажу считается удачной при падении цены,
        // поэтому её результат разворачивается по знаку.
        val average = if (scored.isEmpty()) 0.0 else scored.sumOf {
            if (it.verdict.contains("BUY")) it.resultPercent else -it.resultPercent
        } / scored.size
        return JournalStats(
            total = all.size,
            closed = closed.size,
            reachedTarget = target,
            stopped = stopped,
            expired = expired,
            averageResultPercent = average,
            hitRatePercent = if (closed.isEmpty()) 0.0 else target * 100.0 / closed.size,
        )
    }

    fun clear() {
        _records.value = emptyList()
        runCatching { file.delete() }
    }

    private fun read(): List<RecommendationRecord> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.decodeFromString<RecommendationRecord>(line) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun write(records: List<RecommendationRecord>) {
        runCatching {
            file.writeText(records.joinToString("\n") { json.encodeToString(RecommendationRecord.serializer(), it) })
        }
    }

    companion object {
        private const val FILE_NAME = "recommendation-journal.jsonl"
        private const val MAX_RECORDS = 500
        private const val DEDUPE_WINDOW_MILLIS = 24 * 60 * 60 * 1000L

        /** Насколько должна упасть бумага, чтобы совет «продавать» считался сбывшимся. */
        private const val SELL_TARGET_PERCENT = 5.0

        @Volatile
        private var instance: RecommendationJournal? = null

        fun get(context: Context): RecommendationJournal =
            instance ?: synchronized(this) {
                instance ?: RecommendationJournal(context.applicationContext).also { instance = it }
            }
    }
}
