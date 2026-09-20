package com.tinvestanalyst.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tinvestanalyst.analysis.AnalysisBlock
import com.tinvestanalyst.analysis.AnalysisFactor
import com.tinvestanalyst.analysis.NewsAnalyzer
import com.tinvestanalyst.analysis.PositionPlan
import com.tinvestanalyst.analysis.ScoredNews
import com.tinvestanalyst.analysis.UpcomingEvent
import com.tinvestanalyst.analysis.fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstrumentDetailScreen(viewModel: AnalystViewModel, onBack: () -> Unit) {
    val detail by viewModel.detail.collectAsState()
    val analysis = detail?.analysis

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(analysis?.instrument?.ticker ?: "Разбор инструмента") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (detail?.loading == true && analysis == null) {
                item { Text("Загружаю данные с биржи...", Modifier.padding(16.dp)) }
            }

            detail?.error?.let { error ->
                item {
                    Text(
                        "Ошибка: $error",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            if (analysis == null) return@LazyColumn

            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(analysis.instrument.name, style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(fmt(analysis.lastPrice), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${fmt(analysis.changePercent)}% за период",
                            color = if (analysis.changePercent >= 0) BUY_COLOR else SELL_COLOR,
                        )
                    }
                    Text(
                        listOfNotNull(
                            detail?.tradingStatus,
                            "обновлено ${formatClock(analysis.generatedAtMillis)}",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            analysis.verdict.label,
                            color = colorFor(analysis.verdict),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Уверенность ${analysis.confidencePercent}%",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            analysis.summary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            analysis.coverageNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = HOLD_COLOR,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        analysis.dataNote?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            analysis.positionPlan?.let { plan ->
                item { PositionPlanCard(plan, analysis.instrument.currency) }
            }

            if (analysis.events.isNotEmpty()) {
                item { EventsCard(analysis.events) }
            }

            item {
                CandleChart(
                    candles = analysis.candles,
                    fastSmaSeries = analysis.fastSmaSeries,
                    slowSmaSeries = analysis.slowSmaSeries,
                )
            }

            if (analysis.news.isNotEmpty()) {
                item { NewsCard(analysis.news, analysis.newsTone) }
            }

            analysis.blocks.forEach { block ->
                item { BlockHeader(block) }
                block.factors.forEach { factor ->
                    item { FactorCard(factor) }
                }
            }

            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Волатильность", style = MaterialTheme.typography.titleSmall)
                        Text(
                            analysis.volatilityNote,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        detail?.orderBookNote?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Учтены цены, объёмы, отчётность эмитента, дивиденды, календарь событий " +
                        "и новостной фон. Тональность новостей считается по финансовому словарю, " +
                        "а не по смыслу текста, поэтому заголовки показаны отдельно — проверяйте их. " +
                        "Это не индивидуальная инвестиционная рекомендация — решение принимаете вы.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun BlockHeader(block: AnalysisBlock) {
    val color = when {
        block.normalized > 0.1 -> BUY_COLOR
        block.normalized < -0.1 -> SELL_COLOR
        else -> HOLD_COLOR
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(block.title, style = MaterialTheme.typography.titleMedium)
        Text(
            "${fmt(block.normalized)} · вес ${(block.weight * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Карточка сделки: где вход, стоп и цель, сколько брать и нужно ли плечо.
 * Всё считается от допустимого убытка на сделку, а не от размера счёта.
 */
@Composable
private fun PositionPlanCard(plan: PositionPlan, currency: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Расчёт сделки и риска", style = MaterialTheme.typography.titleMedium)

            PlanRow("Вход", fmt(plan.entryPrice))
            PlanRow("Стоп-лосс (1.5 ATR)", fmt(plan.stopPrice), SELL_COLOR)
            PlanRow("Цель (2.5 ATR)", fmt(plan.targetPrice), BUY_COLOR)
            PlanRow("Отношение прибыль/риск", "${fmt(plan.riskRewardRatio)} : 1")

            if (!plan.blocked) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                PlanRow("Размер позиции", "${plan.lots} лот(ов) = ${plan.shares} шт.")
                PlanRow("Стоимость позиции", "${fmt(plan.positionValue)} ${currency.uppercase()}")
                PlanRow("Под риском", "${fmt(plan.moneyAtRisk)} ${currency.uppercase()}", SELL_COLOR)
                PlanRow("Своих средств нужно", "${fmt(plan.ownFundsRequired)} ${currency.uppercase()}")
                PlanRow("Используемое плечо", "${fmt(plan.leverageUsed)}x")
            }
            plan.maxLeverageAvailable?.let {
                PlanRow("Доступное плечо по бумаге", "${fmt(it)}x")
            }

            plan.notes.forEach { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (plan.blocked) MaterialTheme.colorScheme.error else HOLD_COLOR,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PlanRow(label: String, value: String, color: androidx.compose.ui.graphics.Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun EventsCard(events: List<UpcomingEvent>) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Ближайшие события", style = MaterialTheme.typography.titleMedium)
            events.take(4).forEach { event ->
                Column(Modifier.padding(top = 8.dp)) {
                    Text(
                        "${event.title} — ${event.date} (через ${event.daysAway} дн.)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (event.daysAway <= 5) STRONG_SELL_COLOR else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(event.detail, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * Заголовки, на которых построена оценка новостного фона. Показываются
 * целиком и с собственной тональностью: вывод словаря должно быть можно
 * проверить глазами, а не принимать на веру.
 */
@Composable
private fun NewsCard(news: List<ScoredNews>, tone: Double?) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Новостной фон", style = MaterialTheme.typography.titleMedium)
                tone?.let {
                    Text(
                        "позитив ${NewsAnalyzer.tonePercent(it)}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            it > 0.12 -> BUY_COLOR
                            it < -0.12 -> SELL_COLOR
                            else -> HOLD_COLOR
                        },
                    )
                }
            }

            news.take(8).forEach { scored ->
                val color = when {
                    scored.sentiment > 0.05 -> BUY_COLOR
                    scored.sentiment < -0.05 -> SELL_COLOR
                    else -> HOLD_COLOR
                }
                Column(Modifier.padding(top = 10.dp)) {
                    Text(
                        scored.item.title,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${scored.item.source} · ${agoLabel(scored.hoursAgo)} · " +
                            "оценка ${fmt(scored.sentiment)}" +
                            if (scored.matchedWords.isEmpty()) {
                                " (слов из словаря нет — нейтрально)"
                            } else {
                                " по словам: ${scored.matchedWords.joinToString(", ")}"
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                }
            }

            if (news.size > 8) {
                Text(
                    "...и ещё ${news.size - 8} публикаций учтены в оценке.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun agoLabel(hours: Int): String = when {
    hours < 1 -> "только что"
    hours < 24 -> "$hours ч назад"
    else -> "${hours / 24} дн. назад"
}

@Composable
private fun FactorCard(factor: AnalysisFactor) {
    val color = when {
        factor.score > 0 -> BUY_COLOR
        factor.score < 0 -> SELL_COLOR
        else -> HOLD_COLOR
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(factor.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${if (factor.score > 0) "+" else ""}${factor.score} / ±${factor.weight}",
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(factor.reading, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(factor.interpretation, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}
