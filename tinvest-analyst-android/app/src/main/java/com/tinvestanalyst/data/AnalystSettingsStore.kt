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
        const val DEFAULT_INTERVAL = "CANDLE_INTERVAL_15_MIN"
    }
}
