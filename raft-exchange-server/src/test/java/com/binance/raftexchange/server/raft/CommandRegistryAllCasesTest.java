package com.binance.raftexchange.server.raft;

import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.PositionMode;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiAdjustLeverage;
import com.binance.raftexchange.stubs.request.ApiAutoDeleveraging;
import com.binance.raftexchange.stubs.request.ApiIFTakeOver;
import com.binance.raftexchange.stubs.request.ApiLiquidationOrder;
import com.binance.raftexchange.stubs.request.ApiLoanAddCollateral;
import com.binance.raftexchange.stubs.request.ApiLoanCreate;
import com.binance.raftexchange.stubs.request.ApiLoanCrossAddCollateral;
import com.binance.raftexchange.stubs.request.ApiLoanCrossBorrow;
import com.binance.raftexchange.stubs.request.ApiLoanCrossRepay;
import com.binance.raftexchange.stubs.request.ApiLoanCrossWithdrawCollateral;
import com.binance.raftexchange.stubs.request.ApiLoanReleaseCollateral;
import com.binance.raftexchange.stubs.request.ApiLoanRepay;
import com.binance.raftexchange.stubs.request.ApiAdjustMargin;
import com.binance.raftexchange.stubs.request.ApiAdjustMarkPrice;
import com.binance.raftexchange.stubs.request.ApiAdjustPositionMode;
import com.binance.raftexchange.stubs.request.ApiAdjustUserBalance;
import com.binance.raftexchange.stubs.request.ApiInternalTransfer;
import com.binance.raftexchange.stubs.request.ApiCancelOrder;
import com.binance.raftexchange.stubs.request.ApiClosePosition;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiMoveOrder;
import com.binance.raftexchange.stubs.request.ApiNop;
import com.binance.raftexchange.stubs.request.ApiOrderBookRequest;
import com.binance.raftexchange.stubs.request.ApiPlaceOrder;
import com.binance.raftexchange.stubs.request.ApiPoolDeposit;
import com.binance.raftexchange.stubs.request.ApiLoanIfDeposit;
import com.binance.raftexchange.stubs.request.ApiLoanIfWithdraw;
import com.binance.raftexchange.stubs.request.ApiPoolWithdraw;
import com.binance.raftexchange.stubs.request.ApiReduceOrder;
import com.binance.raftexchange.stubs.request.ApiResetFee;
import com.binance.raftexchange.stubs.request.ApiResumeUser;
import com.binance.raftexchange.stubs.request.ApiSettleFundingFees;
import com.binance.raftexchange.stubs.request.ApiSettlePNL;
import com.binance.raftexchange.stubs.request.ApiSuspendUser;
import com.binance.raftexchange.stubs.request.ApiInsuranceFundDeposit;
import com.binance.raftexchange.stubs.request.ApiInsuranceFundWithdraw;
import com.binance.raftexchange.stubs.request.ApiLoanForceLiquidate;
import com.binance.raftexchange.stubs.request.ApiLoanCrossForceLiquidate;
import com.binance.raftexchange.stubs.request.ApiRepriceLoanRates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CommandRegistry 所有 case 一对一 dispatch 全覆盖：每个 gRPC oneof case 都映射到正确的 exchange-core ApiCommand 子类。 防止重构时漏掉某条注册或者注册到错的
 * convert 函数。
 */
class CommandRegistryAllCasesTest {

