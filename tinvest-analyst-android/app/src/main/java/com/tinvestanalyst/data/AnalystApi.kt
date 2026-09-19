package com.tinvestanalyst.data

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Только методы чтения рыночных данных. Методы выставления заявок сюда не
 * добавляются намеренно: приложение анализирует и советует, но физически не
 * может совершить сделку, даже если в него вставят токен с полным доступом.
 */
interface AnalystApi {

    @POST("tinkoff.public.invest.api.contract.v1.InstrumentsService/FindInstrument")
    suspend fun findInstrument(
        @Header("Authorization") auth: String,
        @Body request: FindInstrumentRequest,
    ): FindInstrumentResponse

    /**
     * Каталог инструментов одного типа. Путь подставляется из
     * [InstrumentCategory], чтобы пять почти одинаковых методов не плодить.
     */
    @POST
    suspend fun getInstruments(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Body request: InstrumentsRequest,
    ): InstrumentsResponse

    @POST("tinkoff.public.invest.api.contract.v1.MarketDataService/GetCandles")
    suspend fun getCandles(
        @Header("Authorization") auth: String,
        @Body request: GetCandlesRequest,
    ): GetCandlesResponse

    @POST("tinkoff.public.invest.api.contract.v1.MarketDataService/GetLastPrices")
    suspend fun getLastPrices(
        @Header("Authorization") auth: String,
        @Body request: GetLastPricesRequest,
    ): GetLastPricesResponse

    @POST("tinkoff.public.invest.api.contract.v1.MarketDataService/GetOrderBook")
    suspend fun getOrderBook(
        @Header("Authorization") auth: String,
        @Body request: GetOrderBookRequest,
    ): GetOrderBookResponse

    @POST("tinkoff.public.invest.api.contract.v1.MarketDataService/GetTradingStatus")
    suspend fun getTradingStatus(
        @Header("Authorization") auth: String,
        @Body request: GetTradingStatusRequest,
    ): GetTradingStatusResponse
}
