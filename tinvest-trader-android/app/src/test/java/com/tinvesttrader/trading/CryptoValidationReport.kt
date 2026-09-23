package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.pow
import org.junit.Test

/**
 * Три проверки крипто-зацепки:
 *  1. только 2022–2026 и сравнение с удержанием за тот же период;
 *  2. без отбора выживших — монеты, входившие в двадцатку крупнейших в начале
 *     2018 года, и громкие падения 2021–2022, а не только дожившие до сегодня;
 *  3. портфель: капитал поровну между монетами, у каждой своя доля.
 *
 * Правило: пробой 20/10 на дневных, только покупка, решение по закрытию,
 * исполнение по открытию следующего дня, комиссия 0,1 % + спред 0,02 % за сторону.
 */
class CryptoValidationReport {

    private val costHalf = (0.1 + 0.02) / 100
    private val entry = 20
    private val exit = 10

    private val survivors = listOf("BTC", "ETH", "ADA", "BNB", "DOGE", "LINK", "LTC", "SOL", "TRX", "XRP")

    // Двадцатка крупнейших по капитализации на начало 2018 года (по памяти, приблизительно;
    // стейблкоины исключены).
    private val top2018 = listOf(
        "BTC", "ETH", "XRP", "BCH", "ADA", "LTC", "XEM", "XLM", "IOTA", "DASH",
        "NEO", "EOS", "XMR", "QTUM", "BTG", "TRX", "ICX", "LSK", "ETC", "OMG",
    )

    // Крупные монеты 2021 года, которые потом обвалились или умерли.
    private val fallen2021 = listOf("LUNC", "FTT", "BSV", "THETA", "VET", "FIL", "XTZ", "EOS", "ALGO", "ICP")

    private data class Series(val name: String, val days: List<String>, val candles: List<Candle>)

    @Test
    fun printReport() {
        val dir = File(System.getenv("CRYPTO_VALIDATION_DIR") ?: "app/build/crypto-validation")
        val files = dir.listFiles { f -> f.extension == "csv" }?.associateBy { it.nameWithoutExtension }
        if (files.isNullOrEmpty()) {
            println("Проверка крипты: данных нет (${dir.absolutePath}).")
            return
        }
        val all = files.mapValues { (name, file) ->
            val c = readCandlesCsv(file)
            Series(name, c.map { it.time.take(10) }, c)
        }
        fun group(names: List<String>) = names.mapNotNull { all[it] }.also { found ->
            val missing = names - found.map { it.name }.toSet()
            if (missing.isNotEmpty()) println("  нет данных: ${missing.joinToString()}")
        }

        println()
        println("==== Проверка 1. Только 2022-01-01 … сегодня, по монетам ====")
        perCoin(group(survivors), "2022-01-01")

        println()
        println("==== Проверка 2. Без отбора выживших: двадцатка начала 2018, с 2018-01-01 ====")
        perCoin(group(top2018), "2018-01-01")
        println()
        println("---- то же, только с 2022-01-01 ----")
        perCoin(group(top2018), "2022-01-01")
        println()
        println("---- крупные монеты 2021 года, которые потом обвалились, с 2021-01-01 ----")
        perCoin(group(fallen2021), "2021-01-01")

        println()
        println("==== Проверка 3. Портфель: капитал поровну между монетами ====")
        println(
            "набор".padEnd(44) + "монет".padStart(6) + "пробой".padStart(10) + "в год".padStart(8) +
                "просадка".padStart(10) + "удерж".padStart(10) + "в год".padStart(8) + "просадка".padStart(10),
        )
        portfolio("10 выживших, с 2018", group(survivors), "2018-01-01")
        portfolio("10 выживших, с 2022", group(survivors), "2022-01-01")
        portfolio("двадцатка 2018, с 2018", group(top2018), "2018-01-01")
        portfolio("двадцатка 2018, с 2022", group(top2018), "2022-01-01")
        portfolio("обвалившиеся 2021, с 2021", group(fallen2021), "2021-01-01")
        portfolio("все монеты вместе, с 2018", group((survivors + top2018 + fallen2021).distinct()), "2018-01-01")
        portfolio("все монеты вместе, с 2022", group((survivors + top2018 + fallen2021).distinct()), "2022-01-01")
    }