    static Stream<Arguments> allCases() {
        return Stream.of(
            Arguments.of(ApiCommand.newBuilder()
                .setPlaceOrder(ApiPlaceOrder.newBuilder().setOrderId(1L).setUid(7L).setSymbol(1)
                    .setAction(OrderAction.BID).setOrderType(OrderType.GTC).setMarginMode(MarginMode.ISOLATED))
                .build(), exchange.core2.core.common.api.ApiPlaceOrder.class),
            Arguments.of(
                ApiCommand.newBuilder().setMoveOrder(ApiMoveOrder.newBuilder().setOrderId(1L).setUid(7L)).build(),
                exchange.core2.core.common.api.ApiMoveOrder.class),
            Arguments.of(
                ApiCommand.newBuilder().setCancelOrder(ApiCancelOrder.newBuilder().setOrderId(1L).setUid(7L)).build(),
                exchange.core2.core.common.api.ApiCancelOrder.class),
            Arguments.of(
                ApiCommand.newBuilder().setReduceOrder(ApiReduceOrder.newBuilder().setOrderId(1L).setUid(7L)).build(),
                exchange.core2.core.common.api.ApiReduceOrder.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setClosePosition(
                        ApiClosePosition.newBuilder().setOrderId(1L).setUid(7L).setAction(OrderAction.BID))
                    .build(),
                exchange.core2.core.common.api.ApiClosePosition.class),
            Arguments.of(
                ApiCommand.newBuilder().setOrderBookRequest(ApiOrderBookRequest.newBuilder().setSymbol(1)).build(),
                exchange.core2.core.common.api.ApiOrderBookRequest.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setAdjustLeverage(ApiAdjustLeverage.newBuilder().setUid(7L).setSymbol(1).setLeverage(10)).build(),
                exchange.core2.core.common.api.ApiAdjustLeverage.class),
            Arguments.of(ApiCommand.newBuilder()
                .setAdjustBalance(ApiAdjustUserBalance.newBuilder().setUid(7L).setCurrency(2).setAmount(100L)).build(),
                exchange.core2.core.common.api.ApiAdjustUserBalance.class),
            Arguments.of(ApiCommand.newBuilder().setInternalTransfer(ApiInternalTransfer.newBuilder().setFromUid(7L)
                .setToUid(8L).setCurrency(2).setAmount(100L).setTransactionId(1L)).build(),
                exchange.core2.core.common.api.ApiInternalTransfer.class),
            Arguments.of(ApiCommand.newBuilder().setAddUser(ApiAddUser.newBuilder().setUid(42L)).build(),
                exchange.core2.core.common.api.ApiAddUser.class),
            Arguments.of(ApiCommand.newBuilder().setSuspendUser(ApiSuspendUser.newBuilder().setUid(7L)).build(),
                exchange.core2.core.common.api.ApiSuspendUser.class),
            Arguments.of(ApiCommand.newBuilder().setResumeUser(ApiResumeUser.newBuilder().setUid(7L)).build(),
                exchange.core2.core.common.api.ApiResumeUser.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setAdjustPositionMode(
                        ApiAdjustPositionMode.newBuilder().setUid(7L).setPositionMode(PositionMode.ONEWAY))
                    .build(),
                exchange.core2.core.common.api.ApiAdjustPositionMode.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setAdjustMargin(ApiAdjustMargin.newBuilder().setUid(7L).setSymbol(1).setCurrency(2).setAmount(100L)
                        .setMarginMode(MarginMode.ISOLATED))
                    .build(),
                exchange.core2.core.common.api.ApiAdjustMargin.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setAdjustMarkprice(ApiAdjustMarkPrice.newBuilder().setSymbol(1).setMarkPrice(100L)).build(),
                exchange.core2.core.common.api.ApiAdjustMarkPrice.class),
            Arguments.of(ApiCommand.newBuilder()
                .setSettleFundingFees(ApiSettleFundingFees.newBuilder().setSymbol(1).setAction(OrderAction.BID))
                .build(), exchange.core2.core.common.api.ApiSettleFundingFees.class),
            Arguments.of(ApiCommand.newBuilder()
                .setSettlePnl(ApiSettlePNL.newBuilder().setSymbol(1).setSettlePrice(100L)).build(),
                exchange.core2.core.common.api.ApiSettlePNL.class),
            Arguments.of(ApiCommand.newBuilder().setResetFee(ApiResetFee.newBuilder()).build(),
                exchange.core2.core.common.api.ApiResetFee.class),
            Arguments.of(ApiCommand.newBuilder().setNop(ApiNop.newBuilder()).build(),
                exchange.core2.core.common.api.ApiNop.class),
            // 强平 cmd 经 raft 复制：leader publish → follower FSM apply → CommandRegistry dispatch 到 LE 状态机入口
            Arguments.of(
                ApiCommand.newBuilder()
                    .setLiquidationOrder(ApiLiquidationOrder.newBuilder().setOrderId(1L).setUid(7L).setSymbol(1)
                        .setAction(OrderAction.ASK).setOrderType(OrderType.IOC).setPrice(100L).setSize(4L))
                    .build(),
                exchange.core2.core.common.api.ApiLiquidationOrder.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setIfTakeover(ApiIFTakeOver.newBuilder().setOrderId(1L).setUid(7L).setSymbol(1)
                        .setAction(OrderAction.BID).setPrice(100L).setSize(4L))
                    .build(),
                exchange.core2.core.common.api.ApiIFTakeOver.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setAutoDeleveraging(ApiAutoDeleveraging.newBuilder().setOrderId(1L).setUid(7L).setSymbol(1)
                        .setAction(OrderAction.BID).setPrice(100L).setSize(4L))
                    .build(),
                exchange.core2.core.common.api.ApiAutoDeleveraging.class),
            // loan 子域 —— 详见 loan.md §5；每个 CommandCase.LOAN_XXX / POOL_XXX 一条覆盖，防止漏注册
            Arguments.of(
                ApiCommand.newBuilder()
                    .setLoanCreate(ApiLoanCreate.newBuilder().setTransactionId(1L).setUid(7L).setLoanId(100L)
                        .setSymbol(1).setCollateralAmount(1L).setPrincipal(30000L))
                    .build(),
                exchange.core2.core.common.api.ApiLoanCreate.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setLoanRepay(ApiLoanRepay.newBuilder().setTransactionId(2L).setUid(7L).setLoanId(100L)
                        .setRepayAmount(0L))
                    .build(),
                exchange.core2.core.common.api.ApiLoanRepay.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setLoanAddCollateral(ApiLoanAddCollateral.newBuilder().setTransactionId(3L).setUid(7L)
                        .setLoanId(100L).setAmount(1L))
                    .build(),
                exchange.core2.core.common.api.ApiLoanAddCollateral.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setLoanReleaseCollateral(ApiLoanReleaseCollateral.newBuilder().setTransactionId(4L).setUid(7L)
                        .setLoanId(100L).setAmount(1L))
                    .build(),
                exchange.core2.core.common.api.ApiLoanReleaseCollateral.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setLoanCrossAddCollateral(ApiLoanCrossAddCollateral.newBuilder().setTransactionId(5L).setUid(7L)
                        .setCurrency(1).setAmount(1L))
                    .build(),
                exchange.core2.core.common.api.ApiLoanCrossAddCollateral.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setLoanCrossWithdrawCollateral(ApiLoanCrossWithdrawCollateral.newBuilder().setTransactionId(6L)
                        .setUid(7L).setCurrency(1).setAmount(1L))
                    .build(),
                exchange.core2.core.common.api.ApiLoanCrossWithdrawCollateral.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setLoanCrossBorrow(ApiLoanCrossBorrow.newBuilder().setTransactionId(7L).setUid(7L).setLoanId(200L)
                        .setSymbolId(2).setPrincipal(10000L))
                    .build(),
                exchange.core2.core.common.api.ApiLoanCrossBorrow.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setLoanCrossRepay(ApiLoanCrossRepay.newBuilder().setTransactionId(8L).setUid(7L).setLoanId(200L)
                        .setRepayAmount(0L))
                    .build(),
                exchange.core2.core.common.api.ApiLoanCrossRepay.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setPoolDeposit(ApiPoolDeposit.newBuilder().setShardId(0).setCurrency(2)
                        .setAmount(50000L))
                    .build(),
                exchange.core2.core.common.api.ApiPoolDeposit.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setPoolWithdraw(ApiPoolWithdraw.newBuilder().setShardId(0).setCurrency(2)
                        .setAmount(10000L))
                    .build(),
                exchange.core2.core.common.api.ApiPoolWithdraw.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setLoanIfDeposit(ApiLoanIfDeposit.newBuilder().setShardId(0).setCurrency(2)
                        .setAmount(10000L))
                    .build(),
                exchange.core2.core.common.api.ApiLoanIfDeposit.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setLoanIfWithdraw(ApiLoanIfWithdraw.newBuilder().setShardId(0).setCurrency(2)
                        .setAmount(10000L))
                    .build(),
                exchange.core2.core.common.api.ApiLoanIfWithdraw.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setLoanForceLiquidate(ApiLoanForceLiquidate.newBuilder().setUid(7L).setSymbol(1).setLoanId(100L)
                        .setPrice(100L).setSize(1L).setOrderId(1L).setAction(OrderAction.ASK)
                        .setOrderType(OrderType.IOC))
                    .build(),
                exchange.core2.core.common.api.ApiLoanForceLiquidate.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setLoanCrossForceLiquidate(ApiLoanCrossForceLiquidate.newBuilder().setUid(7L).setSymbol(1)
                        .setTargetLoanId(200L).setPrice(100L).setSize(1L).setOrderId(1L).setAction(OrderAction.ASK)
                        .setOrderType(OrderType.IOC))
                    .build(),
                exchange.core2.core.common.api.ApiLoanCrossForceLiquidate.class),
            Arguments.of(ApiCommand.newBuilder().setRepriceLoanRates(ApiRepriceLoanRates.newBuilder()).build(),
                exchange.core2.core.common.api.ApiRepriceLoanRates.class),
            Arguments.of(ApiCommand.newBuilder()
                .setLiquidationScan(com.binance.raftexchange.stubs.request.ApiLiquidationScan.newBuilder()).build(),
                exchange.core2.core.common.api.ApiLiquidationScan.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setInsuranceFundDeposit(ApiInsuranceFundDeposit.newBuilder().setTransactionId(1L).setSymbol(1)
                        .setCurrencyAmount(1000L).setShardId(0))
                    .build(),
                exchange.core2.core.common.api.ApiInsuranceFundDeposit.class),
            Arguments.of(
                ApiCommand.newBuilder()
                    .setInsuranceFundWithdraw(ApiInsuranceFundWithdraw.newBuilder().setTransactionId(2L).setSymbol(1)
                        .setCurrencyAmount(1000L).setShardId(0))
                    .build(),
                exchange.core2.core.common.api.ApiInsuranceFundWithdraw.class));
    }

