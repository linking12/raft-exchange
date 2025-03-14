package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.report.StateHashReportQuery;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportQuery;

public class SyncTradeMiscApiController extends AbstractApiController {

    /**
     * 对各个risk引擎中的各个数据进行哈希计算，用于风控或校验
     */
    public static byte[] getStateHash(StateHashReportQuery grpcStateHashReportQuery, int transferId) throws Exception {
        exchange.core2.core.common.api.reports.StateHashReportQuery stateHashReportQuery =
            new exchange.core2.core.common.api.reports.StateHashReportQuery();
        LOG.info("stateHashReportQuery applied");

        return callExchange(stateHashReportQuery, transferId);
    }

    /**
     * 统计整个撮合系统的资金总额分布，用于风控或对账
     */
    public static byte[] getTotalCurrencyBalance(TotalCurrencyBalanceReportQuery grpcTotalCurrencyBalanceReportQuery, int transferId) throws Exception {
        exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportQuery totalCurrencyBalanceReportQuery =
            new exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportQuery();
        LOG.info("totalCurrencyBalanceReportQuery applied");

        return callExchange(totalCurrencyBalanceReportQuery, transferId);
    }
}