    private fun perCoin(coins: List<Series>, start: String) {
        println(
            "монета".padEnd(8) + "с".padStart(12) + "сделок".padStart(8) + "пробой".padStart(11) +
                "удерж".padStart(11) + "просадка".padStart(10) + "просадка уд.".padStart(14),
        )
        var positive = 0
        var beats = 0
        var n = 0
        val breakoutResults = mutableListOf<Double>()
        val holdResults = mutableListOf<Double>()
        coins.forEach { s ->
            val sim = simulate(s, start) ?: run {
                println(s.name.padEnd(8) + "  мало данных после $start")
                return@forEach
            }
            val b = (sim.breakout.last() - 1) * 100
            val h = (sim.hold.last() - 1) * 100
            println(
                s.name.padEnd(8) + sim.from.padStart(12) + sim.trades.toString().padStart(8) +
                    num(b).padStart(11) + num(h).padStart(11) + num(drawdown(sim.breakout)).padStart(10) +
                    num(drawdown(sim.hold)).padStart(14),
            )
            n++
            if (b > 0) positive++
            if (b > h) beats++
            breakoutResults += b
            holdResults += h
        }
        if (n > 0) {
            println(
                "итог: монет $n, пробой в плюсе $positive, лучше удержания $beats; " +
                    "медиана пробоя ${num(breakoutResults.sorted()[n / 2])}%, медиана удержания ${num(holdResults.sorted()[n / 2])}%",
            )
        }
    }

    private class Sim(val from: String, val days: List<String>, val breakout: List<Double>, val hold: List<Double>, val trades: Int)

    /**
     * Дневная стоимость доли (начальная 1.0) для пробоя и удержания начиная с [start].
     * Свечи до [start] используются только для расчёта канала.
     */
    private fun simulate(s: Series, start: String): Sim? {
        val c = s.candles
        val startIndex = s.days.indexOfFirst { it >= start }
        if (startIndex < 0) return null
        val first = startIndex.coerceAtLeast(entry + 1)
        if (first >= c.size - 30) return null
        val hi = c.map { it.high.toDouble() }
        val lo = c.map { it.low.toDouble() }
        val cl = c.map { it.close.toDouble() }
        val op = c.map { it.open.toDouble() }

        var cash = 1.0
        var qty = 0.0
        var trades = 0
        var pending = 0
        val breakout = mutableListOf<Double>()
        val hold = mutableListOf<Double>()
        val holdQty = (1 - costHalf) / op[first]
        val days = mutableListOf<String>()
        for (i in first until c.size) {
            if (pending == 1 && qty == 0.0) {
                qty = cash * (1 - costHalf) / op[i]; cash = 0.0; trades++
            } else if (pending == -1 && qty > 0) {
                cash = qty * op[i] * (1 - costHalf); qty = 0.0
            }
            pending = 0
            val upper = (i - entry until i).maxOf { hi[it] }
            val lower = (i - exit until i).minOf { lo[it] }
            if (qty == 0.0 && cl[i] > upper) pending = 1
            if (qty > 0 && cl[i] < lower) pending = -1
            breakout += cash + qty * cl[i]
            hold += holdQty * cl[i]
            days += s.days[i]
        }
        return Sim(s.days[first], days, breakout, hold, trades)
    }

    /** Капитал делится поровну между монетами; монета без истории на дату держит свою долю в деньгах. */
    private fun portfolio(label: String, coins: List<Series>, start: String) {
        val sims = coins.mapNotNull { s -> simulate(s, start)?.let { s.name to it } }
        if (sims.isEmpty()) return
        val calendar = sims.flatMap { it.second.days }.distinct().sorted()
        val share = 1.0 / sims.size
        fun curve(pick: (Sim) -> List<Double>): List<Double> {
            val maps = sims.map { (_, sim) -> sim.days.zip(pick(sim)).toMap() }
            val last = DoubleArray(sims.size) { 1.0 }
            return calendar.map { day ->
                maps.forEachIndexed { k, m -> m[day]?.let { last[k] = it } }
                last.sum() * share
            }
        }
        val b = curve { it.breakout }
        val h = curve { it.hold }
        val years = calendar.size / 365.0
        fun cagr(x: List<Double>) = (x.last().coerceAtLeast(1e-9).pow(1 / years) - 1) * 100
        println(
            label.padEnd(44) + sims.size.toString().padStart(6) + num((b.last() - 1) * 100).padStart(10) +
                num(cagr(b)).padStart(8) + num(drawdown(b)).padStart(10) +
                num((h.last() - 1) * 100).padStart(10) + num(cagr(h)).padStart(8) + num(drawdown(h)).padStart(10),
        )
    }

    private fun drawdown(v: List<Double>): Double {
        var peak = 0.0
        var dd = 0.0
        v.forEach { peak = max(peak, it); if (peak > 0) dd = max(dd, (peak - it) / peak * 100) }
        return dd
    }

    private fun num(v: Double) = String.format(Locale.US, "%.2f", v)
}
