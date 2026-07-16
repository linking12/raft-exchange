package com.binance.raftexchange.server.raft;

import com.binance.raftexchange.server.exchange.ApiCommandConverters;
import com.binance.raftexchange.stubs.request.ApiCommand;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/**
 * gRPC {@link ApiCommand} → exchange-core ApiCommand 的纯转换表，两 backend 共用。 dispatch（callExchange / submitBinaryDataAsync
 * / NOP short-circuit）在各 state machine 自行处理。 未注册 case（BINARY_DATA / 未来新增）由 {@link #toExchangeCommand} 返 null。
 */
public final class CommandRegistry {

    private final Map<ApiCommand.CommandCase,
        Function<ApiCommand, exchange.core2.core.common.api.ApiCommand>> converters =
            new EnumMap<>(ApiCommand.CommandCase.class);

    public CommandRegistry() {
        register(ApiCommand.CommandCase.PLACE_ORDER, ApiCommandConverters::convertPlaceOrder);
        register(ApiCommand.CommandCase.MOVE_ORDER, ApiCommandConverters::convertMoveOrder);
        register(ApiCommand.CommandCase.CANCEL_ORDER, ApiCommandConverters::convertCancelOrder);
        register(ApiCommand.CommandCase.REDUCE_ORDER, ApiCommandConverters::convertReduceOrder);
        register(ApiCommand.CommandCase.CLOSE_POSITION, ApiCommandConverters::convertClosePosition);
        register(ApiCommand.CommandCase.ORDER_BOOK_REQUEST, ApiCommandConverters::convertOrderBookRequest);
        register(ApiCommand.CommandCase.ADJUST_LEVERAGE, ApiCommandConverters::convertAdjustLeverage);

        register(ApiCommand.CommandCase.ADJUST_BALANCE, ApiCommandConverters::convertAdjustBalance);
        register(ApiCommand.CommandCase.ADD_USER, ApiCommandConverters::convertAddUser);
        register(ApiCommand.CommandCase.SUSPEND_USER, ApiCommandConverters::convertSuspendUser);
        register(ApiCommand.CommandCase.RESUME_USER, ApiCommandConverters::convertResumeUser);
        register(ApiCommand.CommandCase.ADJUST_POSITION_MODE, ApiCommandConverters::convertAdjustPositionMode);
        register(ApiCommand.CommandCase.ADJUST_MARGIN, ApiCommandConverters::convertAdjustMargin);

        register(ApiCommand.CommandCase.ADJUST_MARKPRICE, ApiCommandConverters::convertAdjustMarkPrice);
        register(ApiCommand.CommandCase.SETTLE_FUNDING_FEES, ApiCommandConverters::convertSettleFundingFees);
        register(ApiCommand.CommandCase.SETTLE_PNL, ApiCommandConverters::convertSettlePNL);
        register(ApiCommand.CommandCase.INSURANCE_FUND_DEPOSIT, ApiCommandConverters::convertInsuranceFundDeposit);
        register(ApiCommand.CommandCase.INSURANCE_FUND_WITHDRAW, ApiCommandConverters::convertInsuranceFundWithdraw);

        register(ApiCommand.CommandCase.LIQUIDATION_ORDER, ApiCommandConverters::convertLiquidationOrder);
        register(ApiCommand.CommandCase.IF_TAKEOVER, ApiCommandConverters::convertIFTakeOver);
        register(ApiCommand.CommandCase.AUTO_DELEVERAGING, ApiCommandConverters::convertAutoDeleveraging);
        register(ApiCommand.CommandCase.LOAN_FORCE_LIQUIDATE, ApiCommandConverters::convertLoanForceLiquidate);
        register(ApiCommand.CommandCase.LOAN_CROSS_FORCE_LIQUIDATE,
            ApiCommandConverters::convertLoanCrossForceLiquidate);

        register(ApiCommand.CommandCase.RESET_FEE, ApiCommandConverters::convertResetFee);
        register(ApiCommand.CommandCase.REPRICE_LOAN_RATES, ApiCommandConverters::convertRepriceLoanRates);
        register(ApiCommand.CommandCase.LIQUIDATION_SCAN, ApiCommandConverters::convertLiquidationScan);
        register(ApiCommand.CommandCase.NOP, ApiCommandConverters::convertNop);

        // loan 子域（详见 loan.md §5）；FORCE_LIQUIDATE 两条 scanner 内部触发，暂无 proto，等 force-sell 落地时同批加。
        register(ApiCommand.CommandCase.LOAN_CREATE, ApiCommandConverters::convertLoanCreate);
        register(ApiCommand.CommandCase.LOAN_REPAY, ApiCommandConverters::convertLoanRepay);
        register(ApiCommand.CommandCase.LOAN_ADD_COLLATERAL, ApiCommandConverters::convertLoanAddCollateral);
        register(ApiCommand.CommandCase.LOAN_RELEASE_COLLATERAL, ApiCommandConverters::convertLoanReleaseCollateral);
        register(ApiCommand.CommandCase.LOAN_CROSS_ADD_COLLATERAL,
            ApiCommandConverters::convertLoanCrossAddCollateral);
        register(ApiCommand.CommandCase.LOAN_CROSS_WITHDRAW_COLLATERAL,
            ApiCommandConverters::convertLoanCrossWithdrawCollateral);
        register(ApiCommand.CommandCase.LOAN_CROSS_BORROW, ApiCommandConverters::convertLoanCrossBorrow);
        register(ApiCommand.CommandCase.LOAN_CROSS_REPAY, ApiCommandConverters::convertLoanCrossRepay);
        register(ApiCommand.CommandCase.POOL_DEPOSIT, ApiCommandConverters::convertPoolDeposit);
        register(ApiCommand.CommandCase.POOL_WITHDRAW, ApiCommandConverters::convertPoolWithdraw);
        register(ApiCommand.CommandCase.POOL_ABSORB_BAD_DEBT, ApiCommandConverters::convertPoolAbsorbBadDebt);
    }

    private void register(ApiCommand.CommandCase cc,
        Function<ApiCommand, ? extends exchange.core2.core.common.api.ApiCommand> convert) {
        @SuppressWarnings("unchecked")
        Function<ApiCommand, exchange.core2.core.common.api.ApiCommand> upcast =
            (Function<ApiCommand, exchange.core2.core.common.api.ApiCommand>)convert;
        converters.put(cc, upcast);
    }

    /** 未注册 case 返 null。 */
    public exchange.core2.core.common.api.ApiCommand toExchangeCommand(ApiCommand cmd) {
        Function<ApiCommand, exchange.core2.core.common.api.ApiCommand> conv = converters.get(cmd.getCommandCase());
        return conv == null ? null : conv.apply(cmd);
    }

    public boolean canBatch(ApiCommand cmd) {
        return converters.containsKey(cmd.getCommandCase());
    }
}
