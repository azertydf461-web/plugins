package com.tinvestanalyst.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tinvestanalyst.analysis.fmt
import com.tinvestanalyst.data.Candle

private val UP_COLOR = Color(0xFF26A69A)
private val DOWN_COLOR = Color(0xFFEF5350)
private val FAST_SMA_COLOR = Color(0xFF42A5F5)
private val SLOW_SMA_COLOR = Color(0xFFFFA726)
private val GRID_COLOR = Color(0x33888888)

/**
 * Свечной график рисуется вручную на Canvas: готовая библиотека графиков
 * тянет заметный вес ради одного экрана, а здесь нужно всего три слоя —
 * свечи, две скользящие средние и объём.
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    fastSmaSeries: List<Double?>,
    slowSmaSeries: List<Double?>,
    modifier: Modifier = Modifier,
) {
    if (candles.size < 2) {
        Box(modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            Text("Недостаточно данных для графика", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = Color.Gray)
    val onSurface = MaterialTheme.colorScheme.onSurface

    Canvas(modifier.fillMaxWidth().height(260.dp).padding(vertical = 8.dp)) {
        val priceHeight = size.height * 0.78f
        val volumeTop = size.height * 0.84f
        val volumeHeight = size.height - volumeTop
        val rightPadding = 52f
        val chartWidth = size.width - rightPadding

        val highs = candles.map { it.high.toDouble() }
        val lows = candles.map { it.low.toDouble() }
        val maxPrice = highs.max()
        val minPrice = lows.min()
        val priceRange = (maxPrice - minPrice).takeIf { it > 0 } ?: 1.0

        fun priceToY(price: Double): Float =
            (priceHeight * (1 - (price - minPrice) / priceRange)).toFloat()

        val slotWidth = chartWidth / candles.size
        val bodyWidth = (slotWidth * 0.6f).coerceAtLeast(1.5f)

        // Горизонтальная сетка и подписи цены.
        listOf(0.0, 0.5, 1.0).forEach { fraction ->
            val price = minPrice + priceRange * (1 - fraction)
            val y = (priceHeight * fraction).toFloat()
            drawLine(GRID_COLOR, Offset(0f, y), Offset(chartWidth, y), strokeWidth = 1f)
            drawText(
                textMeasurer = textMeasurer,
                text = fmt(price),
                topLeft = Offset(chartWidth + 4f, y - 6f),
                style = labelStyle,
            )
        }

        val maxVolume = candles.maxOf { it.volumeAsDouble }.takeIf { it > 0 } ?: 1.0

        candles.forEachIndexed { index, candle ->
            val centerX = index * slotWidth + slotWidth / 2
            val open = candle.open.toDouble()
            val close = candle.close.toDouble()
            val color = if (close >= open) UP_COLOR else DOWN_COLOR

            drawLine(
                color = color,
                start = Offset(centerX, priceToY(candle.high.toDouble())),
                end = Offset(centerX, priceToY(candle.low.toDouble())),
                strokeWidth = 1.5f,
            )

            val bodyTop = priceToY(maxOf(open, close))
            val bodyBottom = priceToY(minOf(open, close))
            drawRect(
                color = color,
                topLeft = Offset(centerX - bodyWidth / 2, bodyTop),
                size = Size(bodyWidth, (bodyBottom - bodyTop).coerceAtLeast(1.5f)),
            )

            val barHeight = (candle.volumeAsDouble / maxVolume * volumeHeight).toFloat()
            drawRect(
                color = color.copy(alpha = 0.45f),
                topLeft = Offset(centerX - bodyWidth / 2, size.height - barHeight),
                size = Size(bodyWidth, barHeight),
            )
        }

        drawSmaLine(fastSmaSeries, slotWidth, FAST_SMA_COLOR) { priceToY(it) }
        drawSmaLine(slowSmaSeries, slotWidth, SLOW_SMA_COLOR) { priceToY(it) }

        drawLegend(textMeasurer, onSurface)
    }
}

private fun DrawScope.drawSmaLine(
    series: List<Double?>,
    slotWidth: Float,
    color: Color,
    priceToY: (Double) -> Float,
) {
    val path = Path()
    var started = false
    series.forEachIndexed { index, value ->
        if (value == null) return@forEachIndexed
        val x = index * slotWidth + slotWidth / 2
        val y = priceToY(value)
        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
    }
    if (started) drawPath(path, color, style = Stroke(width = 2f))
}

private fun DrawScope.drawLegend(textMeasurer: TextMeasurer, textColor: Color) {
    val style = TextStyle(fontSize = 10.sp, color = textColor)
    drawRect(FAST_SMA_COLOR, topLeft = Offset(4f, 10f), size = Size(14f, 3f))
    drawText(textMeasurer, "SMA 9", topLeft = Offset(22f, 2f), style = style)
    drawRect(SLOW_SMA_COLOR, topLeft = Offset(78f, 10f), size = Size(14f, 3f))
    drawText(textMeasurer, "SMA 21", topLeft = Offset(96f, 2f), style = style)
}
