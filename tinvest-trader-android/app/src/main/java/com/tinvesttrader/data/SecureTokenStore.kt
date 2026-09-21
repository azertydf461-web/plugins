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

    /**
     * Таймфрейм свечей. По умолчанию он совпадает с периодом пробуждения
     * бота: если брать свечи мельче, чем частота проверок, бот физически не
     * увидит часть пересечений — они случатся на свечах, до которых он ни
     * разу не доберётся.
     */
    var candleInterval: String
        get() = prefs.getString(KEY_INTERVAL, DEFAULT_INTERVAL) ?: DEFAULT_INTERVAL
        set(value) = prefs.edit().putString(KEY_INTERVAL, value).apply()

    /** "PERCENT" — фиксированный процент, "ATR" — от волатильности бумаги. */
    var stopMode: String
        get() = prefs.getString(KEY_STOP_MODE, DEFAULT_STOP_MODE) ?: DEFAULT_STOP_MODE
        set(value) = prefs.edit().putString(KEY_STOP_MODE, value).apply()

    var stopLossPercent: Double
        get() = prefs.getString(KEY_STOP_PERCENT, null)?.toDoubleOrNull() ?: 3.0
        set(value) = prefs.edit().putString(KEY_STOP_PERCENT, value.toString()).apply()

    var atrMultiplier: Double
        get() = prefs.getString(KEY_ATR_MULTIPLIER, null)?.toDoubleOrNull() ?: 2.0
        set(value) = prefs.edit().putString(KEY_ATR_MULTIPLIER, value.toString()).apply()

    /** Ставить ли защитную стоп-заявку на стороне брокера после покупки. */
    var protectiveStopEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROTECTIVE_STOP, true)
        set(value) = prefs.edit().putBoolean(KEY_PROTECTIVE_STOP, value).apply()

    /** Идентификатор выставленной стоп-заявки: по нему её потом снимают. */
    var protectiveStopOrderId: String?
        get() = prefs.getString(KEY_STOP_ORDER_ID, null)
        set(value) = prefs.edit().putString(KEY_STOP_ORDER_ID, value).apply()

    /** "TREND" — пересечение с фильтрами входа, "SMA" — голое пересечение. */
    var strategyMode: String
        get() = prefs.getString(KEY_STRATEGY, DEFAULT_STRATEGY) ?: DEFAULT_STRATEGY
        set(value) = prefs.edit().putString(KEY_STRATEGY, value).apply()

    /** Подтягивать ли стоп вслед за ценой, пока позиция в прибыли. */
    var trailingStopEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRAILING, true)
        set(value) = prefs.edit().putBoolean(KEY_TRAILING, value).apply()

    /** Максимум цены с момента входа — от него отсчитывается подтянутый стоп. */
    var positionHighWaterPrice: Double
        get() = prefs.getString(KEY_HIGH_WATER, null)?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString(KEY_HIGH_WATER, value.toString()).apply()

    /** Цена выставленного стопа — порог для проверки в цикле бота. */
    var protectiveStopPrice: Double
        get() = prefs.getString(KEY_STOP_PRICE, null)?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString(KEY_STOP_PRICE, value.toString()).apply()

    /**
     * Время последней разобранной свечи. По нему бот понимает, какой кусок
     * истории он проспал, и досматривает пропущенные свечи на пересечения.
     */
    var lastProcessedCandleTime: String?
        get() = prefs.getString(KEY_LAST_CANDLE, null)
        set(value) = prefs.edit().putString(KEY_LAST_CANDLE, value).apply()

    private companion object {
        const val KEY_SANDBOX_TOKEN = "sandbox_token"
        const val KEY_LIVE_TOKEN = "live_token"
        const val KEY_LIVE_ENABLED = "live_trading_enabled"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_FIGI = "instrument_figi"
        const val KEY_ACCOUNT_LABEL = "account_label"
        const val KEY_FIGI_LABEL = "instrument_label"
        const val KEY_INTERVAL = "candle_interval"
        const val KEY_STOP_MODE = "stop_mode"
        const val KEY_STOP_PERCENT = "stop_loss_percent"
        const val KEY_ATR_MULTIPLIER = "atr_multiplier"
        const val KEY_PROTECTIVE_STOP = "protective_stop_enabled"
        const val KEY_STOP_ORDER_ID = "protective_stop_order_id"
        const val KEY_STRATEGY = "strategy_mode"
        const val KEY_TRAILING = "trailing_stop_enabled"
        const val KEY_HIGH_WATER = "position_high_water"
        const val KEY_STOP_PRICE = "protective_stop_price"
        const val DEFAULT_STRATEGY = "TREND"
        const val KEY_LAST_CANDLE = "last_processed_candle_time"
        const val DEFAULT_INTERVAL = "CANDLE_INTERVAL_15_MIN"
        const val DEFAULT_STOP_MODE = "ATR"
    }
}
