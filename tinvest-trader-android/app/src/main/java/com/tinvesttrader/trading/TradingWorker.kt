package com.tinvesttrader.trading

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tinvesttrader.data.SecureTokenStore
import com.tinvesttrader.data.TInvestRepository
import java.util.concurrent.TimeUnit

/**
 * Периодический запуск движка через WorkManager. Минимальный интервал
 * PeriodicWorkRequest в Android — 15 минут; для прототипа этого достаточно
 * (это НЕ HFT, см. README). Для более частого опроса нужен foreground-сервис
 * с собственным таймером — сознательно не делаем это первым шагом, чтобы не
 * держать бота включённым в фоне без явного контроля пользователя.
 */
class TradingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tokenStore = SecureTokenStore(applicationContext)
        val journal = DecisionJournal.get(applicationContext)
        val engine = TradingEngine(
            repository = TInvestRepository(tokenStore),
            strategy = SmaCrossoverStrategy(),
            riskManager = RiskManagerHolder.getOrCreate(),
            tokenStore = tokenStore,
            journal = journal,
        )

        return try {
            engine.tick()
            Result.success()
        } catch (e: Exception) {
            journal.append(
                DecisionRecord(
                    timestampMillis = System.currentTimeMillis(),
                    figi = tokenStore.instrumentFigi.orEmpty(),
                    action = DecisionAction.ERROR.name,
                    headline = "Сбой в цикле бота",
                    marketReasoning = listOf("Непредвиденная ошибка: ${e.message}"),
                ),
            )
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "tinvest_trading_cycle"

        fun enable(context: Context) {
            val request = PeriodicWorkRequestBuilder<TradingWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun disable(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}

/** Держит один и тот же RiskManager между тиками воркера, а не новый каждый раз. */
object RiskManagerHolder {
    private var instance: RiskManager? = null

    @Synchronized
    fun getOrCreate(limits: RiskLimits = RiskLimits()): RiskManager =
        instance ?: RiskManager(limits).also { instance = it }
}
