package com.tinvesttrader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

private const val LIVE_HOST = "invest-public-api.tbank.ru"
private const val SANDBOX_HOST = "sandbox-invest-public-api.tbank.ru"
private const val ACCOUNTS_PATH =
    "rest/tinkoff.public.invest.api.contract.v1.UsersService/GetAccounts"

/**
 * Пошаговая проверка связи: DNS -> TLS-соединение -> ответ на запрос с
 * токеном. Без неё «приложение не подключается» невозможно отличить от
 * «телефон не пускает приложение в сеть», «VPN режет доступ» и «токен не от
 * того контура» — а лечатся эти случаи по-разному.
 */
class NetworkDiagnostics(private val tokenStore: SecureTokenStore) {

    suspend fun run(): List<DiagnosticStep> = withContext(Dispatchers.IO) {
        val live = tokenStore.liveTradingEnabled
        val host = if (live) LIVE_HOST else SANDBOX_HOST
        val url = "https://$host/$ACCOUNTS_PATH"
        val token = if (live) tokenStore.liveToken else tokenStore.sandboxToken

        val steps = mutableListOf(
            DiagnosticStep(
                title = "Режим",
                status = CheckStatus.OK,
                detail = if (live) "LIVE — реальный счёт, сервер $host" else "SANDBOX — песочница, сервер $host",
            ),
        )

        val dns = checkDns(host)
        steps += dns
        if (dns.status == CheckStatus.FAIL) return@withContext steps

        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        val reachability = checkReachability(client, url)
        steps += reachability
        if (reachability.status == CheckStatus.FAIL) return@withContext steps

        steps += checkToken(client, url, token, live)
        steps
    }

    private fun checkDns(host: String): DiagnosticStep = runCatching {
        val addresses = InetAddress.getAllByName(host)
        DiagnosticStep(
            title = "Интернет на устройстве",
            status = CheckStatus.OK,
            detail = "Адрес $host определён (${addresses.size} IP).",
        )
    }.getOrElse { error ->
        DiagnosticStep(
            title = "Интернет на устройстве",
            status = CheckStatus.FAIL,
            detail = "Не удалось определить адрес $host: ${error.message}",
            hint = "Телефон не пускает приложение в сеть. На Xiaomi/Redmi: Настройки → " +
                "Приложения → Управление приложениями → T-Invest Trader → Ограничение трафика " +
                "(или «Сетевые подключения») — включите и Wi-Fi, и мобильные данные.",
        )
    }

    private fun checkReachability(client: OkHttpClient, url: String): DiagnosticStep {
        val request = Request.Builder().url(url).post("{}".toRequestBody()).build()
        return runCatching {
            client.newCall(request).execute().use { response ->
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
                hint = "Интернет есть, но до сервера брокера не достучаться. Чаще всего виноват VPN: " +
                    "T-Invest не всегда пускает запросы с иностранных IP. Отключите VPN и повторите.",
            )
        }
    }

    private fun checkToken(
        client: OkHttpClient,
        url: String,
        token: String?,
        live: Boolean,
    ): DiagnosticStep {
        val value = token?.takeIf { it.isNotBlank() }
            ?: return DiagnosticStep(
                title = "Токен",
                status = CheckStatus.WARN,
                detail = if (live) "Live-токен не сохранён." else "Sandbox-токен не сохранён.",
                hint = "Вставьте токен нужного контура и нажмите «Сохранить».",
            )

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $value")
            .post("{}".toRequestBody())
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> DiagnosticStep(
                        title = "Токен",
                        status = CheckStatus.OK,
                        detail = "Брокер принял токен.",
                    )
                    401, 403 -> DiagnosticStep(
                        title = "Токен",
                        status = CheckStatus.FAIL,
                        detail = "Брокер отклонил токен (код ${response.code}).",
                        hint = if (live) {
                            "Для LIVE нужен боевой токен, sandbox-токен здесь не работает."
                        } else {
                            "Для песочницы нужен sandbox-токен: боевой токен на этом сервере не принимается."
                        },
                    )
                    429 -> DiagnosticStep(
                        title = "Токен",
                        status = CheckStatus.WARN,
                        detail = "Слишком частые запросы (429).",
                        hint = "Подождите минуту и повторите.",
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
