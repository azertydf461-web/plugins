package com.tinvestanalyst.analysis

import com.tinvestanalyst.data.NewsItem
import com.tinvestanalyst.data.WatchedInstrument
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Новость, отнесённая к бумаге, с посчитанной тональностью. */
data class ScoredNews(
    val item: NewsItem,
    /** Тональность от -1 (резко негативная) до +1 (резко позитивная). */
    val sentiment: Double,
    val matchedWords: List<String>,
) {
    val hoursAgo: Int
        get() = ((System.currentTimeMillis() - item.publishedAtMillis) / 3_600_000L)
            .coerceAtLeast(0).toInt()
}

data class NewsAssessment(
    val factor: AnalysisFactor,
    val items: List<ScoredNews>,
    /** Взвешенная по свежести тональность, от -1 до +1. */
    val tone: Double,
)

/**
 * Новостной фон по бумаге. Тональность считается по словарю финансовых
 * терминов, а не нейросетью: это грубее, но работает офлайн, объяснимо и не
 * выдумывает смысл, которого в заголовке нет. Поэтому вес блока намеренно
 * ниже, чем у цены и отчётности, а каждый заголовок показывается пользователю
 * с его собственной оценкой — чтобы решение можно было перепроверить глазами.
 */
object NewsAnalyzer {

    /** Новости старше этого срока в расчёт не идут. */
    private const val MAX_AGE_HOURS = 96.0

    /** За сутки вес новости падает вдвое: вчерашний заголовок уже почти не рынок. */
    private const val HALF_LIFE_HOURS = 24.0

    fun assess(instrument: WatchedInstrument, news: List<NewsItem>): NewsAssessment? {
        if (news.isEmpty()) return null

        val keys = keywordsFor(instrument)
        if (keys.isEmpty()) return null

        val matched = news.mapNotNull { item ->
            val text = normalize(item.searchText)
            if (!keys.any { key -> containsWord(text, key) }) return@mapNotNull null
            val (sentiment, words) = sentimentOf(text)
            ScoredNews(item, sentiment, words)
        }.filter { it.hoursAgo <= MAX_AGE_HOURS }
            .sortedByDescending { it.item.publishedAtMillis }
            .take(20)

        if (matched.isEmpty()) {
            return NewsAssessment(
                factor = AnalysisFactor(
                    name = "Новостной фон",
                    score = 0,
                    weight = 2,
                    reading = "упоминаний за последние ${MAX_AGE_HOURS.toInt()} ч не найдено",
                    interpretation = "Бумага не в новостях: фон нейтральный, двигать цену " +
                        "будут технические факторы, а не информационный повод.",
                ),
                items = emptyList(),
                tone = 0.0,
            )
        }

        var weightSum = 0.0
        var toneSum = 0.0
        matched.forEach { scored ->
            val freshness = exp(-ln(2.0) * scored.hoursAgo / HALF_LIFE_HOURS)
            weightSum += freshness
            toneSum += scored.sentiment * freshness
        }
        val tone = if (weightSum == 0.0) 0.0 else toneSum / weightSum

        val score = when {
            tone >= 0.35 -> 2
            tone >= 0.12 -> 1
            tone <= -0.35 -> -2
            tone <= -0.12 -> -1
            else -> 0
        }

        val positives = matched.count { it.sentiment > 0.05 }
        val negatives = matched.count { it.sentiment < -0.05 }
        val spike = matched.count { it.hoursAgo <= 24 }

        val interpretation = buildString {
            append(
                when (score) {
                    2 -> "Фон отчётливо позитивный — новости работают на рост."
                    1 -> "Фон умеренно позитивный, но перевес небольшой."
                    -1 -> "Фон умеренно негативный: есть повод для осторожности."
                    -2 -> "Фон отчётливо негативный — новости давят на котировки."
                    else -> "Позитив и негатив уравновешены, направления фон не задаёт."
                },
            )
            if (spike >= 5) {
                append(
                    " За сутки $spike публикаций — бумага в центре внимания, " +
                        "движения могут быть резче обычного.",
                )
            }
            append(" Тональность считается по словарю, а не по смыслу текста: проверьте заголовки ниже.")
        }

        return NewsAssessment(
            factor = AnalysisFactor(
                name = "Новостной фон",
                score = score,
                weight = 2,
                reading = "${matched.size} публикаций за ${MAX_AGE_HOURS.toInt()} ч " +
                    "(позитивных $positives, негативных $negatives), " +
                    "взвешенная тональность ${signed(tone)}",
                interpretation = interpretation,
            ),
            items = matched,
            tone = tone,
        )
    }

