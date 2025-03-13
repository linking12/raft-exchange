package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.report.SingleUserReportQuery;

public class SyncTradeAccountApiController extends AbstractApiController {

    public static byte[] getUserState(SingleUserReportQuery grpcSingleUserReportQuery, int transferId) throws Exception {
        exchange.core2.core.common.api.reports.SingleUserReportQuery singleUserReportQuery =
            new exchange.core2.core.common.api.reports.SingleUserReportQuery(grpcSingleUserReportQuery.getUserId());
        LOG.info("singleUserReportQuery applied, msg: {}", singleUserReportQuery);

        return callExchange(singleUserReportQuery, transferId);
    }
}
