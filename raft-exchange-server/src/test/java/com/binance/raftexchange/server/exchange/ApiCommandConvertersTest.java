package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.request.ApiCommand;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 LiquidationEngine 触发的强平 cmd 经 raft 复制 round-trip 后字段不丢/不串。
 * <p>
 * leader 端 exchange-core ApiCommand → {@link ApiCommandConverters#liquidationCmdToRaftLog} → proto bytes → follower 端
 * ApiCommand.parseFrom → convertXxx → exchange-core ApiCommand。 任一字段编解码错位都会导致 follower 状态机偏离 leader。
 */
class ApiCommandConvertersTest {

    @Test
    void liquidationOrder_roundTripPreservesAllFields() throws Exception {
        long ts = 1_234_567_890L;
        var leaderCmd = exchange.core2.core.common.api.ApiLiquidationOrder.builder().price(50_000L).size(7L)
            .orderId(123_456_789L).action(OrderAction.ASK).orderType(OrderType.IOC).uid(9801L).symbol(100001).build();

        byte[] raftLog = ApiCommandConverters.liquidationCmdToRaftLog(leaderCmd, ts);
        ApiCommand parsed = ApiCommand.parseFrom(raftLog);

        assertEquals(ts, parsed.getTimestamp(), "timestamp 必须透传");
        assertSame(ApiCommand.CommandCase.LIQUIDATION_ORDER, parsed.getCommandCase(),
            "oneof case 必须指向 LIQUIDATION_ORDER");

        var followerCmd = ApiCommandConverters.convertLiquidationOrder(parsed);
        assertEquals(leaderCmd.price, followerCmd.price);
        assertEquals(leaderCmd.size, followerCmd.size);
        assertEquals(leaderCmd.orderId, followerCmd.orderId);
        assertSame(leaderCmd.action, followerCmd.action);
        assertSame(leaderCmd.orderType, followerCmd.orderType);
        assertEquals(leaderCmd.uid, followerCmd.uid);
        assertEquals(leaderCmd.symbol, followerCmd.symbol);
    }

    @Test
    void ifTakeover_roundTripPreservesAllFields() throws Exception {
        long ts = 1_111_222_333L;
        var leaderCmd = exchange.core2.core.common.api.ApiIFTakeOver.builder().orderId(555_666L).uid(9802L)
            .symbol(100002).action(OrderAction.BID).size(3L).price(45_000L).build();

        byte[] raftLog = ApiCommandConverters.liquidationCmdToRaftLog(leaderCmd, ts);
        ApiCommand parsed = ApiCommand.parseFrom(raftLog);

        assertEquals(ts, parsed.getTimestamp());
        assertSame(ApiCommand.CommandCase.IF_TAKEOVER, parsed.getCommandCase());

        var followerCmd = ApiCommandConverters.convertIFTakeOver(parsed);
        assertEquals(leaderCmd.orderId, followerCmd.orderId);
        assertEquals(leaderCmd.uid, followerCmd.uid);
        assertEquals(leaderCmd.symbol, followerCmd.symbol);
        assertSame(leaderCmd.action, followerCmd.action);
        assertEquals(leaderCmd.size, followerCmd.size);
        assertEquals(leaderCmd.price, followerCmd.price);
    }

    @Test
    void autoDeleveraging_roundTripPreservesAllFields() throws Exception {
        long ts = 2_222_333_444L;
        var leaderCmd = exchange.core2.core.common.api.ApiAutoDeleveraging.builder().orderId(777_888L).uid(9803L)
            .symbol(100003).action(OrderAction.ASK).size(11L).price(40_000L).build();

        byte[] raftLog = ApiCommandConverters.liquidationCmdToRaftLog(leaderCmd, ts);
        ApiCommand parsed = ApiCommand.parseFrom(raftLog);

        assertEquals(ts, parsed.getTimestamp());
        assertSame(ApiCommand.CommandCase.AUTO_DELEVERAGING, parsed.getCommandCase());

        var followerCmd = ApiCommandConverters.convertAutoDeleveraging(parsed);
        assertEquals(leaderCmd.orderId, followerCmd.orderId);
        assertEquals(leaderCmd.uid, followerCmd.uid);
        assertEquals(leaderCmd.symbol, followerCmd.symbol);
        assertSame(leaderCmd.action, followerCmd.action);
        assertEquals(leaderCmd.size, followerCmd.size);
        assertEquals(leaderCmd.price, followerCmd.price);
    }

    @Test
    void toRaftLog_unsupportedCmdType_throws() {
        // ApiSystemLiquidationNotify 不走 raft 复制（只是下游事件），尝试序列化必须 fail-fast
        var notify = exchange.core2.core.common.api.ApiSystemLiquidationNotify.builder().fundEvent(null).build();
        assertThrows(IllegalArgumentException.class, () -> ApiCommandConverters.liquidationCmdToRaftLog(notify, 0L),
            "ApiSystemLiquidationNotify 不在 raft 复制白名单上，必须抛 IllegalArgumentException");
    }

    @Test
    void batchAddLoan_globalOnly_mapsNumeraireAndThresholds() {
        // proto BatchAddLoanCommand{global} → exchange-core BatchAddLoanCommand，global 部分字段不丢
        var grpc = com.binance.raftexchange.stubs.request.BatchAddLoanCommand.newBuilder()
            .setGlobal(com.binance.raftexchange.stubs.request.SpotLoanGlobalConfig.newBuilder()
                .setNumeraireCcy(2).setCrossLiquidationLtvBps(8500).setCrossMarginCallLtvBps(8000)
                .setLoanPoolUtilizationCapBps(9000).setLoanLiquidationFeeBps(200))
            .build();

        var cmd = ApiCommandConverters.convertBatchAddLoan(grpc);

        assertTrue(cmd.hasGlobal());
        assertFalse(cmd.hasSymbol());
        var g = cmd.getGlobal();
        assertEquals(2, g.getNumeraireCurrency());
        assertEquals(8500, g.getCrossLiquidationLtvBps());
        assertEquals(8000, g.getCrossMarginCallLtvBps());
        assertEquals(9000, g.getLoanPoolUtilizationCapBps());
        assertEquals(200, g.getLoanLiquidationFeeBps());
    }

    @Test
    void batchAddLoan_bothParts_mapsGlobalAndSymbol() {
        // 合并后的核心能力：一条 proto 命令同时携带 global + symbol。
        // symbol 只带 symbolId + initialLtv,其余阈值 → UNSET(−1),由 exchange-core resolve() 派生。
        final int UNSET = exchange.core2.core.common.api.binary.BatchAddLoanCommand.SymbolLoanConfig.UNSET;
        var grpc = com.binance.raftexchange.stubs.request.BatchAddLoanCommand.newBuilder()
            .setGlobal(com.binance.raftexchange.stubs.request.SpotLoanGlobalConfig.newBuilder().setNumeraireCcy(2))
            .setSymbol(com.binance.raftexchange.stubs.request.SpotLoanConfig.newBuilder()
                .setSymbolId(101).setLoanInitialLtvBps(6000))
            .build();

        var cmd = ApiCommandConverters.convertBatchAddLoan(grpc);

        assertTrue(cmd.hasGlobal());
        assertTrue(cmd.hasSymbol());
        assertEquals(2, cmd.getGlobal().getNumeraireCurrency());
        var s = cmd.getSymbol();
        assertEquals(101, s.getSymbolId());
        assertEquals(6000, s.getLoanInitialLtvBps());
        assertEquals(UNSET, s.getLoanLiquidationLtvBps(), "override 不进 proto → UNSET → 派生");
        assertEquals(UNSET, s.getLoanMarginCallLtvBps());
        assertEquals((long) UNSET, s.getLoanMaxAmount());
        assertEquals(UNSET, s.getLoanMaxTermDays());
        assertEquals(UNSET, s.getCollateralWeightBps());
    }

    // ---- loan 强平 / reprice 的 raft-log round-trip：scanner 命令 leader→follower 字段不丢，否则状态机分叉 ----

    @Test
    void loanForceLiquidate_roundTripPreservesAllFields() throws Exception {
        long ts = 111L;
        var leaderCmd = exchange.core2.core.common.api.ApiLoanForceLiquidate.builder().uid(9801L).symbol(100)
            .loanId(55L).price(50_000L).size(3L).orderId(123L).action(OrderAction.ASK).orderType(OrderType.IOC).build();

        byte[] raftLog = ApiCommandConverters.liquidationCmdToRaftLog(leaderCmd, ts);
        ApiCommand parsed = ApiCommand.parseFrom(raftLog);
        assertEquals(ts, parsed.getTimestamp());
        assertSame(ApiCommand.CommandCase.LOAN_FORCE_LIQUIDATE, parsed.getCommandCase());

        var f = ApiCommandConverters.convertLoanForceLiquidate(parsed);
        assertEquals(leaderCmd.uid, f.uid);
        assertEquals(leaderCmd.symbol, f.symbol);
        assertEquals(leaderCmd.loanId, f.loanId);
        assertEquals(leaderCmd.price, f.price);
        assertEquals(leaderCmd.size, f.size);
        assertEquals(leaderCmd.orderId, f.orderId);
        assertSame(leaderCmd.action, f.action);
        assertSame(leaderCmd.orderType, f.orderType);
    }

    @Test
    void loanCrossForceLiquidate_roundTripPreservesAllFields() throws Exception {
        long ts = 222L;
        var leaderCmd = exchange.core2.core.common.api.ApiLoanCrossForceLiquidate.builder().uid(9802L).symbol(100)
            .targetLoanId(200L).price(49_500L).size(2L).orderId(456L).action(OrderAction.ASK).orderType(OrderType.IOC)
            .build();

        byte[] raftLog = ApiCommandConverters.liquidationCmdToRaftLog(leaderCmd, ts);
        ApiCommand parsed = ApiCommand.parseFrom(raftLog);
        assertEquals(ts, parsed.getTimestamp());
        assertSame(ApiCommand.CommandCase.LOAN_CROSS_FORCE_LIQUIDATE, parsed.getCommandCase());

        var f = ApiCommandConverters.convertLoanCrossForceLiquidate(parsed);
        assertEquals(leaderCmd.uid, f.uid);
        assertEquals(leaderCmd.symbol, f.symbol);
        assertEquals(leaderCmd.targetLoanId, f.targetLoanId);
        assertEquals(leaderCmd.price, f.price);
        assertEquals(leaderCmd.size, f.size);
        assertEquals(leaderCmd.orderId, f.orderId);
        assertSame(leaderCmd.action, f.action);
        assertSame(leaderCmd.orderType, f.orderType);
    }

    @Test
    void repriceLoanRates_roundTripPreservesCaseAndTimestamp() throws Exception {
        long ts = 1_700_000_000_000L;
        var leaderCmd = exchange.core2.core.common.api.ApiRepriceLoanRates.builder().build();

        byte[] raftLog = ApiCommandConverters.liquidationCmdToRaftLog(leaderCmd, ts);
        ApiCommand parsed = ApiCommand.parseFrom(raftLog);
        assertEquals(ts, parsed.getTimestamp(), "reprice 的 leader timestamp 须随 raft log 复制（累加器确定性）");
        assertSame(ApiCommand.CommandCase.REPRICE_LOAN_RATES, parsed.getCommandCase());

        var f = ApiCommandConverters.convertRepriceLoanRates(parsed);
        assertEquals(ts, f.timestamp, "follower 侧 timestamp 透传");
    }

    @Test
    void batchAddLoan_rateCurvePreset_mapsToPresetCurve() {
        // 档位:STANDARD → base=200/kink=8000(固定)/slope1=400/slope2=6000,lockedAdjust=0
        var grpc = com.binance.raftexchange.stubs.request.BatchAddLoanCommand.newBuilder()
            .setRateCurve(com.binance.raftexchange.stubs.request.SpotLoanRateCurveConfig.newBuilder()
                .setPreset(com.binance.raftexchange.stubs.request.SpotLoanRatePreset.RATE_PRESET_STANDARD))
            .build();

        var cmd = ApiCommandConverters.convertBatchAddLoan(grpc);

        var r = cmd.getRateCurve();
        assertEquals(200, r.getBaseBps());
        assertEquals(8000, r.getKinkUtilBps());
        assertEquals(400, r.getSlope1Bps());
        assertEquals(6000, r.getSlope2Bps());
        assertEquals(0, r.getLockedRateAdjustBps());
    }

    @Test
    void batchAddLoan_symbolMinimal_omittedOverridesBecomeUnset() {
        // 简化核心:每市场只传 symbolId + initialLtv,其余 optional 缺省 → UNSET(−1)(由 resolve 派生)
        final int UNSET = exchange.core2.core.common.api.binary.BatchAddLoanCommand.SymbolLoanConfig.UNSET;
        var grpc = com.binance.raftexchange.stubs.request.BatchAddLoanCommand.newBuilder()
            .setSymbol(com.binance.raftexchange.stubs.request.SpotLoanConfig.newBuilder()
                .setSymbolId(202).setLoanInitialLtvBps(6000))
            .build();

        var cmd = ApiCommandConverters.convertBatchAddLoan(grpc);

        var s = cmd.getSymbol();
        assertEquals(202, s.getSymbolId());
        assertEquals(6000, s.getLoanInitialLtvBps());
        assertEquals(UNSET, s.getLoanLiquidationLtvBps(), "缺省 → UNSET → 派生");
        assertEquals(UNSET, s.getLoanMarginCallLtvBps());
        assertEquals((long) UNSET, s.getLoanMaxAmount());
        assertEquals(UNSET, s.getLoanMaxTermDays());
        assertEquals(UNSET, s.getCollateralWeightBps());
    }

    // ============================================================
    // loan 命令的字段映射：CommandRegistryAllCasesTest 只验类型，改名字段（loanCcy→loanCurrency）靠这里守
    // ============================================================

    @Test
    void convertLoanCreate_mapsAllFields() {
        var cmd = ApiCommandConverters.convertLoanCreate(ApiCommand.newBuilder()
            .setLoanCreate(com.binance.raftexchange.stubs.request.ApiLoanCreate.newBuilder()
                .setTransactionId(11L).setUid(7L).setLoanId(88L).setSymbol(101)
                .setCollateralAmount(150_000_000L).setPrincipal(300_250_000L).setRateMode(1))
            .build());

        assertEquals(11L, cmd.transactionId);
        assertEquals(7L, cmd.uid);
        assertEquals(88L, cmd.loanId);
        assertEquals(101, cmd.symbol);
        assertEquals(150_000_000L, cmd.collateralAmount, "抵押量原样透传，server 不做二次 scale");
        assertEquals(300_250_000L, cmd.principal, "本金原样透传");
        assertEquals((byte) 1, cmd.rateMode);
    }

    @Test
    void convertLoanRepay_mapsRepayAmount() {
        var cmd = ApiCommandConverters.convertLoanRepay(ApiCommand.newBuilder()
            .setLoanRepay(com.binance.raftexchange.stubs.request.ApiLoanRepay.newBuilder()
                .setTransactionId(12L).setUid(7L).setLoanId(88L).setRepayAmount(100_000_000L))
            .build());

        assertEquals(88L, cmd.loanId);
        assertEquals(100_000_000L, cmd.repayAmount);
    }

    @Test
    void convertLoanCrossBorrow_mapsSymbolId() {
        // proto 与 core 都叫 symbolId——改名映射错了不会有别的测试发现
        var cmd = ApiCommandConverters.convertLoanCrossBorrow(ApiCommand.newBuilder()
            .setLoanCrossBorrow(com.binance.raftexchange.stubs.request.ApiLoanCrossBorrow.newBuilder()
                .setTransactionId(13L).setUid(7L).setLoanId(99L).setSymbolId(2).setPrincipal(500_500_000L))
            .build());

        assertEquals(99L, cmd.loanId);
        assertEquals(2, cmd.symbolId, "symbolId 必须落到 symbolId");
        assertEquals(500_500_000L, cmd.principal);
    }

    @Test
    void convertCrossCollateralCommands_mapCurrencyAndAmount() {
        var add = ApiCommandConverters.convertLoanCrossAddCollateral(ApiCommand.newBuilder()
            .setLoanCrossAddCollateral(com.binance.raftexchange.stubs.request.ApiLoanCrossAddCollateral.newBuilder()
                .setTransactionId(14L).setUid(7L).setCurrency(1).setAmount(200_000_000L))
            .build());
        assertEquals(1, add.currency);
        assertEquals(200_000_000L, add.amount);

        var withdraw = ApiCommandConverters.convertLoanCrossWithdrawCollateral(ApiCommand.newBuilder()
            .setLoanCrossWithdrawCollateral(
                com.binance.raftexchange.stubs.request.ApiLoanCrossWithdrawCollateral.newBuilder()
                    .setTransactionId(15L).setUid(7L).setCurrency(2).setAmount(1_500_000L))
            .build());
        assertEquals(2, withdraw.currency);
        assertEquals(1_500_000L, withdraw.amount);
    }

    @Test
    void convertPoolAndLifCommands_mapShardCurrencyAmount() {
        var poolDeposit = ApiCommandConverters.convertPoolDeposit(ApiCommand.newBuilder()
            .setPoolDeposit(com.binance.raftexchange.stubs.request.ApiPoolDeposit.newBuilder()
                .setShardId(1).setCurrency(2).setAmount(10_000_000_000L))
            .build());
        assertEquals(1, poolDeposit.shardId);
        assertEquals(2, poolDeposit.currency);
        assertEquals(10_000_000_000L, poolDeposit.amount);

        var lifDeposit = ApiCommandConverters.convertLoanIfDeposit(ApiCommand.newBuilder()
            .setLoanIfDeposit(com.binance.raftexchange.stubs.request.ApiLoanIfDeposit.newBuilder()
                .setShardId(2).setCurrency(2).setAmount(250_000_000L))
            .build());
        assertEquals(2, lifDeposit.shardId);
        assertEquals(250_000_000L, lifDeposit.amount);

        var lifWithdraw = ApiCommandConverters.convertLoanIfWithdraw(ApiCommand.newBuilder()
            .setLoanIfWithdraw(com.binance.raftexchange.stubs.request.ApiLoanIfWithdraw.newBuilder()
                .setShardId(0).setCurrency(1).setAmount(75_000_000L))
            .build());
        assertEquals(1, lifWithdraw.currency);
        assertEquals(75_000_000L, lifWithdraw.amount);
    }
}
