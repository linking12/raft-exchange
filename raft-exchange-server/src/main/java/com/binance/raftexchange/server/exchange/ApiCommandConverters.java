package com.binance.raftexchange.server.exchange;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;

import com.binance.raftexchange.stubs.CoreCurrencySpecification;
import com.binance.raftexchange.stubs.CoreSymbolSpecification;
import com.binance.raftexchange.stubs.request.AccountBalanceMap;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.BatchAddAccountsCommand;
import com.binance.raftexchange.stubs.request.BatchAddCurrenciesCommand;
import com.binance.raftexchange.stubs.request.BatchAddSymbolsCommand;

import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionMode;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiAdjustLeverage;
import exchange.core2.core.common.api.ApiAdjustMargin;
import exchange.core2.core.common.api.ApiAdjustMarkPrice;
import exchange.core2.core.common.api.ApiAdjustPositionMode;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiAutoDeleveraging;
import exchange.core2.core.common.api.ApiCancelOrder;
import exchange.core2.core.common.api.ApiClosePosition;
import exchange.core2.core.common.api.ApiIFTakeOver;
import exchange.core2.core.common.api.ApiInsuranceFundDeposit;
import exchange.core2.core.common.api.ApiInsuranceFundWithdraw;
import exchange.core2.core.common.api.ApiLiquidationOrder;
import exchange.core2.core.common.api.ApiLoanAddCollateral;
import exchange.core2.core.common.api.ApiLoanCreate;
import exchange.core2.core.common.api.ApiLoanCrossAddCollateral;
import exchange.core2.core.common.api.ApiLoanCrossBorrow;
import exchange.core2.core.common.api.ApiLoanCrossRepay;
import exchange.core2.core.common.api.ApiLoanCrossWithdrawCollateral;
import exchange.core2.core.common.api.ApiLoanReleaseCollateral;
import exchange.core2.core.common.api.ApiLoanRepay;
import exchange.core2.core.common.api.ApiMoveOrder;
import exchange.core2.core.common.api.ApiNop;
import exchange.core2.core.common.api.ApiOrderBookRequest;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiPoolDeposit;
import exchange.core2.core.common.api.ApiPoolWithdraw;
import exchange.core2.core.common.api.ApiReduceOrder;
import exchange.core2.core.common.api.ApiResetFee;
import exchange.core2.core.common.api.ApiResumeUser;
import exchange.core2.core.common.api.ApiSettleFundingFees;
import exchange.core2.core.common.api.ApiSettlePNL;
import exchange.core2.core.common.api.ApiSuspendUser;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;

/** gRPC {@link ApiCommand} → exchange-core ApiCommand 的纯静态 converter。 */
public final class ApiCommandConverters {

    private ApiCommandConverters() {}