    // --- Сопоставление новости и бумаги ------------------------------------

    private fun keywordsFor(instrument: WatchedInstrument): List<String> {
        val explicit = ALIASES[instrument.ticker.uppercase()]
        if (explicit != null) return explicit.map(::normalize)

        // Из названия берутся только содержательные слова: «ПАО», «ао», «обыкн.»
        // встречаются у сотен бумаг и дали бы ложные совпадения.
        val fromName = normalize(instrument.name)
            .split(' ', '-', '«', '»', '"', ',', '.')
            .map { it.trim() }
            .filter { it.length >= 4 && it !in STOP_WORDS }

        val ticker = instrument.ticker.lowercase()
        // Короткий тикер («ГМК», «МТС») сам по себе слишком шумный, длинный — нет.
        val tickerKey = if (ticker.length >= 4 && ticker.any { it.isLetter() }) listOf(ticker) else emptyList()
        return (fromName + tickerKey).distinct()
    }

    /** Совпадение ищется по началу слова: «газпром» находит и «газпрома». */
    private fun containsWord(text: String, key: String): Boolean {
        var from = 0
        while (true) {
            val index = text.indexOf(key, from)
            if (index < 0) return false
            val before = if (index == 0) ' ' else text[index - 1]
            if (!before.isLetterOrDigit()) return true
            from = index + 1
        }
    }

    // --- Тональность --------------------------------------------------------

    private fun sentimentOf(text: String): Pair<Double, List<String>> {
        val words = text.split(' ', ',', '.', ':', ';', '(', ')', '«', '»', '"', '—', '-')
            .filter { it.isNotBlank() }
        var raw = 0.0
        val hits = mutableListOf<String>()

        words.forEachIndexed { index, word ->
            // Хвост ограничен четырьмя буквами: так «рост» ловит «роста», но не
            // «Ростелеком», и словарь не срабатывает на случайно похожих словах.
            val weight = LEXICON.entries
                .firstOrNull { word.startsWith(it.key) && word.length - it.key.length <= 4 }
                ?.value ?: return@forEachIndexed
            // Грубый учёт отрицания: «не вырос» не должно читаться как рост.
            val negated = index > 0 && words[index - 1] in NEGATIONS
            raw += if (negated) -weight else weight
            hits += word
        }

        if (hits.isEmpty()) return 0.0 to emptyList()
        // Нормировка гасит эффект длинного текста: важен перевес, а не число слов.
        val normalized = raw / (abs(raw) + 3.0)
        return normalized.coerceIn(-1.0, 1.0) to hits.take(6)
    }

    private fun normalize(text: String): String = text.lowercase().replace('ё', 'е')

    private fun signed(value: Double): String =
        (if (value >= 0) "+" else "") + String.format("%.2f", value)

    /** Процент позитива для наглядной шкалы в интерфейсе. */
    fun tonePercent(tone: Double): Int = ((tone + 1) / 2 * 100).roundToInt().coerceIn(0, 100)

    private val NEGATIONS = setOf("не", "ни", "без")

    private val STOP_WORDS = setOf(
        "пао", "оао", "зао", "ооо", "акции", "акция", "обыкновенные", "привилегированные",
        "банк", "группа", "компания", "холдинг", "россии", "российская", "инвестиции",
        "облигация", "облигации", "выпуск", "фонд", "биржевой", "класс", "депозитарные",
        "расписки", "имени", "корпорация", "публичное", "общество", "акционерное",
    )

