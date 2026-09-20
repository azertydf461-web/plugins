package com.tinvestanalyst.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Токен — ключ доступа к брокерскому счёту, поэтому хранится в
 * EncryptedSharedPreferences (ключ шифрования лежит в Android Keystore),
 * а не в обычных настройках.
 */
class AnalystSettingsStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "tinvest_analyst_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var apiToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var candleInterval: String
        get() = prefs.getString(KEY_INTERVAL, DEFAULT_INTERVAL) ?: DEFAULT_INTERVAL
        set(value) = prefs.edit().putString(KEY_INTERVAL, value).apply()

    /** Капитал, от которого считается размер позиции. 0 — расчёт не делается. */
    var capital: Double
        get() = prefs.getString(KEY_CAPITAL, null)?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString(KEY_CAPITAL, value.toString()).apply()

    /** Сколько процентов капитала допустимо потерять на одной сделке. */
    var riskPerTradePercent: Double
        get() = prefs.getString(KEY_RISK_PERCENT, null)?.toDoubleOrNull() ?: 1.0
        set(value) = prefs.edit().putString(KEY_RISK_PERCENT, value.toString()).apply()

    /** Потолок плеча, который пользователь готов использовать (1.0 — без плеча). */
    var maxLeverage: Double
        get() = prefs.getString(KEY_MAX_LEVERAGE, null)?.toDoubleOrNull() ?: 1.0
        set(value) = prefs.edit().putString(KEY_MAX_LEVERAGE, value.toString()).apply()

    /** Горизонт: от него зависит, что важнее — техника или отчётность. */
    var horizon: String
        get() = prefs.getString(KEY_HORIZON, DEFAULT_HORIZON) ?: DEFAULT_HORIZON
        set(value) = prefs.edit().putString(KEY_HORIZON, value).apply()

    var watchlist: List<WatchedInstrument>
        get() = runCatching {
            prefs.getString(KEY_WATCHLIST, null)
                ?.let { json.decodeFromString<List<WatchedInstrument>>(it) }
                ?: emptyList()
        }.getOrDefault(emptyList())
        set(value) = prefs.edit().putString(KEY_WATCHLIST, json.encodeToString(value)).apply()

    fun addToWatchlist(instrument: WatchedInstrument) {
        if (watchlist.any { it.figi == instrument.figi }) return
        watchlist = watchlist + instrument
    }

    fun removeFromWatchlist(figi: String) {
        watchlist = watchlist.filterNot { it.figi == figi }
    }

    private companion object {
        const val KEY_TOKEN = "api_token"
        const val KEY_WATCHLIST = "watchlist"
        const val KEY_INTERVAL = "candle_interval"
        const val KEY_CAPITAL = "capital"
        const val KEY_RISK_PERCENT = "risk_per_trade_percent"
        const val KEY_MAX_LEVERAGE = "max_leverage"
        const val KEY_HORIZON = "horizon"
        const val DEFAULT_INTERVAL = "CANDLE_INTERVAL_15_MIN"
        const val DEFAULT_HORIZON = "SWING"
    }
}