    /**
     * 完备性守卫：除去 oneof 未设 + BINARY_DATA（走 ExchangeCalls.applyBinaryData），每个 CommandCase 都必须在 allCases 里有一条。
     * 新增一条 gRPC 命令却忘了在此测 + 在 CommandRegistry 注册时，本测试会直接失败（防"漏注册/漏测"）。
     */
    @Test
    void allCases_coversEveryDispatchableCommandCase() {
        Set<ApiCommand.CommandCase> covered =
            allCases().map(a -> ((ApiCommand)a.get()[0]).getCommandCase()).collect(Collectors.toSet());
        Set<ApiCommand.CommandCase> excluded =
            EnumSet.of(ApiCommand.CommandCase.COMMAND_NOT_SET, ApiCommand.CommandCase.BINARY_DATA);
        for (ApiCommand.CommandCase cc : ApiCommand.CommandCase.values()) {
            if (!excluded.contains(cc)) {
                assertTrue(covered.contains(cc),
                    "CommandCase " + cc + " 未被 allCases 覆盖（新增命令须在此补一条 + 在 CommandRegistry 注册）");
            }
        }
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("allCases")
    void toExchangeCommand_mapsToExpectedCorePojoType(ApiCommand grpcCmd,
        Class<? extends exchange.core2.core.common.api.ApiCommand> expectedCoreType) {
        CommandRegistry reg = new CommandRegistry();

        exchange.core2.core.common.api.ApiCommand result = reg.toExchangeCommand(grpcCmd);

        assertNotNull(result, "case " + grpcCmd.getCommandCase() + " 必须有 dispatch entry");
        assertEquals(expectedCoreType, result.getClass(),
            "case " + grpcCmd.getCommandCase() + " 应映射到 " + expectedCoreType.getSimpleName());
    }
}
