package com.binance.raftexchange.server.exchange.events;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.PartitionInfo;

import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.server.util.ProtoBuilderPool;
import com.binance.raftexchange.stubs.BalanceSnapshot;
import com.binance.raftexchange.stubs.FundEventReportPB;
import com.binance.raftexchange.stubs.LoanSnapshot;
import com.binance.raftexchange.stubs.FuturesExecutionReportPB;
import com.binance.raftexchange.stubs.OrderBookPB;
import com.binance.raftexchange.stubs.OrderBookRecordPB;
import com.binance.raftexchange.stubs.PositionSnapshot;
import com.binance.raftexchange.stubs.SpotExecutionReportPB;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.SymbolType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IEventsHandlerByKafka implements ITradeEventsHandler, IFundEventsHandler {
    private static final Logger LOG = LoggerFactory.getLogger(IEventsHandlerByKafka.class);
    private static final long IGNORE_UID = -1L;
    private static final ProtoBuilderPool builderPool;

    public enum TopicGroup {
        SPOT, PERP, DELIVERY, FUND, OTHER
    }

    static {
        builderPool = new ProtoBuilderPool();
        builderPool.register(SpotExecutionReportPB.Builder.class, SpotExecutionReportPB::newBuilder);
        builderPool.register(FuturesExecutionReportPB.Builder.class, FuturesExecutionReportPB::newBuilder);
        builderPool.register(OrderBookPB.Builder.class, OrderBookPB::newBuilder);
        builderPool.register(FundEventReportPB.Builder.class, FundEventReportPB::newBuilder);
        builderPool.register(BalanceSnapshot.Builder.class, BalanceSnapshot::newBuilder);
        builderPool.register(PositionSnapshot.Builder.class, PositionSnapshot::newBuilder);
    }

    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private final KafkaEventSink sink;

    public IEventsHandlerByKafka(KafkaEventSink sink) {
        this.sink = sink;
    }

    /** 由 RaftClusterContainer 注册到 ESM 的角色监听器调用，控制 kafka 投递只在 leader 上执行。 */
    public void onRoleChange(RaftNode.NodeType nodeType) {
        isLeader.set(nodeType == RaftNode.NodeType.LEADER);
    }

    void send(TopicGroup group, long key, byte[] payload) {
        sink.enqueue(group, key, payload);
    }

    @Override
    public void spotExecutionReport(SpotExecutionReport executionReport) {
        if (!isLeader.get()) {
            return;
        }
        SpotExecutionReportPB pbObject = builderPool.get(SpotExecutionReportPB.Builder.class)
            .setExecutionId(executionReport.getExecutionId())
            .setExecutionTypeValue(executionReport.getExecutionType().ordinal())
            .setOrderStatusValue(executionReport.getOrderStatus().ordinal()).setSymbol(executionReport.getSymbol())
            .setBaseScaleK(executionReport.getBaseScaleK()).setQuoteScaleK(executionReport.getQuoteScaleK())
            .setAccountId(executionReport.getAccountId()).setClOrdId(executionReport.getClOrdId())
            .setOrderId(executionReport.getOrderId())
            .setOrderTypeValue(executionReport.getOrderType() == null ? 0 : executionReport.getOrderType().getCode())
            .setSideValue(executionReport.getSide() == null ? 0 : executionReport.getSide().getCode())
            .setQty(executionReport.getQty()).setPrice(executionReport.getPrice())
            .setQuoteOrderQty(executionReport.getQuoteOrderQty())
            .setOrderCreationTime(executionReport.getOrderCreationTime()).setTradeId(executionReport.getTradeId())
            .setLastQty(executionReport.getLastQty()).setLastPrice(executionReport.getLastPrice())
            .setLastQuoteQty(executionReport.getLastQuoteQty()).setCumulativeQty(executionReport.getCumulativeQty())
            .setCumulativeQuoteQty(executionReport.getCumulativeQuoteQty())
            .setCommission(executionReport.getCommission()).setCommissionAsset(executionReport.getCommissionAsset())
            .setIsMaker(executionReport.isMaker()).setWorkingIndicator(executionReport.isWorkingIndicator()).build();
        if (LOG.isDebugEnabled()) {
            String formateString = pbObject.toString();
            LOG.debug("SpotExecutionReportPB: {}", formateString);
        }
        byte[] pbData = pbObject.toByteArray();
        send(TopicGroup.SPOT, executionReport.getAccountId(), pbData);
    }

    @Override
    public void futuresExecutionReport(FuturesExecutionReport executionReport) {
        if (!isLeader.get()) {
            return;
        }
        FuturesExecutionReportPB pbObject = builderPool.get(FuturesExecutionReportPB.Builder.class)
            .setUniId(executionReport.getUniId()).setExecutionTypeValue(executionReport.getExecutionType().ordinal())
            .setOrderStatusValue(executionReport.getOrderStatus().ordinal()).setSymbol(executionReport.getSymbolId())
            .setOrderQtyScale(executionReport.getOrderQtyScale()).setPriceScale(executionReport.getPriceScale())
            .setUserId(executionReport.getUserId()).setClOrdId(executionReport.getClOrderId())
            .setOrderId(executionReport.getOrderId())
            .setOrderTypeValue(executionReport.getOrderType() == null ? 0 : executionReport.getOrderType().getCode())
            .setSideValue(executionReport.getSide() == null ? 0 : executionReport.getSide().getCode())
            .setCounterpartyId(executionReport.getCounterpartyId()).setPrice(executionReport.getPrice())
            .setOrderQty(executionReport.getOrderQty()).setCreateTime(executionReport.getCreateTime())
            .setExecId(executionReport.getExecId()).setContractTypeValue(executionReport.getContractType().getCode())
            .setPositionSideValue(executionReport.getPositionSide().getCode()).setLastQty(executionReport.getLastQty())
            .setLastPx(executionReport.getLastPx()).setCumQty(executionReport.getCumQty())
            .setCumQuoteQty(executionReport.getCumQuoteQty()).setAvgPx(executionReport.getAvgPx())
            .setFee(executionReport.getFee()).setFeeAssetId(executionReport.getFeeAssetId())
            .setIsMaker(executionReport.isMaker()).build();
        if (LOG.isDebugEnabled()) {
            String formateString = pbObject.toString();
            LOG.debug("FuturesExecutionReportPB: {}", formateString);
        }
        byte[] pbData = pbObject.toByteArray();
        if (executionReport.getContractType() == SymbolType.FUTURES_CONTRACT_PERPETUAL) {
            send(TopicGroup.PERP, executionReport.getUserId(), pbData);
        } else if (executionReport.getContractType() == SymbolType.FUTURES_CONTRACT_DELIVERY) {
            send(TopicGroup.DELIVERY, executionReport.getUserId(), pbData);
        }
    }

    @Override
    public void orderBook(OrderBook orderBook) {
        if (!isLeader.get()) {
            return;
        }
        OrderBookPB.Builder builder = builderPool.get(OrderBookPB.Builder.class).setSymbol(orderBook.getSymbol())
            .setTimestamp(orderBook.getTimestamp()).setBaseScaleK(orderBook.getBaseScaleK())
            .setQuoteScaleK(orderBook.getQuoteScaleK());
        if (orderBook.getAsks() != null) {
            for (OrderBookRecord ask : orderBook.getAsks()) {
                builder.addAsks(OrderBookRecordPB.newBuilder().setPrice(ask.getPrice()).setVolume(ask.getVolume())
                    .setOrders(ask.getOrders()).build());
            }
        }
        if (orderBook.getBids() != null) {
            for (OrderBookRecord bid : orderBook.getBids()) {
                builder.addBids(OrderBookRecordPB.newBuilder().setPrice(bid.getPrice()).setVolume(bid.getVolume())
                    .setOrders(bid.getOrders()).build());
            }
        }
        OrderBookPB pbObject = builder.build();
        if (LOG.isDebugEnabled()) {
            String formateString = pbObject.toString();
            LOG.debug("OrderBookPB: {}", formateString);
        }
        byte[] pbData = pbObject.toByteArray();
        send(TopicGroup.OTHER, IGNORE_UID, pbData);
    }

    @Override
    public void fundEventReport(FundEventReport fundEventReport) {
        if (!isLeader.get()) {
            return;
        }
        FundEventReport.LoanSnapshot loan = fundEventReport.getLoan();
        FundEventReportPB.Builder builder = builderPool.get(FundEventReportPB.Builder.class)
            .setAccountId(fundEventReport.getAccountId()).setEventTypeValue(fundEventReport.getEventType().getCode())
            .setLoan(LoanSnapshot.newBuilder()
                .setMode(loan.getMode())
                // 借贷侧
                .setDebtPrincipal(loan.getDebtPrincipal())
                .setDebtInterest(loan.getDebtInterest())
                .setInterestPaidTotal(loan.getInterestPaidTotal())
                .setLtvBps(loan.getLtvBps())
                .setThresholdBps(loan.getThresholdBps())
                // 抵押侧
                .setCollateralCurrency(loan.getCollateralCurrency())
                .setCollateralCurrencyScaleK(loan.getCollateralCurrencyScaleK())
                .setCollateralPledged(loan.getCollateralPledged())
                .setCollateralFree(loan.getCollateralFree())
                .setCollateralLocked(loan.getCollateralLocked()));
        FundEventReport.BalanceSnapshot balance = fundEventReport.getBalances();
        builder.setBalances(builderPool.get(BalanceSnapshot.Builder.class).setCurrency(balance.getCurrency())
            .setCurrencyScaleK(balance.getCurrencyScaleK()).setFree(balance.getFree()).setLocked(balance.getLocked()));
        FundEventReport.PositionSnapshot position = fundEventReport.getPositions();
        if (fundEventReport.getEventType().getCode() >= FundEvent.FundEventType.LOCK_PENDING.getCode()) {
            builder.setPositions(builderPool.get(PositionSnapshot.Builder.class).setSymbolId(position.getSymbolId())
                .setBaseScaleK(position.getBaseScaleK()).setQuoteScaleK(position.getQuoteScaleK())
                .setDirectionValue(position.getDirection().getMultiplier() & 0xFF).setQuantity(position.getQuantity())
                .setOpenPriceSum(position.getOpenPriceSum()).setCumRealized(position.getCumRealized())
                .setIsolated(position.isIsolated()).setIsolatedWallet(position.getIsolatedWallet())
                .setLeverage(position.getLeverage()).setOpenInitMarginSum(position.getOpenInitMarginSum())
                .setMarkPrice(position.getMarkPrice()).setUnrealizedProfit(position.getUnrealizedProfit())
                .setLiquidationPrice(position.getLiquidationPrice())
                .setMarginRatioScaleK(position.getMarginRatioScaleK())
                .setMaintenanceMarginScaleK(position.getMaintenanceMarginScaleK())
                .setBidsNotional(position.getBidsNotional())
                .setAsksNotional(position.getAsksNotional()).setBidsQty(position.getBidsQty())
                .setAsksQty(position.getAsksQty()));
        }
        FundEventReportPB pbObject = builder.build();
        if (LOG.isDebugEnabled()) {
            String formateString = pbObject.toString();
            LOG.debug("PositionOutReportPB: {}", formateString);
        }
        byte[] pbData = pbObject.toByteArray();
        TopicGroup target = (fundEventReport.getEventType() == FundEvent.FundEventType.MARGIN_ALERT
            || fundEventReport.getEventType() == FundEvent.FundEventType.LIQUIDATION_ALERT) ? TopicGroup.OTHER
                : TopicGroup.FUND;
        send(target, fundEventReport.getAccountId(), pbData);
    }

    public static class CommandPartitioner implements Partitioner {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes,
            Cluster cluster) {
            List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
            int numPartitions = partitions.size();
            Long uid = (Long)key;
            if (uid == IGNORE_UID) {
                return Math.floorMod(counter.getAndIncrement(), numPartitions);
            }
            return Math.floorMod(uid.intValue(), numPartitions);
        }

        @Override
        public void close() {}

        @Override
        public void configure(Map<String, ?> configs) {}
    }

}
