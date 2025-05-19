package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.report.SingleUserReportQuery;
import exchange.core2.core.common.api.reports.SingleUserReportResult;

import java.util.concurrent.CompletableFuture;

public class SyncTradeAccountApiController extends AbstractApiController {

    public static CompletableFuture<SingleUserReportResult> getUserState(SingleUserReportQuery grpcSingleUserReportQuery, int transferId) {
        exchange.core2.core.common.api.reports.SingleUserReportQuery singleUserReportQuery =
            new exchange.core2.core.common.api.reports.SingleUserReportQuery(grpcSingleUserReportQuery.getUserId());
        LOG.debug("singleUserReportQuery applied, msg: {}", singleUserReportQuery);
        return callExchange(singleUserReportQuery, transferId);
    }
}