    public static ApiOrderBookRequest convertOrderBookRequest(ApiCommand apiCommand) {
        var g = apiCommand.getOrderBookRequest();
        ApiOrderBookRequest cmd = ApiOrderBookRequest.builder().symbol(g.getSymbol()).size(g.getSize()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiPlaceOrder convertPlaceOrder(ApiCommand apiCommand) {
        var g = apiCommand.getPlaceOrder();
        ApiPlaceOrder cmd = ApiPlaceOrder.builder().price(g.getPrice()).size(g.getSize()).orderId(g.getOrderId())
            .action(OrderAction.of((byte)g.getAction().getNumber()))
            .orderType(OrderType.of((byte)g.getOrderType().getNumber())).uid(g.getUid()).symbol(g.getSymbol())
            .userCookie(g.getUserCookie()).leverage(g.getLeverage())
            .marginMode(MarginMode.of((byte)g.getMarginMode().getNumber())).reservePrice(g.getReservePrice())
            .reduceOnly(g.getReduceOnly()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiMoveOrder convertMoveOrder(ApiCommand apiCommand) {
        var g = apiCommand.getMoveOrder();
        ApiMoveOrder cmd = ApiMoveOrder.builder().orderId(g.getOrderId()).newPrice(g.getNewPrice()).uid(g.getUid())
            .symbol(g.getSymbol()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiCancelOrder convertCancelOrder(ApiCommand apiCommand) {
        var g = apiCommand.getCancelOrder();
        ApiCancelOrder cmd =
            ApiCancelOrder.builder().orderId(g.getOrderId()).uid(g.getUid()).symbol(g.getSymbol()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiReduceOrder convertReduceOrder(ApiCommand apiCommand) {
        var g = apiCommand.getReduceOrder();
        ApiReduceOrder cmd = ApiReduceOrder.builder().orderId(g.getOrderId()).uid(g.getUid()).symbol(g.getSymbol())
            .reduceSize(g.getReduceSize()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiAdjustLeverage convertAdjustLeverage(ApiCommand apiCommand) {
        var g = apiCommand.getAdjustLeverage();
        ApiAdjustLeverage cmd =
            ApiAdjustLeverage.builder().uid(g.getUid()).symbol(g.getSymbol()).leverage(g.getLeverage()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiClosePosition convertClosePosition(ApiCommand apiCommand) {
        var g = apiCommand.getClosePosition();
        ApiClosePosition cmd = ApiClosePosition.builder().price(g.getPrice()).size(g.getSize()).orderId(g.getOrderId())
            .action(OrderAction.of((byte)g.getAction().getNumber())).uid(g.getUid()).symbol(g.getSymbol()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiAddUser convertAddUser(ApiCommand apiCommand) {
        ApiAddUser cmd = ApiAddUser.builder().uid(apiCommand.getAddUser().getUid()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiAdjustUserBalance convertAdjustBalance(ApiCommand apiCommand) {
        var g = apiCommand.getAdjustBalance();
        ApiAdjustUserBalance cmd = ApiAdjustUserBalance.builder().uid(g.getUid()).currency(g.getCurrency())
            .amount(g.getAmount()).transactionId(g.getTransactionId()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiSuspendUser convertSuspendUser(ApiCommand apiCommand) {
        ApiSuspendUser cmd = ApiSuspendUser.builder().uid(apiCommand.getSuspendUser().getUid()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiResumeUser convertResumeUser(ApiCommand apiCommand) {
        ApiResumeUser cmd = ApiResumeUser.builder().uid(apiCommand.getResumeUser().getUid()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiAdjustPositionMode convertAdjustPositionMode(ApiCommand apiCommand) {
        var g = apiCommand.getAdjustPositionMode();
        ApiAdjustPositionMode cmd = ApiAdjustPositionMode.builder().uid(g.getUid())
            .positionMode(PositionMode.of((byte)g.getPositionMode().getNumber())).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiAdjustMargin convertAdjustMargin(ApiCommand apiCommand) {
        var g = apiCommand.getAdjustMargin();
        ApiAdjustMargin cmd = ApiAdjustMargin.builder().transactionId(g.getTransactionId()).uid(g.getUid())
            .symbol(g.getSymbol()).currency(g.getCurrency()).amount(g.getAmount())
            .marginMode(MarginMode.of((byte)g.getMarginMode().getNumber())).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiAdjustMarkPrice convertAdjustMarkPrice(ApiCommand apiCommand) {
        var g = apiCommand.getAdjustMarkprice();
        ApiAdjustMarkPrice cmd = ApiAdjustMarkPrice.builder().transactionId(g.getTransactionId()).symbol(g.getSymbol())
            .markPrice(g.getMarkPrice()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiSettleFundingFees convertSettleFundingFees(ApiCommand apiCommand) {
        var g = apiCommand.getSettleFundingFees();
        ApiSettleFundingFees cmd = ApiSettleFundingFees.builder().transactionId(g.getTransactionId())
            .symbol(g.getSymbol()).action(OrderAction.of((byte)g.getAction().getNumber()))
            .fundingRate(g.getFundingRate()).rateScaleK(g.getRateScaleK()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiInsuranceFundDeposit convertInsuranceFundDeposit(ApiCommand apiCommand) {
        var g = apiCommand.getInsuranceFundDeposit();
        ApiInsuranceFundDeposit cmd = ApiInsuranceFundDeposit.builder().transactionId(g.getTransactionId())
            .symbol(g.getSymbol()).currencyAmount(g.getCurrencyAmount()).shardId(g.getShardId()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiInsuranceFundWithdraw convertInsuranceFundWithdraw(ApiCommand apiCommand) {
        var g = apiCommand.getInsuranceFundWithdraw();
        ApiInsuranceFundWithdraw cmd = ApiInsuranceFundWithdraw.builder().transactionId(g.getTransactionId())
            .symbol(g.getSymbol()).currencyAmount(g.getCurrencyAmount()).shardId(g.getShardId()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiSettlePNL convertSettlePNL(ApiCommand apiCommand) {
        var g = apiCommand.getSettlePnl();
        ApiSettlePNL cmd = ApiSettlePNL.builder().transactionId(g.getTransactionId()).symbol(g.getSymbol())
            .settlePrice(g.getSettlePrice()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiLiquidationOrder convertLiquidationOrder(ApiCommand apiCommand) {
        var g = apiCommand.getLiquidationOrder();
        ApiLiquidationOrder cmd = ApiLiquidationOrder.builder().price(g.getPrice()).size(g.getSize())
            .orderId(g.getOrderId()).action(OrderAction.of((byte)g.getAction().getNumber()))
            .orderType(OrderType.of((byte)g.getOrderType().getNumber())).uid(g.getUid()).symbol(g.getSymbol()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiIFTakeOver convertIFTakeOver(ApiCommand apiCommand) {
        var g = apiCommand.getIfTakeover();
        ApiIFTakeOver cmd = ApiIFTakeOver.builder().orderId(g.getOrderId()).uid(g.getUid()).symbol(g.getSymbol())
            .action(OrderAction.of((byte)g.getAction().getNumber())).size(g.getSize()).price(g.getPrice()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiAutoDeleveraging convertAutoDeleveraging(ApiCommand apiCommand) {
        var g = apiCommand.getAutoDeleveraging();
        ApiAutoDeleveraging cmd =
            ApiAutoDeleveraging.builder().orderId(g.getOrderId()).uid(g.getUid()).symbol(g.getSymbol())
                .action(OrderAction.of((byte)g.getAction().getNumber())).size(g.getSize()).price(g.getPrice()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static exchange.core2.core.common.api.ApiLoanForceLiquidate
        convertLoanForceLiquidate(ApiCommand apiCommand) {
        var g = apiCommand.getLoanForceLiquidate();
        exchange.core2.core.common.api.ApiLoanForceLiquidate cmd = exchange.core2.core.common.api.ApiLoanForceLiquidate
            .builder().uid(g.getUid()).symbol(g.getSymbol()).loanId(g.getLoanId()).price(g.getPrice()).size(g.getSize())
            .orderId(g.getOrderId()).action(OrderAction.of((byte)g.getAction().getNumber()))
            .orderType(OrderType.of((byte)g.getOrderType().getNumber())).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static exchange.core2.core.common.api.ApiLoanCrossForceLiquidate
        convertLoanCrossForceLiquidate(ApiCommand apiCommand) {
        var g = apiCommand.getLoanCrossForceLiquidate();
        exchange.core2.core.common.api.ApiLoanCrossForceLiquidate cmd =
            exchange.core2.core.common.api.ApiLoanCrossForceLiquidate.builder().uid(g.getUid()).symbol(g.getSymbol())
                .targetLoanId(g.getTargetLoanId()).price(g.getPrice()).size(g.getSize()).orderId(g.getOrderId())
                .action(OrderAction.of((byte)g.getAction().getNumber()))
                .orderType(OrderType.of((byte)g.getOrderType().getNumber())).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiResetFee convertResetFee(ApiCommand apiCommand) {
        ApiResetFee cmd = ApiResetFee.builder().build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiNop convertNop(ApiCommand apiCommand) {
        ApiNop cmd = ApiNop.builder().build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static exchange.core2.core.common.api.ApiRepriceLoanRates convertRepriceLoanRates(ApiCommand apiCommand) {
        var cmd = exchange.core2.core.common.api.ApiRepriceLoanRates.builder().build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    // ============================== loan 子域 converter（详见 loan.md §5）==============================

    public static ApiLoanCreate convertLoanCreate(ApiCommand apiCommand) {
        var g = apiCommand.getLoanCreate();
        ApiLoanCreate cmd = ApiLoanCreate.builder().externalId(g.getExternalId()).uid(g.getUid()).loanId(g.getLoanId())
            .symbol(g.getSymbol()).collateralAmount(g.getCollateralAmount()).principal(g.getPrincipal())
            .rateMode((byte)g.getRateMode()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiLoanRepay convertLoanRepay(ApiCommand apiCommand) {
        var g = apiCommand.getLoanRepay();
        ApiLoanRepay cmd = ApiLoanRepay.builder().externalId(g.getExternalId()).uid(g.getUid()).loanId(g.getLoanId())
            .repayAmount(g.getRepayAmount()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiLoanAddCollateral convertLoanAddCollateral(ApiCommand apiCommand) {
        var g = apiCommand.getLoanAddCollateral();
        ApiLoanAddCollateral cmd = ApiLoanAddCollateral.builder().externalId(g.getExternalId()).uid(g.getUid())
            .loanId(g.getLoanId()).amount(g.getAmount()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiLoanReleaseCollateral convertLoanReleaseCollateral(ApiCommand apiCommand) {
        var g = apiCommand.getLoanReleaseCollateral();
        ApiLoanReleaseCollateral cmd = ApiLoanReleaseCollateral.builder().externalId(g.getExternalId()).uid(g.getUid())
            .loanId(g.getLoanId()).amount(g.getAmount()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiLoanCrossAddCollateral convertLoanCrossAddCollateral(ApiCommand apiCommand) {
        var g = apiCommand.getLoanCrossAddCollateral();
        ApiLoanCrossAddCollateral cmd = ApiLoanCrossAddCollateral.builder().externalId(g.getExternalId())
            .uid(g.getUid()).currency(g.getCurrency()).amount(g.getAmount()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiLoanCrossWithdrawCollateral convertLoanCrossWithdrawCollateral(ApiCommand apiCommand) {
        var g = apiCommand.getLoanCrossWithdrawCollateral();
        ApiLoanCrossWithdrawCollateral cmd = ApiLoanCrossWithdrawCollateral.builder().externalId(g.getExternalId())
            .uid(g.getUid()).currency(g.getCurrency()).amount(g.getAmount()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiLoanCrossBorrow convertLoanCrossBorrow(ApiCommand apiCommand) {
        var g = apiCommand.getLoanCrossBorrow();
        ApiLoanCrossBorrow cmd = ApiLoanCrossBorrow.builder().externalId(g.getExternalId()).uid(g.getUid())
            .loanId(g.getLoanId()).loanCurrency(g.getLoanCcy()).principal(g.getPrincipal()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiLoanCrossRepay convertLoanCrossRepay(ApiCommand apiCommand) {
        var g = apiCommand.getLoanCrossRepay();
        ApiLoanCrossRepay cmd = ApiLoanCrossRepay.builder().externalId(g.getExternalId()).uid(g.getUid())
            .loanId(g.getLoanId()).repayAmount(g.getRepayAmount()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiPoolDeposit convertPoolDeposit(ApiCommand apiCommand) {
        var g = apiCommand.getPoolDeposit();
        ApiPoolDeposit cmd = ApiPoolDeposit.builder().externalId(g.getExternalId()).shardId(g.getShardId())
            .currency(g.getCurrency()).amount(g.getAmount()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static ApiPoolWithdraw convertPoolWithdraw(ApiCommand apiCommand) {
        var g = apiCommand.getPoolWithdraw();
        ApiPoolWithdraw cmd = ApiPoolWithdraw.builder().externalId(g.getExternalId()).shardId(g.getShardId())
            .currency(g.getCurrency()).amount(g.getAmount()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static exchange.core2.core.common.api.ApiPoolAbsorbBadDebt convertPoolAbsorbBadDebt(ApiCommand apiCommand) {
        var g = apiCommand.getPoolAbsorbBadDebt();
        exchange.core2.core.common.api.ApiPoolAbsorbBadDebt cmd =
            exchange.core2.core.common.api.ApiPoolAbsorbBadDebt.builder().externalId(g.getExternalId())
                .shardId(g.getShardId()).currency(g.getCurrency()).amount(g.getAmount()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    public static exchange.core2.core.common.api.binary.BatchAddAccountsCommand
        convertBatchAddAccounts(BatchAddAccountsCommand grpc) {
        var users = new LongObjectHashMap<IntLongHashMap>(grpc.getUsersMap().size());
        for (Map.Entry<Long, AccountBalanceMap> entry : grpc.getUsersMap().entrySet()) {
            for (Map.Entry<Integer, Long> e : entry.getValue().getBalancesMap().entrySet()) {
                users.getIfAbsentPut(entry.getKey(), new IntLongHashMap()).put(e.getKey(), e.getValue());
            }
        }
        return new exchange.core2.core.common.api.binary.BatchAddAccountsCommand(users);
    }

    public static exchange.core2.core.common.api.binary.BatchAddSymbolsCommand
        convertBatchAddSymbols(BatchAddSymbolsCommand grpc) {
        Collection<exchange.core2.core.common.CoreSymbolSpecification> coreSymbols =
            new ArrayList<>(grpc.getSymbolsMap().size());
        for (CoreSymbolSpecification s : grpc.getSymbolsMap().values()) {
            coreSymbols.add(exchange.core2.core.common.CoreSymbolSpecification.builder().symbolId(s.getSymbolId())
                .type(SymbolType.of(s.getType().getNumber())).baseCurrency(s.getBaseCurrency())
                .quoteCurrency(s.getQuoteCurrency()).baseScaleK(s.getBaseScaleK()).quoteScaleK(s.getQuoteScaleK())
                .takerFee(s.getTakerFee()).makerFee(s.getMakerFee()).liquidationFee(s.getLiquidationFee())
                .feeScaleK(s.getFeeScaleK()).initMargin(s.getInitMargin()).initMarginScaleK(s.getInitMarginScaleK())
                .maintenanceMargin(TreeSortedMap.newMap(Comparator.naturalOrder(), s.getMaintenanceMarginMap()))
                .maintenanceMarginScaleK(s.getMaintenanceMarginScaleK())
                .maxLeverage(TreeSortedMap.newMap(Comparator.naturalOrder(), s.getMaxLeverageMap())).build());
        }
        return new exchange.core2.core.common.api.binary.BatchAddSymbolsCommand(coreSymbols);
    }

    public static exchange.core2.core.common.api.binary.BatchAddCurrenciesCommand
        convertBatchAddCurrencies(BatchAddCurrenciesCommand grpc) {
        Collection<exchange.core2.core.common.CoreCurrencySpecification> currencies =
            new ArrayList<>(grpc.getCurrenciesMap().size());
        for (CoreCurrencySpecification c : grpc.getCurrenciesMap().values()) {
            currencies.add(exchange.core2.core.common.CoreCurrencySpecification.builder().id(c.getId())
                .name(c.getName()).digit(c.getDigit()).build());
        }
        return new exchange.core2.core.common.api.binary.BatchAddCurrenciesCommand(currencies);
    }

    /**
     * ADD_LOAN: proto → exchange-core binary command。global / symbol / rateCurve 三部分可选（proto message presence），
     * 字段一一对应无缩放；至少一部分存在（否则 exchange-core 命令构造抛错）。
     */
    public static exchange.core2.core.common.api.binary.BatchAddLoanCommand
        convertBatchAddLoan(com.binance.raftexchange.stubs.request.BatchAddLoanCommand grpc) {
        exchange.core2.core.common.api.binary.BatchAddLoanCommand.GlobalLoanConfig global = null;
        if (grpc.hasGlobal()) {
            var g = grpc.getGlobal();
            global = new exchange.core2.core.common.api.binary.BatchAddLoanCommand.GlobalLoanConfig(g.getNumeraireCcy(),
                g.getCrossLiquidationLtvBps(), g.getCrossMarginCallLtvBps(), g.getLoanPoolUtilizationCapBps(),
                g.getLoanLiquidationFeeBps());
        }
        exchange.core2.core.common.api.binary.BatchAddLoanCommand.SymbolLoanConfig symbol = null;
        if (grpc.hasSymbol()) {
            var s = grpc.getSymbol();
            symbol = new exchange.core2.core.common.api.binary.BatchAddLoanCommand.SymbolLoanConfig(s.getSymbolId(),
                s.getLoanInitialLtvBps(), s.getLoanLiquidationLtvBps(), s.getLoanMarginCallLtvBps(),
                s.getLoanMaxAmount(), s.getLoanMaxTermDays(), s.getCollateralWeightBps());
        }
        exchange.core2.core.common.api.binary.BatchAddLoanCommand.RateCurveConfig rateCurve = null;
        if (grpc.hasRateCurve()) {
            var r = grpc.getRateCurve();
            rateCurve = new exchange.core2.core.common.api.binary.BatchAddLoanCommand.RateCurveConfig(r.getBaseBps(),
                r.getKinkUtilBps(), r.getSlope1Bps(), r.getSlope2Bps(), r.getLockedRateAdjustBps());
        }
        return new exchange.core2.core.common.api.binary.BatchAddLoanCommand(global, symbol, rateCurve);
    }

    /**
     * engine cmd → raft log bytes，跟 convertLiquidationOrder / IFTakeOver / AutoDeleveraging / LoanForceLiquidate 互逆。
     */
    public static byte[] liquidationCmdToRaftLog(exchange.core2.core.common.api.ApiCommand cmd, long timestamp) {
        ApiCommand.Builder b = ApiCommand.newBuilder().setTimestamp(timestamp);
        switch (cmd) {
            case ApiLiquidationOrder lo -> b
                .setLiquidationOrder(com.binance.raftexchange.stubs.request.ApiLiquidationOrder.newBuilder()
                    .setPrice(lo.price).setSize(lo.size).setOrderId(lo.orderId).setActionValue(lo.action.getCode())
                    .setOrderTypeValue(lo.orderType.getCode()).setUid(lo.uid).setSymbol(lo.symbol));
            case ApiIFTakeOver iff -> b.setIfTakeover(com.binance.raftexchange.stubs.request.ApiIFTakeOver.newBuilder()
                .setOrderId(iff.orderId).setUid(iff.uid).setSymbol(iff.symbol).setActionValue(iff.action.getCode())
                .setSize(iff.size).setPrice(iff.price));
            case ApiAutoDeleveraging adl -> b
                .setAutoDeleveraging(com.binance.raftexchange.stubs.request.ApiAutoDeleveraging.newBuilder()
                    .setOrderId(adl.orderId).setUid(adl.uid).setSymbol(adl.symbol).setActionValue(adl.action.getCode())
                    .setSize(adl.size).setPrice(adl.price));
            case exchange.core2.core.common.api.ApiLoanForceLiquidate lfl -> b
                .setLoanForceLiquidate(com.binance.raftexchange.stubs.request.ApiLoanForceLiquidate.newBuilder()
                    .setUid(lfl.uid).setSymbol(lfl.symbol).setLoanId(lfl.loanId).setPrice(lfl.price).setSize(lfl.size)
                    .setOrderId(lfl.orderId).setActionValue(lfl.action.getCode())
                    .setOrderTypeValue(lfl.orderType.getCode()));
            case exchange.core2.core.common.api.ApiLoanCrossForceLiquidate cfl -> b
                .setLoanCrossForceLiquidate(com.binance.raftexchange.stubs.request.ApiLoanCrossForceLiquidate
                    .newBuilder().setUid(cfl.uid).setSymbol(cfl.symbol).setTargetLoanId(cfl.targetLoanId)
                    .setPrice(cfl.price).setSize(cfl.size).setOrderId(cfl.orderId).setActionValue(cfl.action.getCode())
                    .setOrderTypeValue(cfl.orderType.getCode()));
            case exchange.core2.core.common.api.ApiRepriceLoanRates rlr -> b
                .setRepriceLoanRates(com.binance.raftexchange.stubs.request.ApiRepriceLoanRates.newBuilder());
            default -> throw new IllegalArgumentException("unsupported liquidation cmd: " + cmd.getClass().getName());
        }
        return b.build().toByteArray();
    }
}
