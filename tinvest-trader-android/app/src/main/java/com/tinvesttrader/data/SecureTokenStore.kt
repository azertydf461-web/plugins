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
        // Минутные таймфреймы на истории убыточны; сохранённое старое значение
        // переводится на дневные свечи, а не тянет бота обратно в минус.
        get() = prefs.getString(KEY_INTERVAL, DEFAULT_INTERVAL)
            ?.takeIf { it == INTERVAL_DAY || it == INTERVAL_HOUR } ?: DEFAULT_INTERVAL
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

    /**
     * Базовый размер позиции в лотах. Нужен режиму, где фильтры режут объём:
     * из одного лота треть не выкроить, и уменьшать было бы нечего.
     */
    var baseLots: Long
        get() = prefs.getString(KEY_BASE_LOTS, null)?.toLongOrNull() ?: 3
        set(value) = prefs.edit().putString(KEY_BASE_LOTS, value.toString()).apply()

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

    // ---- Криптобиржа (Bybit, спот) ----

    var cryptoApiKey: String?
        get() = prefs.getString(KEY_CRYPTO_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_CRYPTO_API_KEY, value).apply()

    var cryptoApiSecret: String?
        get() = prefs.getString(KEY_CRYPTO_API_SECRET, null)
        set(value) = prefs.edit().putString(KEY_CRYPTO_API_SECRET, value).apply()

    /** true — боевой контур биржи. Включается только после ввода фразы подтверждения. */
    var cryptoLiveEnabled: Boolean
        get() = prefs.getBoolean(KEY_CRYPTO_LIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_CRYPTO_LIVE, value).apply()

    var cryptoSymbol: String
        get() = prefs.getString(KEY_CRYPTO_SYMBOL, "BTCUSDT") ?: "BTCUSDT"
        set(value) = prefs.edit().putString(KEY_CRYPTO_SYMBOL, value.trim().uppercase()).apply()

    /** Сумма одной покупки в валюте котировки (USDT). */
    var cryptoOrderQuoteAmount: Double
        get() = prefs.getString(KEY_CRYPTO_AMOUNT, null)?.toDoubleOrNull() ?: 50.0
        set(value) = prefs.edit().putString(KEY_CRYPTO_AMOUNT, value.toString()).apply()

    /** INTERVAL_DAY или INTERVAL_HOUR — те же значения, что у бота Т-Инвестиций. */
    var cryptoInterval: String
        get() = prefs.getString(KEY_CRYPTO_INTERVAL, INTERVAL_DAY)
            ?.takeIf { it == INTERVAL_DAY || it == INTERVAL_HOUR } ?: INTERVAL_DAY
        set(value) = prefs.edit().putString(KEY_CRYPTO_INTERVAL, value).apply()

    var cryptoLastCandleTime: String?
        get() = prefs.getString(KEY_CRYPTO_LAST_CANDLE, null)
        set(value) = prefs.edit().putString(KEY_CRYPTO_LAST_CANDLE, value).apply()

    var cryptoPositionBaseline: String?
        get() = prefs.getString(KEY_CRYPTO_BASELINE, null)
        set(value) = prefs.edit().putString(KEY_CRYPTO_BASELINE, value).apply()

    companion object {
        const val INTERVAL_DAY = "CANDLE_INTERVAL_DAY"
        const val INTERVAL_HOUR = "CANDLE_INTERVAL_HOUR"

        private const val KEY_CRYPTO_API_KEY = "crypto_api_key"
        private const val KEY_CRYPTO_API_SECRET = "crypto_api_secret"
        private const val KEY_CRYPTO_LIVE = "crypto_live_enabled"
        private const val KEY_CRYPTO_SYMBOL = "crypto_symbol"
        private const val KEY_CRYPTO_AMOUNT = "crypto_order_quote_amount"
        private const val KEY_CRYPTO_INTERVAL = "crypto_interval"
        private const val KEY_CRYPTO_LAST_CANDLE = "crypto_last_candle_time"
        private const val KEY_CRYPTO_BASELINE = "crypto_position_baseline"

        private const val KEY_SANDBOX_TOKEN = "sandbox_token"
        private const val KEY_LIVE_TOKEN = "live_token"
        private const val KEY_LIVE_ENABLED = "live_trading_enabled"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_FIGI = "instrument_figi"
        private const val KEY_ACCOUNT_LABEL = "account_label"
        private const val KEY_FIGI_LABEL = "instrument_label"
        private const val KEY_INTERVAL = "candle_interval"
        private const val KEY_STOP_MODE = "stop_mode"
        private const val KEY_STOP_PERCENT = "stop_loss_percent"
        private const val KEY_ATR_MULTIPLIER = "atr_multiplier"
        private const val KEY_PROTECTIVE_STOP = "protective_stop_enabled"
        private const val KEY_STOP_ORDER_ID = "protective_stop_order_id"
        private const val KEY_STRATEGY = "strategy_mode"
        private const val KEY_TRAILING = "trailing_stop_enabled"
        private const val KEY_BASE_LOTS = "base_lots"
        private const val KEY_HIGH_WATER = "position_high_water"
        private const val KEY_STOP_PRICE = "protective_stop_price"
        private const val DEFAULT_STRATEGY = "DONCHIAN"
        private const val KEY_LAST_CANDLE = "last_processed_candle_time"
        private const val DEFAULT_INTERVAL = INTERVAL_DAY
        private const val DEFAULT_STOP_MODE = "ATR"
    }
}