    /**
     * Ручные синонимы для бумаг, у которых официальное название и то, как
     * компанию зовут в новостях, расходятся: «Сбер Банк» против «Сбербанка».
     */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "SBER" to listOf("сбербанк", "сбер"),
        "SBERP" to listOf("сбербанк", "сбер"),
        "VTBR" to listOf("втб"),
        "GAZP" to listOf("газпром"),
        "LKOH" to listOf("лукойл"),
        "ROSN" to listOf("роснефть"),
        "GMKN" to listOf("норникель", "норильский никель", "гмк"),
        "YDEX" to listOf("яндекс"),
        "MGNT" to listOf("магнит"),
        "MTSS" to listOf("мтс"),
        "NVTK" to listOf("новатэк"),
        "TATN" to listOf("татнефть"),
        "TATNP" to listOf("татнефть"),
        "SNGS" to listOf("сургутнефтегаз"),
        "SNGSP" to listOf("сургутнефтегаз"),
        "CHMF" to listOf("северсталь"),
        "NLMK" to listOf("нлмк", "новолипецк"),
        "MAGN" to listOf("ммк", "магнитогорский"),
        "PLZL" to listOf("полюс"),
        "AFLT" to listOf("аэрофлот"),
        "MOEX" to listOf("московская биржа", "мосбиржа"),
        "PHOR" to listOf("фосагро"),
        "ALRS" to listOf("алроса"),
        "RUAL" to listOf("русал"),
        "IRAO" to listOf("интер рао"),
        "FEES" to listOf("россети"),
        "HYDR" to listOf("русгидро"),
        "TRNFP" to listOf("транснефть"),
        "T" to listOf("т-банк", "тинькофф", "т-технологии"),
        "OZON" to listOf("ozon", "озон"),
        "VKCO" to listOf("вк ", "vk "),
        "POSI" to listOf("позитив"),
        "SMLT" to listOf("самолет"),
        "PIKK" to listOf("пик"),
        "RTKM" to listOf("ростелеком"),
        "SIBN" to listOf("газпром нефть"),
        "BANE" to listOf("башнефть"),
        "UPRO" to listOf("юнипро"),
        "SELG" to listOf("селигдар"),
        "SGZH" to listOf("сегежа"),
        "MVID" to listOf("м.видео", "м видео"),
        "FIVE" to listOf("x5", "пятерочка"),
        "AGRO" to listOf("русагро"),
        "ENPG" to listOf("эн+"),
        "LSRG" to listOf("лср"),
        "AFKS" to listOf("система"),
    )

    /**
     * Словарь финансовой тональности. Веса подобраны по силе сигнала:
     * «дефолт» весит больше, чем «снижение», потому что последствия разные.
     */
    private val LEXICON: Map<String, Double> = linkedMapOf(
        // позитив
        "рекорд" to 1.5,
        "прибыл" to 1.2,
        "дивиденд" to 1.2,
        "выкуп" to 1.2,
        "байбэк" to 1.2,
        "рост" to 1.0,
        "выросл" to 1.0,
        "вырос" to 1.0,
        "росл" to 0.8,
        "подорожа" to 1.0,
        "укрепил" to 0.8,
        "увеличил" to 0.8,
        "нарастил" to 0.8,
        "превысил" to 0.8,
        "контракт" to 0.8,
        "соглашени" to 0.6,
        "запустил" to 0.6,
        "расшир" to 0.6,
        "рекомендова" to 0.6,
        "одобрил" to 0.6,
        "сделк" to 0.4,
        "спрос" to 0.4,
        "успешн" to 0.8,
        "лидер" to 0.6,
        // негатив
        "дефолт" to -2.0,
        "банкрот" to -2.0,
        "убыт" to -1.5,
        "санкци" to -1.5,
        "допэмисси" to -1.5,
        "штраф" to -1.2,
        "судебн" to -0.8,
        "расследован" to -1.0,
        "обыск" to -1.2,
        "авари" to -1.2,
        "пожар" to -1.0,
        "снизил" to -1.0,
        "снижен" to -1.0,
        "паден" to -1.0,
        "упал" to -1.0,
        "подешевел" to -1.0,
        "сократил" to -0.8,
        "потер" to -1.0,
        "задолженност" to -0.8,
        "долгов" to -0.6,
        "отказал" to -0.8,
        "приостанов" to -0.8,
        "заморозил" to -1.0,
        "отменил" to -1.0,
        "понизил" to -1.0,
        "рисков" to -0.6,
        "проблем" to -0.8,
        "кризис" to -1.0,
        "обвал" to -1.5,
        "распрода" to -1.0,
    )
}
