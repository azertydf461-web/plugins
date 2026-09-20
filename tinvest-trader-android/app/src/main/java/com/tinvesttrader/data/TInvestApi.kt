package com.tinvesttrader.data

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * REST-шлюз T-Invest API. Базовый URL переключается между боевым и
 * sandbox-контуром в [TInvestRepository] — токены для них разные и не
 * взаимозаменяемы.
 *
 * Прод:    https://invest-public-api.tbank.ru/rest
 * Sandbox: https://sandbox-invest-public-api.tbank.ru/rest
 */
interface TInvestApi {

    @POST("tinkoff.public.invest.api.contract.v1.UsersService/GetAccounts")
    suspend fun getAccounts(@Header("Authorization") auth: String): GetAccountsResponse

    @POST("tinkoff.public.invest.api.contract.v1.MarketDataService/GetCandles")
    suspend fun getCandles(
        @Header("Authorization") auth: String,
        @Body request: GetCandlesRequest,
    ): GetCandlesResponse

    @POST("tinkoff.public.invest.api.contract.v1.OrdersService/PostOrder")
    suspend fun postOrder(
        @Header("Authorization") auth: String,
        @Body request: PostOrderRequest,
    ): PostOrderResponse

    /** Каталог одного вида активов; путь подставляется из [InstrumentCategory]. */
    @POST
    suspend fun getInstruments(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Body request: InstrumentsRequest,
    ): InstrumentsResponse

    @POST("tinkoff.public.invest.api.contract.v1.OperationsService/GetPortfolio")
    suspend fun getPortfolio(
        @Header("Authorization") auth: String,
        @Body request: GetPortfolioRequest,
    ): PortfolioResponse

    // Sandbox-специфичные методы держим в этом же интерфейсе — их вызывают
    // только когда TInvestRepository настроен на sandbox base URL.
    @POST("tinkoff.public.invest.api.contract.v1.SandboxService/OpenSandboxAccount")
    suspend fun openSandboxAccount(@Header("Authorization") auth: String): OpenSandboxAccountResponse

    @POST("tinkoff.public.invest.api.contract.v1.SandboxService/SandboxPayIn")
    suspend fun sandboxPayIn(
        @Header("Authorization") auth: String,
        @Body request: SandboxPayInRequest,
    ): SandboxPayInResponse

    /**
     * Стоп-заявка исполняется на стороне брокера, поэтому защищает позицию
     * и тогда, когда приложение выгружено из памяти.
     */
    @POST("tinkoff.public.invest.api.contract.v1.StopOrdersService/PostStopOrder")
    suspend fun postStopOrder(
        @Header("Authorization") auth: String,
        @Body request: PostStopOrderRequest,
    ): PostStopOrderResponse

    @POST("tinkoff.public.invest.api.contract.v1.StopOrdersService/GetStopOrders")
    suspend fun getStopOrders(
        @Header("Authorization") auth: String,
        @Body request: GetStopOrdersRequest,
    ): GetStopOrdersResponse

    @POST("tinkoff.public.invest.api.contract.v1.StopOrdersService/CancelStopOrder")
    suspend fun cancelStopOrder(
        @Header("Authorization") auth: String,
        @Body request: CancelStopOrderRequest,
    ): CancelStopOrderResponse
}

@kotlinx.serialization.Serializable
data class GetCandlesRequest(
    val instrumentId: String,
    val from: String,
    val to: String,
    val interval: String, // CANDLE_INTERVAL_1_MIN, _5_MIN, _15_MIN, _HOUR, _DAY ...
)

@kotlinx.serialization.Serializable
data class GetPortfolioRequest(val accountId: String)

@kotlinx.serialization.Serializable
data class OpenSandboxAccountResponse(val accountId: String)

@kotlinx.serialization.Serializable
data class SandboxPayInRequest(val accountId: String, val amount: MoneyValue)

@kotlinx.serialization.Serializable
data class SandboxPayInResponse(val balance: MoneyValue)
