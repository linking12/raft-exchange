package exchange.core2.core.processors;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.orderbook.OrderBookEventsHelper;

/**
 * INTERNAL_TRANSFER —— 用户间同币种原子转账（Branch B）。
 * 字段映射：uid=from / size=to / symbol=currency / price=amount / orderId=transactionId。
 *
 * <p>from 与 to 可跨 shard，三阶段（同 {@link ResetFeeCommandProcessor} 骨架）：
 * R1 {@link #collectInput}（from-shard 校验 + 扣款 + 幂等，置 VALID 放行）→ ME {@link #buildMatcherEvents}（产事件）
 * → R2 {@link #applyEvent}（to-shard 入账，未知 to 自动建 SUSPENDED 档）。
 *
 * <p>唯一会失败的 from-NSF 压在 R1、只依赖本地状态；扣款按 seq 定序，无双花、无需冻结。
 * 守恒中性（accounts 桶内 from−x/to+x，不碰 adjustments），幂等锚在 from。
 */
public final class InternalTransferProcessor extends TwoStepCommandProcessor {

    /** R1/R2 实例（RiskEngine 每 shard 一份）。 */
    public InternalTransferProcessor(RiskEngine riskEngine) {
        super(null, riskEngine);
    }

    /** ME-stage 实例（MatchingEngineRouter 持）。 */
    public InternalTransferProcessor(OrderBookEventsHelper eventsHelper) {
        super(eventsHelper, null);
    }

    /** R1：仅 from-shard 校验 + 扣款 + 幂等。 */
    @Override
    public void collectInput(OrderCommand cmd) {
        super.collectInput(cmd);
        if (!riskEngine.uidForThisHandler(cmd.uid)) {
            return;
        }
        final long toUid = cmd.size;
        final int currency = cmd.symbol;
        final long amount = cmd.price;
        if (cmd.uid == toUid) {
            cmd.resultCode = CommandResultCode.INTERNAL_TRANSFER_INVALID_SELF;
            return;
        }
        if (amount <= 0) {
            cmd.resultCode = CommandResultCode.RISK_INVALID_AMOUNT;
            return;
        }
        final UserProfile from = riskEngine.getUserProfileService().getUserProfile(cmd.uid);
        if (from == null) {
            cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
            return;
        }
        // NSF 与提现同口径；不 claim，可改额同 id 重试
        if (riskEngine.withdrawableBalance(from, currency) < amount) {
            cmd.resultCode = CommandResultCode.RISK_NSF;
            return;
        }
        if (!from.processedTransactionIds.tryClaim(cmd.orderId, cmd.timestamp)) {
            cmd.resultCode = CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
            return;
        }
        emitSnapshot(cmd, cmd.uid, from, currency, from.accounts.addToValue(currency, -amount));
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }

    /** ME：把 to / currency / amount 打进一条事件。 */
    @Override
    protected void buildMatcherEvents(OrderCommand cmd) {
        final MatcherTradeEvent ev = eventsHelper.newMatcherEvent();
        ev.eventType = MatcherEventType.INTERNAL_TRANSFER_EVENT;
        ev.matchedOrderUid = cmd.size; // to
        ev.price = cmd.symbol;         // currency
        ev.size = cmd.price;           // amount
        ev.nextEvent = null;
        cmd.matcherEvent = ev;
    }

    /** R2：to-shard 入账（未知 to 自动建 SUSPENDED 档）。 */
    @Override
    public void applyEvent(OrderCommand cmd, MatcherTradeEvent ev, CoreSymbolSpecification spec,
        CoreCurrencySpecification currencySpec) {
        super.applyEvent(cmd, ev, spec, currencySpec);
        if (ev.eventType != MatcherEventType.INTERNAL_TRANSFER_EVENT) {
            return;
        }
        final long toUid = ev.matchedOrderUid;
        if (!riskEngine.uidForThisHandler(toUid)) {
            return;
        }
        final int currency = (int) ev.price;
        final UserProfile to = riskEngine.getUserProfileService().getUserProfileOrAddSuspended(toUid);
        emitSnapshot(cmd, toUid, to, currency, to.accounts.addToValue(currency, ev.size));
    }

    /** 发一条转账后余额快照事件（free = 新余额 − 冻结）。 */
    private void emitSnapshot(OrderCommand cmd, long uid, UserProfile up, int currency, long newBalance) {
        final long locked = riskEngine.calculateLocked(up, currency);
        riskEngine.getEventsHelper().sendInternalTransferEvent(cmd, uid, currency, newBalance - locked, locked);
    }
}
