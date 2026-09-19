package com.tinvesttrader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Токен и торговый режим никогда не пишутся в обычные SharedPreferences —
 * это ключ доступа к брокерскому счёту. EncryptedSharedPreferences шифрует
 * значение на диске ключом из Android Keystore.
 */
class SecureTokenStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "tinvest_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var sandboxToken: String?
        get() = prefs.getString(KEY_SANDBOX_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_SANDBOX_TOKEN, value).apply()

    var liveToken: String?
        get() = prefs.getString(KEY_LIVE_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_LIVE_TOKEN, value).apply()

    /** true только после явного двойного подтверждения в UI (см. SettingsScreen). */
    var liveTradingEnabled: Boolean
        get() = prefs.getBoolean(KEY_LIVE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_ENABLED, value).apply()

    var accountId: String?
        get() = prefs.getString(KEY_ACCOUNT_ID, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_ID, value).apply()

    var instrumentFigi: String?
        get() = prefs.getString(KEY_FIGI, null)
        set(value) = prefs.edit().putString(KEY_FIGI, value).apply()

    /** Человекочитаемые подписи выбранного счёта и бумаги — только для интерфейса. */
    var accountLabel: String?
        get() = prefs.getString(KEY_ACCOUNT_LABEL, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_LABEL, value).apply()

    var instrumentLabel: String?
        get() = prefs.getString(KEY_FIGI_LABEL, null)
        set(value) = prefs.edit().putString(KEY_FIGI_LABEL, value).apply()

    private companion object {
        const val KEY_SANDBOX_TOKEN = "sandbox_token"
        const val KEY_LIVE_TOKEN = "live_token"
        const val KEY_LIVE_ENABLED = "live_trading_enabled"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_FIGI = "instrument_figi"
        const val KEY_ACCOUNT_LABEL = "account_label"
        const val KEY_FIGI_LABEL = "instrument_label"
    }
}
