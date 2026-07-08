package exchange.core2.core.processors;

import org.eclipse.collections.api.iterator.LongIterator;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.FundingPaymentAndRecvNotional;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.orderbook.OrderBookEventsHelper;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class FundingFeeCommandProcessor extends TwoStepCommandProcessor {

    public FundingFeeCommandProcessor(RiskEngine riskEngine) {
        super(null, riskEngine);
    }

    public FundingFeeCommandProcessor(OrderBookEventsHelper eventsHelper) {
        super(eventsHelper, null);
    }

    @Override
    public void collectInput(OrderCommand cmd) {
        super.collectInput(cmd);
        final int symbol = cmd.symbol;
        if (cmd.size <= 0) {
            cmd.resultCode = CommandResultCode.RISK_INVALID_AMOUNT;
            return;
        }
        final LastPriceCacheRecord priceRecord = riskEngine.getLastPriceCache().get(symbol);
        if (priceRecord == null) {
            cmd.resultCode = CommandResultCode.RISK_MARKPRICE_NOT_AVAILABLE;
            return;
        }
        final long markPrice = priceRecord.markPrice;
        int shardId = riskEngine.getShardId();
        FundingPaymentAndRecvNotional shardData = cmd.fundingPaymentAndRecvNotionalByShard[shardId];
        riskEngine.getUserProfileService().getUserProfiles().forEachValue(userProfile -> {
            if (userProfile.userStatus != UserStatus.ACTIVE) {
                return;
            }
            userProfile.processPositionRecord(symbol, position -> {
                if (position.openVolume == 0) {
                    return;
                }
                long notional = Math.multiplyExact(position.openVolume, markPrice);
                if (position.direction.isSameAsAction(cmd.action)) {
                    long fundingFee = CoreArithmeticUtils.truncMulDiv(notional, cmd.price, cmd.size);
                    if (fundingFee > 0) {
                        shardData.payerAmounts.put(userProfile.uid, fundingFee);
                    }
                } else {
                    shardData.receiverNotionals.put(userProfile.uid, notional);
                }
            });
        });
    }

    @Override
    protected void buildMatcherEvents(OrderCommand cmd) {
        FundingPaymentAndRecvNotional[] shardsData = cmd.fundingPaymentAndRecvNotionalByShard;
        final int numShards = shardsData.length;
        long totalPayAmount = 0;
        long totalRecvNotional = 0;
        for (FundingPaymentAndRecvNotional perShard : shardsData) {
            totalPayAmount += perShard.payerAmounts.sum();
            totalRecvNotional += perShard.receiverNotionals.sum();
        }
        if (totalPayAmount == 0 || totalRecvNotional == 0) {
            cmd.matcherEvent = null;
            return;
        }

        long[] shardRecvAmount = new long[numShards];
        long distributed = 0;
        for (int shardId = 0; shardId < numShards; shardId++) {
            long notional = shardsData[shardId].receiverNotionals.sum();
            if (notional == 0)
                continue;
            long amount = CoreArithmeticUtils.truncMulDiv(totalPayAmount, notional, totalRecvNotional);
            shardRecvAmount[shardId] = amount;
            distributed += amount;
        }
        long remainder = totalPayAmount - distributed;
        for (int shardId = 0; remainder > 0 && shardId < numShards; shardId++) {
            if (!shardsData[shardId].receiverNotionals.isEmpty()) {
                shardRecvAmount[shardId]++;
                remainder--;
            }
        }
        if (remainder != 0) {
            log.error("funding remainder not fully distributed: remainder={} symbol={}", remainder, cmd.symbol);
        }

        MatcherTradeEvent head = null;
        MatcherTradeEvent tail = null;
        for (int shardId = 0; shardId < numShards; shardId++) {
            long amount = shardRecvAmount[shardId];
            boolean hasPayers = !shardsData[shardId].payerAmounts.isEmpty();
            if (amount <= 0 && !hasPayers) {
                continue;
            }
            MatcherTradeEvent ev = eventsHelper.newMatcherEvent();
            ev.eventType = MatcherEventType.FUNDING_EVENT;
            ev.price = amount;
            ev.matchedOrderUid = shardId;
            if (head == null) {
                head = ev;
            } else {
                tail.nextEvent = ev;
            }
            tail = ev;
        }
        cmd.matcherEvent = head;
    }

    @Override
    public void applyEvent(OrderCommand cmd, MatcherTradeEvent ev, CoreSymbolSpecification spec,
        CoreCurrencySpecification currencySpec) {
        super.applyEvent(cmd, ev, spec, currencySpec);
        if (ev.eventType != MatcherEventType.FUNDING_EVENT) {
            return;
        }
        int shardId = riskEngine.getShardId();
        if (ev.matchedOrderUid != shardId) {
            return;
        }
        final int symbol = cmd.symbol;
        final long shardRecvAmount = ev.price;
        final FundingPaymentAndRecvNotional shardData = cmd.fundingPaymentAndRecvNotionalByShard[shardId];
        final LongLongHashMap payerAmounts = shardData.payerAmounts;
        final LongLongHashMap receiverNotionals = shardData.receiverNotionals;

        payerAmounts.forEachKeyValue((uid, fee) -> settleFundingFee(cmd, symbol, uid, fee, true, spec, currencySpec));

        if (shardRecvAmount <= 0 || receiverNotionals.isEmpty()) {
            return;
        }
        final long shardRecvNotional = receiverNotionals.sum();
        LongLongHashMap receiverFees = new LongLongHashMap(receiverNotionals.size());
        receiverNotionals.forEachKeyValue((uid, notional) -> receiverFees.put(uid,
            CoreArithmeticUtils.truncMulDiv(shardRecvAmount, notional, shardRecvNotional)));
        long remain = shardRecvAmount - receiverFees.sum();
        if (remain > 0) {
            LongIterator it = receiverNotionals.keySet().longIterator();
            while (it.hasNext() && remain > 0) {
                receiverFees.addToValue(it.next(), 1);
                remain--;
            }
        }
        receiverFees.forEachKeyValue((uid, fee) -> {
            if (fee == 0)
                return;
            settleFundingFee(cmd, symbol, uid, fee, false, spec, currencySpec);
        });
    }

    private void settleFundingFee(OrderCommand cmd, int symbol, long uid, long fee, boolean isPayer,
        CoreSymbolSpecification spec, CoreCurrencySpecification currencySpec) {
        UserProfile user = riskEngine.getUserProfileService().getUserProfile(uid);
        if (user == null || user.userStatus != UserStatus.ACTIVE) {
            return;
        }
        final OrderAction positionSide = isPayer ? cmd.action : cmd.action.opposite();
        final long signedFee = isPayer ? -fee : fee;
        SymbolPositionRecord position = user.positions.get(symbol);
        if (position == null || !position.direction.isSameAsAction(positionSide)) {
            position = user.positions.get(-symbol);
        }
        boolean hasActivePosition =
            position != null && position.openVolume > 0 && position.direction.isSameAsAction(positionSide);
        if (hasActivePosition) {
            position.profit += signedFee;
            long balance = user.accounts.get(position.currency);
            long locked = riskEngine.calculateLocked(user, position.currency);
            riskEngine.getEventsHelper().sendFundingFeeEvent(cmd, position, balance - locked, locked);
        } else {
            long scaledFee = CoreArithmeticUtils.sizePriceToCurrencyScale(signedFee, spec, currencySpec);
            long balance = user.accounts.addToValue(spec.quoteCurrency, scaledFee);
            long locked = riskEngine.calculateLocked(user, spec.quoteCurrency);
            riskEngine.getEventsHelper().sendFundingFeeEventForClosedPosition(cmd, uid, symbol, spec.quoteCurrency,
                balance - locked, locked);
        }
    }
}
