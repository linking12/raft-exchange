package com.binance.raftexchange.server.exchange;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.binary.BatchAddCurrenciesCommand;
import exchange.core2.core.common.api.binary.BinaryDataCommand;
import exchange.core2.core.common.api.reports.StateHashReportQuery;
import exchange.core2.core.common.api.reports.StateHashReportResult;
import exchange.core2.core.common.cmd.OrderCommand;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeCallsTest {

    @Test
    void callExchange_apiCommand_routesToSubmitCommandAsyncFullResponse() throws Exception {
        ExchangeApi api = mock(ExchangeApi.class);
        ExchangeCalls calls = new ExchangeCalls(api);
        OrderCommand oc = mock(OrderCommand.class);
        ApiAddUser cmd = ApiAddUser.builder().uid(42L).build();
        when(api.submitCommandAsyncFullResponse(cmd)).thenReturn(CompletableFuture.completedFuture(oc));

        Supplier<byte[]> result = calls.callExchange(cmd).get();

        assertNotNull(result);
        verify(api).submitCommandAsyncFullResponse(cmd);
    }

    @Test
    void callExchange_apiPersistState_routesToSubmitPersistCommandAsync() throws Exception {
        ExchangeApi api = mock(ExchangeApi.class);
        ExchangeCalls calls = new ExchangeCalls(api);
        ApiPersistState cmd = ApiPersistState.builder().dumpId(123L).build();
        when(api.submitPersistCommandAsync(cmd))
            .thenReturn(CompletableFuture.completedFuture(exchange.core2.core.common.cmd.CommandResultCode.SUCCESS));

        Supplier<byte[]> result = calls.callExchange(cmd).get();

        assertNotNull(result);
        verify(api).submitPersistCommandAsync(cmd);
    }

    @Test
    void callExchange_binaryData_routesToSubmitBinaryDataAsync() throws Exception {
        ExchangeApi api = mock(ExchangeApi.class);
        ExchangeCalls calls = new ExchangeCalls(api);
        BinaryDataCommand cmd = new BatchAddCurrenciesCommand(Collections.emptyList());
        when(api.submitBinaryDataAsync(cmd))
            .thenReturn(CompletableFuture.completedFuture(exchange.core2.core.common.cmd.CommandResultCode.SUCCESS));

        Supplier<byte[]> result = calls.callExchange(cmd).get();

        assertNotNull(result);
        verify(api).submitBinaryDataAsync(cmd);
    }

    @Test
    void callExchange_reportQuery_routesToProcessReport() throws Exception {
        ExchangeApi api = mock(ExchangeApi.class);
        ExchangeCalls calls = new ExchangeCalls(api);
        StateHashReportResult result = mock(StateHashReportResult.class);
        StateHashReportQuery q = new StateHashReportQuery();
        when(api.processReport(any(StateHashReportQuery.class), eq(7)))
            .thenReturn(CompletableFuture.completedFuture(result));

        StateHashReportResult ret = calls.callExchange(q, 7).get();

        assertSame(result, ret);
        verify(api).processReport(q, 7);
    }

    @Test
    void callExchangeAsync_returnsOrderCommandDirectly_noWrapping() throws Exception {
        ExchangeApi api = mock(ExchangeApi.class);
        ExchangeCalls calls = new ExchangeCalls(api);
        OrderCommand oc = mock(OrderCommand.class);
        ApiAddUser cmd = ApiAddUser.builder().uid(1L).build();
        when(api.submitCommandAsyncFullResponse(cmd)).thenReturn(CompletableFuture.completedFuture(oc));

        OrderCommand ret = calls.callExchangeAsync(cmd).get();

        assertSame(oc, ret);
    }
}
