package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.exchange.ExchangeCalls;
import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.stubs.report.ReportQuery;
import com.binance.raftexchange.stubs.report.ReportResult;
import com.binance.raftexchange.stubs.report.SingleUserReportQuery;
import com.binance.raftexchange.stubs.report.StateHashReportQuery;
import com.binance.raftexchange.stubs.report.SymbolCurrencyReportQuery;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportQuery;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.StateHashReportResult;
import exchange.core2.core.common.api.reports.SymbolCurrencyReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QueryService.query 是按 ReportQuery.TypeCase 分发到 4 个 report 路径的 switch； 验证每个 case 调对 ExchangeApi.processReport 的具体子查询类型
 * + default 走 failedFuture。
 */
class QueryServiceTest {

    private RaftClusterContainer raft;
    private ExchangeApi api;
    private QueryService service;

    @BeforeEach
    void setUp() {
        raft = mock(RaftClusterContainer.class);
        api = mock(ExchangeApi.class);
        when(raft.exchangeCalls()).thenReturn(new ExchangeCalls(api));
        when(raft.readConsistencyBarrier()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        service = new QueryService(raft);
    }

    @Test
    void query_singleUserReport_routesToProcessReport() {
        SingleUserReportResult notFound = SingleUserReportResult.createFromRiskEngineNotFound(42L);
        when(api.processReport(any(exchange.core2.core.common.api.reports.SingleUserReportQuery.class), eq(7)))
            .thenReturn(CompletableFuture.completedFuture(notFound));

        @SuppressWarnings("unchecked")
        StreamObserver<ReportResult> observer = mock(StreamObserver.class);
        service.query(ReportQuery.newBuilder().setTransferId(7)
            .setSingleUserReport(SingleUserReportQuery.newBuilder().setUserId(42L)).build(), observer);

        ArgumentCaptor<ReportResult> captor = ArgumentCaptor.forClass(ReportResult.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        assertNotNull(captor.getValue().getSingleUserReport(), "query 应路由到 SingleUserReport 路径");
    }

    @Test
    void query_stateHash_routesToProcessReport() {
        StateHashReportResult stateHash = mock(StateHashReportResult.class);
        when(stateHash.getHashCodes()).thenReturn(new java.util.TreeMap<>());
        when(api.processReport(any(exchange.core2.core.common.api.reports.StateHashReportQuery.class), eq(3)))
            .thenReturn(CompletableFuture.completedFuture(stateHash));

        @SuppressWarnings("unchecked")
        StreamObserver<ReportResult> observer = mock(StreamObserver.class);
        service.query(ReportQuery.newBuilder().setTransferId(3).setStateHash(StateHashReportQuery.newBuilder()).build(),
            observer);

        verify(observer).onNext(any());
        verify(observer).onCompleted();
    }

    @Test
    void query_totalCurrencyBalance_routesToProcessReport() {
        TotalCurrencyBalanceReportResult result = mock(TotalCurrencyBalanceReportResult.class);
        org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap empty =
            new org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap();
        when(result.getAccountBalances()).thenReturn(empty);
        when(result.getFees()).thenReturn(empty);
        when(result.getAdjustments()).thenReturn(empty);
        when(result.getSuspends()).thenReturn(empty);
        when(result.getOpenInterestLong()).thenReturn(empty);
        when(result.getOpenInterestShort()).thenReturn(empty);
        when(result.getIfBalances()).thenReturn(empty);
        when(result.getIfOpenInterestLong()).thenReturn(empty);
        when(result.getIfOpenInterestShort()).thenReturn(empty);
        when(
            api.processReport(any(exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportQuery.class), eq(0)))
                .thenReturn(CompletableFuture.completedFuture(result));

        @SuppressWarnings("unchecked")
        StreamObserver<ReportResult> observer = mock(StreamObserver.class);
        service.query(
            ReportQuery.newBuilder().setTotalCurrencyBalance(TotalCurrencyBalanceReportQuery.newBuilder()).build(),
            observer);

        verify(observer).onNext(any());
        verify(observer).onCompleted();
    }

    @Test
    void query_symbolCurrencyReport_routesToProcessReport() {
        SymbolCurrencyReportResult result = mock(SymbolCurrencyReportResult.class);
        when(result.getSymbolSpecs())
            .thenReturn(new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<>());
        when(result.getCurrencySpecs())
            .thenReturn(new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<>());
        when(api.processReport(any(exchange.core2.core.common.api.reports.SymbolCurrencyReportQuery.class), eq(0)))
            .thenReturn(CompletableFuture.completedFuture(result));

        @SuppressWarnings("unchecked")
        StreamObserver<ReportResult> observer = mock(StreamObserver.class);
        service.query(ReportQuery.newBuilder().setSymbolCurrencyReport(SymbolCurrencyReportQuery.newBuilder()).build(),
            observer);

        verify(observer).onNext(any());
        verify(observer).onCompleted();
    }

    @Test
    void query_typeNotSet_propagatesIllegalArgumentToObserver() {
        @SuppressWarnings("unchecked")
        StreamObserver<ReportResult> observer = mock(StreamObserver.class);
        service.query(ReportQuery.newBuilder().build(), observer);

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(captor.capture());
        assertTrue(
            captor.getValue() instanceof IllegalArgumentException
                || (captor.getValue().getCause() instanceof IllegalArgumentException),
            "default case 必须把 IllegalArgumentException 传给 observer.onError");
    }

    @Test
    void search_engineFails_propagatesErrorToObserver() {
        RuntimeException boom = new RuntimeException("engine down");
        when(api.processReport(any(exchange.core2.core.common.api.reports.StateHashReportQuery.class), eq(0)))
            .thenReturn(CompletableFuture.failedFuture(boom));

        @SuppressWarnings("unchecked")
        StreamObserver<ReportResult> observer = mock(StreamObserver.class);
        service.query(ReportQuery.newBuilder().setStateHash(StateHashReportQuery.newBuilder()).build(), observer);

        verify(observer).onError(any(Throwable.class));
    }
}
