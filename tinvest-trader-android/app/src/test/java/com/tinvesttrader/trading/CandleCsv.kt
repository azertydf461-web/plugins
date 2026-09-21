package com.tinvesttrader.trading

import com.tinvesttrader.data.Candle
import com.tinvesttrader.data.Quotation
import java.io.File
import kotlin.math.floor

/**
 * Разбор свечей из CSV, который отдают загрузчики в CI:
 * open,high,low,close,volume,begin — ровно в этом порядке, с заголовком.
 *
 * Читают его все отчёты-прогоны, поэтому формат живёт в одном месте: разойдись
 * копии, и два отчёта начнут считать по-разному на одних и тех же файлах.
 */
fun readCandlesCsv(file: File): List<Candle> = file.readLines()
    .drop(1)
    .mapNotNull { line ->
        val parts = line.split(',')
        if (parts.size < 6) return@mapNotNull null
        val open = parts[0].toDoubleOrNull() ?: return@mapNotNull null
        val high = parts[1].toDoubleOrNull() ?: return@mapNotNull null
        val low = parts[2].toDoubleOrNull() ?: return@mapNotNull null
        val close = parts[3].toDoubleOrNull() ?: return@mapNotNull null
        if (close <= 0 || high <= 0 || low <= 0) return@mapNotNull null
        Candle(
            open = open.toCsvQuotation(),
            high = high.toCsvQuotation(),
            low = low.toCsvQuotation(),
            close = close.toCsvQuotation(),
            volume = parts[4].substringBefore('.'),
            time = parts[5].trim(),
        )
    }

private fun Double.toCsvQuotation(): Quotation {
    val units = floor(this).toLong()
    return Quotation(units = units.toString(), nano = ((this - units) * 1_000_000_000).toInt())
}
