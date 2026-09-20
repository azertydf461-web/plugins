package com.tinvestanalyst.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.concurrent.TimeUnit

enum class CheckStatus { OK, FAIL, WARN }

data class DiagnosticStep(
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val hint: String? = null,
)

private const val API_HOST = "invest-public-api.tbank.ru"
private const val ACCOUNTS_URL =
    "https://$API_HOST/rest/tinkoff.public.invest.api.contract.v1.UsersService/GetAccounts"

// Без этого заголовка шлюз брокера отвечает 415 ещё до проверки токена,
// и проверка выглядит как «токен не принят».
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Пошаговая проверка связи: DNS -> TLS-соединение -> ответ на запрос с
 * токеном. Без неё «приложение не подключается» невозможно отличить от
 * «телефон не пускает приложение в сеть», «VPN режет доступ» и «токен не
 * тот» — а лечатся эти три случая совершенно по-разному.
 */
class NetworkDiagnostics(private val settings: AnalystSettingsStore) {

    suspend fun run(): List<DiagnosticStep> = withContext(Dispatchers.IO) {
        val steps = mutableListOf<DiagnosticStep>()

        val dnsStep = checkDns()
        steps += dnsStep
        if (dnsStep.status == CheckStatus.FAIL) {
            steps += DiagnosticStep(
                title = "Соединение с сервером брокера",
                status = CheckStatus.FAIL,
                detail = "Не проверялось — сначала нужен доступ в интернет.",
            )
            return@withContext steps
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        val reachability = checkReachability(client)
        steps += reachability
        if (reachability.status == CheckStatus.FAIL) return@withContext steps

        steps += checkToken(client)
        steps
    }

    private fun checkDns(): DiagnosticStep = runCatching {
        val addresses = InetAddress.getAllByName(API_HOST)
        DiagnosticStep(
            title = "Интернет на устройстве",
            status = CheckStatus.OK,
            detail = "Адрес $API_HOST определён (${addresses.size} IP).",
        )
    }.getOrElse { error ->
        DiagnosticStep(
            title = "Интернет на устройстве",
            status = CheckStatus.FAIL,
            detail = "Не удалось определить адрес $API_HOST: ${error.message}",
            hint = "Телефон не пускает приложение в сеть. На Xiaomi/Redmi: Настройки → " +
                "Приложения → Управление приложениями → T-Invest Analyst → Ограничение трафика " +
                "(или «Сетевые подключения») — включите и Wi-Fi, и мобильные данные. " +
                "Проверьте также, что интернет работает в браузере.",
        )
    }

    private fun checkReachability(client: OkHttpClient): DiagnosticStep {
        val request = Request.Builder()
            .url(ACCOUNTS_URL)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                // Сервер ответил хоть чем-то — значит сеть и TLS в порядке;
                // 401 здесь ожидаем, запрос намеренно без токена.
                DiagnosticStep(
                    title = "Соединение с сервером брокера",
                    status = CheckStatus.OK,
                    detail = "Сервер ответил, код ${response.code} (без токена это нормально).",
                )
            }
        }.getOrElse { error ->
            DiagnosticStep(
                title = "Соединение с сервером брокера",
                status = CheckStatus.FAIL,
                detail = "Соединение не установилось: ${error.message}",
                hint = hintForConnectionError(error),
            )
        }
    }

    /**
     * Т-Банк выпускает сертификаты в российском УЦ Минцифры, которого нет в
     * хранилище Android. Это выглядит как «нет связи», хотя сеть в порядке, —
     * и лечится установкой корневого сертификата, а не отключением VPN.
     */
    private fun hintForConnectionError(error: Throwable): String {
        val text = generateSequence(error) { it.cause }
            .mapNotNull { "${it::class.java.simpleName} ${it.message}" }
            .joinToString(" | ")

        val certificateProblem = text.contains("Trust anchor", ignoreCase = true) ||
            text.contains("CertPathValidator", ignoreCase = true) ||
            text.contains("CertificateException", ignoreCase = true) ||
            text.contains("SSLHandshake", ignoreCase = true)

        return if (certificateProblem) {
            "Сеть работает, но телефон не доверяет сертификату сервера. Т-Банк использует " +
                "сертификат российского УЦ Минцифры, которого нет в Android по умолчанию.\n\n" +
                "Что сделать: скачайте корневой сертификат с gosuslugi.ru/crt, затем " +
                "Настройки телефона → Пароли и безопасность → Шифрование и учётные данные → " +
                "Установить сертификат → Сертификат CA → выберите скачанный файл. " +
                "Android предупредит, что сеть может отслеживаться — это обычное предупреждение " +
                "при установке любого стороннего сертификата.\n\n" +
                "Приложение доверяет таким сертификатам только для доменов Т-Банка, " +
                "для остального интернета проверка остаётся обычной."
        } else {
            "Интернет есть, но до сервера брокера не достучаться. Частая причина — VPN: " +
                "T-Invest не всегда пускает запросы с иностранных IP. Отключите VPN и повторите. " +
                "Если VPN выключен — проверьте антивирус и настройки сети."
        }
    }

    private fun checkToken(client: OkHttpClient): DiagnosticStep {
        val token = settings.apiToken?.takeIf { it.isNotBlank() }
            ?: return DiagnosticStep(
                title = "Токен",
                status = CheckStatus.WARN,
                detail = "Токен не сохранён.",
                hint = "Вставьте токен T-Инвестиций и нажмите «Сохранить».",
            )

        val request = Request.Builder()
            .url(ACCOUNTS_URL)
            .addHeader("Authorization", "Bearer $token")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> DiagnosticStep(
                        title = "Токен",
                        status = CheckStatus.OK,
                        detail = "Брокер принял токен — всё готово к работе.",
                    )
                    401, 403 -> DiagnosticStep(
                        title = "Токен",
                        status = CheckStatus.FAIL,
                        detail = "Брокер отклонил токен (код ${response.code}).",
                        hint = "Токен недействителен, отозван или выпущен для другого контура. " +
                            "Это приложение работает с боевым контуром, поэтому sandbox-токен " +
                            "здесь не подойдёт — нужен обычный токен, достаточно «только чтение».",
                    )
                    429 -> DiagnosticStep(
                        title = "Токен",
                        status = CheckStatus.WARN,
                        detail = "Слишком частые запросы (429).",
                        hint = "Подождите минуту и повторите проверку.",
                    )
                    else -> DiagnosticStep(
                        title = "Токен",
                        status = CheckStatus.WARN,
                        detail = "Неожиданный ответ сервера: код ${response.code}.",
                    )
                }
            }
        }.getOrElse { error ->
            DiagnosticStep(
                title = "Токен",
                status = CheckStatus.FAIL,
                detail = "Запрос не прошёл: ${error.message}",
            )
        }
    }
}
