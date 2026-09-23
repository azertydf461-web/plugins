package com.tinvesttrader.trading

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tinvesttrader.data.SecureTokenStore
import com.tinvesttrader.data.crypto.BybitClient
import java.util.concurrent.TimeUnit

/** Фоновый запуск криптобота раз в 15 минут — отдельно от бота Т-Инвестиций. */
class CryptoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = SecureTokenStore(applicationContext)
        val journal = DecisionJournal.get(applicationContext)
        val exchange = cryptoExchangeFrom(store) ?: run {
            journal.append(
                DecisionRecord(
                    timestampMillis = System.currentTimeMillis(),
                    figi = "BYBIT:${store.cryptoSymbol}",
                    action = DecisionAction.ERROR.name,
                    headline = "Ключи биржи не заданы",
                    marketReasoning = listOf("Введите ключ и секрет Bybit на экране «Криптовалюта»."),
                ),
            )
            return Result.success()
        }
        return try {
            CryptoTradingEngine(exchange, StoreCryptoBotSettings(store), journal::append).tick()
            Result.success()
        } catch (e: Exception) {
            journal.append(
                DecisionRecord(
                    timestampMillis = System.currentTimeMillis(),
                    figi = "BYBIT:${store.cryptoSymbol}",
                    action = DecisionAction.ERROR.name,
                    headline = "Сбой в цикле криптобота",
                    marketReasoning = listOf("Непредвиденная ошибка: ${e.message}"),
                ),
            )
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "crypto_trading_cycle"

        fun enable(context: Context) {
            val request = PeriodicWorkRequestBuilder<CryptoWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun disable(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}

/** Клиент биржи из сохранённых ключей; null — ключей нет. */
fun cryptoExchangeFrom(store: SecureTokenStore): BybitClient? {
    val key = store.cryptoApiKey?.takeIf { it.isNotBlank() } ?: return null
    val secret = store.cryptoApiSecret?.takeIf { it.isNotBlank() } ?: return null
    return BybitClient(key, secret, live = store.cryptoLiveEnabled)
}
