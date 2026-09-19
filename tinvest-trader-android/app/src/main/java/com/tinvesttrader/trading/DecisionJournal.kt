package com.tinvesttrader.trading

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

enum class DecisionAction { BUY, SELL, HOLD, STOP_LOSS, BLOCKED, ERROR }

@Serializable
data class IndicatorReading(val label: String, val value: String)

/**
 * Одна запись хода принятия решения: что бот увидел на рынке, что сказал
 * риск-контроль и что он в итоге сделал. Пишется на каждой проверке, в том
 * числе когда бот решил ничего не делать — иначе картина неполная.
 */
@Serializable
data class DecisionRecord(
    val timestampMillis: Long,
    val figi: String,
    val action: String,
    val headline: String,
    val marketReasoning: List<String> = emptyList(),
    val riskReasoning: List<String> = emptyList(),
    val executionNote: String? = null,
    val indicators: List<IndicatorReading> = emptyList(),
) {
    val decisionAction: DecisionAction
        get() = runCatching { DecisionAction.valueOf(action) }.getOrDefault(DecisionAction.ERROR)
}

/**
 * Журнал хранится построчным JSON в приватной папке приложения: переживает
 * перезапуск, читается человеком и не тянет за собой базу данных ради
 * нескольких сотен записей.
 */
class DecisionJournal(context: Context) {

    private val file = File(context.filesDir, "decision-journal.jsonl")
    private val json = Json { ignoreUnknownKeys = true }
    private val _records = MutableStateFlow(loadFromDisk())
    val records: StateFlow<List<DecisionRecord>> = _records.asStateFlow()

    @Synchronized
    fun append(record: DecisionRecord) {
        val updated = (listOf(record) + _records.value).take(MAX_RECORDS)
        _records.value = updated
        runCatching {
            file.writeText(updated.joinToString("\n") { json.encodeToString(it) })
        }
    }

    @Synchronized
    fun clear() {
        _records.value = emptyList()
        runCatching { file.delete() }
    }

    private fun loadFromDisk(): List<DecisionRecord> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.decodeFromString<DecisionRecord>(line) }.getOrNull() }
    }.getOrDefault(emptyList())

    companion object {
        private const val MAX_RECORDS = 300

        @Volatile
        private var instance: DecisionJournal? = null

        fun get(context: Context): DecisionJournal =
            instance ?: synchronized(this) {
                instance ?: DecisionJournal(context.applicationContext).also { instance = it }
            }
    }
}
