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
 * (это НЕ HFT, см. README). Для более частого опроса в первой версии
 * лучше держать приложение на переднем плане и использовать foreground-сервис
 * с собственным таймером — сознательно не делаем это первым шагом, чтобы не
 * держать бота включённым в фоне без явного контроля пользователя.
 */
class TradingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tokenStore = SecureTokenStore(applicationContext)
        val repository = TInvestRepository(tokenStore)
        val strategy = SmaCrossoverStrategy()
        val riskManager = RiskManagerHolder.getOrCreate()
        val engine = TradingEngine(repository, strategy, riskManager, tokenStore)

        return try {
            val event = engine.tick()
            EngineEventLog.append(event)
            Result.success()
        } catch (e: Exception) {
            EngineEventLog.append(EngineEvent.Error("Worker crashed: ${e.message}"))
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

/** Простой лог событий движка для отображения в UI — в проде заменить на Room. */
object EngineEventLog {
    private val _events = mutableListOf<EngineEvent>()
    val events: List<EngineEvent> get() = _events.toList()

    @Synchronized
    fun append(event: EngineEvent) {
        _events.add(0, event)
        if (_events.size > 200) _events.removeAt(_events.lastIndex)
    }
}
