package exchange.core2.core.processors.liquidation;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.api.ApiAutoDeleveraging;
import exchange.core2.core.common.cmd.OrderCommand;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class LiquidationStateMachine {

    private final ExchangeApi exchangeApi;

    private LiquidationStateMachine(ExchangeApi exchangeApi) {
        this.exchangeApi = exchangeApi;
    }

    private static class Holder {
        private static LiquidationStateMachine instance;
    }

    public static void init(ExchangeApi exchangeApi) {
        Holder.instance = new LiquidationStateMachine(exchangeApi);
    }

    public static LiquidationStateMachine getInstance() {
        return Holder.instance;
    }


    public void next(OrderCommand cmd, SymbolPositionRecord pos) {
        switch (cmd.command) {
            case FORCE_LIQUIDATION -> onMarketDone(cmd, pos);

//            case IF_JUDGED -> onIfJudged(pos);
//
//            case IF_SETTLED -> onIfSettled(pos);

            case AUTO_DELEVERAGING -> onADLDone(pos);
        }
    }


    private void onMarketDone(OrderCommand cmd, SymbolPositionRecord pos) {
        LiquidationContext ctx = pos.liquidationCtx;
        assert ctx.state == LiquidationState.LIQUIDATING;
        /**
         * 如果第一个事件是reject，说明没有完全成交。
         *
         * @see exchange.core2.core.orderbook.OrderBookDirectImpl#newOrderMatchIoc
         */
        MatcherTradeEvent firstEvent = cmd.matcherEvent;
        // 市场完全吃完，直接关闭
        if (firstEvent.eventType != MatcherEventType.REJECT) {
            ctx.size = 0;
            ctx.state = LiquidationState.CLOSED;
            return;
        }
        // 还有剩余
        ctx.size = firstEvent.size;
        if (shouldTryIF()) {
            ctx.state = LiquidationState.WAIT_IF_JUDGEMENT;
            exchangeApi.submitCommand(null); //todo submit IF cmd
        } else {
            ctx.state = LiquidationState.WAIT_ADL_EXECUTION;
            ApiAutoDeleveraging adlCmd = ApiAutoDeleveraging.builder()
                    .orderId(ADLUserPositionHelper.generateADLOrderId(pos))
                    .uid(pos.uid).symbol(pos.symbol)
                    .action(pos.direction == PositionDirection.LONG ? OrderAction.BID : OrderAction.ASK)
                    .size(ctx.size).price(ctx.price).build();
            log.warn("adlCmd={}", adlCmd);
            exchangeApi.submitCommand(adlCmd);
        }
    }

    private boolean shouldTryIF() {
        return false; // todo
    }

    // ME：IF-J 接
    public void onIfAccepted(
            LiquidationContext ctx,
            long ifCovered
    ) {
        if (ctx.state != LiquidationState.WAIT_IF_JUDGEMENT) {
            return;
        }

//        ctx.ifCovered = ifCovered;
        ctx.state = LiquidationState.CLOSED;
    }

    // ME：IF-J 不接
    public void onIfRejected(
            SymbolPositionRecord pos,
            LiquidationContext ctx
    ) {
        if (ctx.state != LiquidationState.WAIT_IF_JUDGEMENT) {
            return;
        }

        ctx.state = LiquidationState.WAIT_ADL_EXECUTION;
//        exchangeApi.emitAdl(pos, ctx.residualLoss);
    }

    private void onADLDone(SymbolPositionRecord pos) {
        LiquidationContext ctx = pos.liquidationCtx;
        assert ctx.state == LiquidationState.WAIT_ADL_EXECUTION;
        ctx.state = LiquidationState.CLOSED;
    }

}