/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.core.common;


import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

import java.util.Objects;

@Slf4j
@NoArgsConstructor
public final class SymbolPositionRecord implements WriteBytesMarshallable, StateHash {

    public long uid;

    public int symbol;
    public int currency;

    // open positions state (for margin trades only)
    public PositionDirection direction = PositionDirection.EMPTY;
    public long openVolume = 0;
    public long openPriceSum = 0; //
    public long profit = 0;

    // pending orders total size
    // increment before sending order to matching engine
    // decrement after receiving trade confirmation from matching engine
    public long pendingSellSize = 0;
    public long pendingBuySize = 0;

    private int leverage = 1; // 用户自选杠杆，默认 1 倍

    public void initialize(long uid, int symbol, int currency, int leverage) {
        this.uid = uid;

        this.symbol = symbol;
        this.currency = currency;

        this.direction = PositionDirection.EMPTY;
        this.openVolume = 0;
        this.openPriceSum = 0;
        this.profit = 0;

        this.pendingSellSize = 0;
        this.pendingBuySize = 0;

        updateLeverage(leverage);
    }

    public void updateLeverage(int leverage) {
        this.leverage = leverage == 0 ? 1 : leverage; // 用户自选杠杆，默认 1 倍
    }

    public boolean isSameLeverage(int leverage) {
        return this.leverage == (leverage == 0 ? 1 : leverage);
    }

    public SymbolPositionRecord(long uid, BytesIn bytes) {
        this.uid = uid;

        this.symbol = bytes.readInt();
        this.currency = bytes.readInt();

        this.direction = PositionDirection.of(bytes.readByte());
        this.openVolume = bytes.readLong();
        this.openPriceSum = bytes.readLong();
        this.profit = bytes.readLong();

        this.pendingSellSize = bytes.readLong();
        this.pendingBuySize = bytes.readLong();

        updateLeverage(bytes.readInt());
    }


    /**
     * Check if position is empty (no pending orders, no open trades) - can remove it from hashmap
     *
     * @return true if position is empty (no pending orders, no open trades)
     */
    public boolean isEmpty() {
        return direction == PositionDirection.EMPTY
                && pendingSellSize == 0
                && pendingBuySize == 0;
    }

    public void pendingHold(OrderAction orderAction, long size) {
        if (orderAction == OrderAction.ASK) {
            pendingSellSize += size;
        } else {
            pendingBuySize += size;
        }
    }

    public long pendingRelease(OrderAction orderAction, long size) {
        long released;
        if (orderAction == OrderAction.ASK) {
            released = Math.min(pendingSellSize, size);
            pendingSellSize -= released;
        } else {
            released = Math.min(pendingBuySize, size);
            pendingBuySize -= released;
        }
        return released;
    }

    /**
     * P&L = (持仓方向 *（当前价格 - 开仓均价）) × 持仓张数
     * 返回纯利润，不包含冻结保证金。
     */
    public long estimateProfit(final CoreSymbolSpecification spec, final RiskEngine.LastPriceCacheRecord lastPriceCacheRecord) {
        switch (direction) {
            case EMPTY:
                return profit;
            case LONG:
                return profit + ((lastPriceCacheRecord != null && lastPriceCacheRecord.bidPrice != 0)
                        ? (openVolume * lastPriceCacheRecord.bidPrice - openPriceSum)
                        : CoreArithmeticUtils.ceilDivide(spec.marginBuy, leverage) * openVolume); // unknown price - no liquidity - require extra margin
            case SHORT:
                return profit + ((lastPriceCacheRecord != null && lastPriceCacheRecord.askPrice != Long.MAX_VALUE)
                        ? (openPriceSum - openVolume * lastPriceCacheRecord.askPrice)
                        : CoreArithmeticUtils.ceilDivide(spec.marginSell, leverage) * openVolume); // unknown price - no liquidity - require extra margin
            default:
                throw new IllegalStateException();
        }
    }
    
    /**
     * 计算未实现盈亏，基于标记价格。
     * - 多头：(markPrice - 开仓价格) * 数量 + 已实现盈亏。
     * - 空头：(开仓价格 - markPrice) * 数量 + 已实现盈亏。
     * - 若 markPrice 无效（0），使用初始保证金作为保守估计。
     */
    public long liquidateEstimateProfit(final CoreSymbolSpecification spec, final RiskEngine.LastPriceCacheRecord lastPriceCacheRecord) {
        switch (direction) {
            case EMPTY:
                return profit;
            case LONG:
                return profit + ((lastPriceCacheRecord != null && lastPriceCacheRecord.markPrice != 0)
                    ? (openVolume * lastPriceCacheRecord.markPrice - openPriceSum)
                    : CoreArithmeticUtils.ceilDivide(spec.marginBuy, leverage) * openVolume);
            case SHORT:
                return profit + ((lastPriceCacheRecord != null && lastPriceCacheRecord.markPrice != 0)
                    ? (openPriceSum - openVolume * lastPriceCacheRecord.markPrice)
                    : CoreArithmeticUtils.ceilDivide(spec.marginSell, leverage) * openVolume);
            default:
                throw new IllegalStateException();
        }
    }

    /**
     * 【强平风险评估用】只计算持仓*维持保证金，不看挂单。
     * @param spec
     * @return
     */
    public long calculateMaintenanceMargin(CoreSymbolSpecification spec) {
        return direction == PositionDirection.EMPTY ? 0 : openVolume * CoreArithmeticUtils.ceilDivide(spec.maintenanceMargin, leverage);
    }
    
    /**
     * Calculate required margin based on specification and current position/orders
     *
     * 当前这笔合约持仓（含挂单）所需要冻结的保证金总额
     * 【下单前风控用】不考虑维持保证金，是因为它已经用openVolume*初始保证金计算了，而初始保证金本来就大于维持保证金
     *
     * @param spec core symbol specification
     * @return required margin
     */
    public long calculateRequiredMarginForFutures(CoreSymbolSpecification spec) {
        return calculateRequiredMarginForFutures(spec, leverage);
    }

    public long calculateRequiredMarginForFutures(CoreSymbolSpecification spec, int leverage) {
        final long specMarginBuy = CoreArithmeticUtils.ceilDivide(spec.marginBuy, leverage);
        final long specMarginSell = CoreArithmeticUtils.ceilDivide(spec.marginSell, leverage);

        final long signedPosition = openVolume * direction.getMultiplier();
        final long currentRiskBuySize = pendingBuySize + signedPosition;
        final long currentRiskSellSize = pendingSellSize - signedPosition;

        try {
            final long marginBuy = currentRiskBuySize >= 0 ? Math.multiplyExact(specMarginBuy, currentRiskBuySize) : 0;
            final long marginSell = currentRiskSellSize >= 0 ? Math.multiplyExact(specMarginSell, currentRiskSellSize) : 0;
            return Math.max(marginBuy, marginSell);
        } catch (ArithmeticException e) {
            log.error("Overflow in calculateRequiredMarginForFutures: uid={} symbol={} openVolume={} direction={} pendingBuySize={} pendingSellSize={}",
                    uid, symbol, openVolume, direction, pendingBuySize, pendingSellSize, e);
            throw new IllegalStateException("Margin calculation overflow for symbol " + symbol, e);
        }
    }

    /**
     * Calculate required margin based on specification and current position/orders
     * considering extra size added to current position (or outstanding orders)
     *
     * @param spec   symbols specification
     * @param action order action
     * @param size   order size
     * @return -1 if order will reduce current exposure (no additional margin required), otherwise full margin for symbol position if order placed/executed
     */
    public long calculateRequiredMarginForOrder(final CoreSymbolSpecification spec, final OrderAction action, final long size) {
        final long specMarginBuy = CoreArithmeticUtils.ceilDivide(spec.marginBuy, leverage);
        final long specMarginSell = CoreArithmeticUtils.ceilDivide(spec.marginSell, leverage);

        final long signedPosition = openVolume * direction.getMultiplier();
        final long currentRiskBuySize = pendingBuySize + signedPosition;
        final long currentRiskSellSize = pendingSellSize - signedPosition;

        long marginBuy = specMarginBuy * currentRiskBuySize;
        long marginSell = specMarginSell * currentRiskSellSize;
        // either marginBuy or marginSell can be negative (because of signedPosition), but not both of them
        final long currentMargin = Math.max(marginBuy, marginSell);

        if (action == OrderAction.BID) {
            marginBuy += specMarginBuy * size;
        } else {
            marginSell += specMarginSell * size;
        }

        // marginBuy or marginSell can be negative, but not both of them
        final long newMargin = Math.max(marginBuy, marginSell);

        return (newMargin <= currentMargin) ? -1 : newMargin;
    }

    /**
     * Update position for one user
     * 1. Un-hold pending size
     * 2. Reduce opposite position accordingly (if exists)
     * 3. Increase forward position accordingly (if size left in the trading event)
     *
     * @param action order action
     * @param size   order size
     * @param price  order price
     * @return opened size
     */
    public long updatePositionForMarginTrade(OrderAction action, long size, long price) {

        // 1. Un-hold pending size
        pendingRelease(action, size);

        // 2. Reduce opposite position accordingly (if exists)
        final long sizeToOpen = closeCurrentPositionFutures(action, size, price);

        // 3. Increase forward position accordingly (if size left in the trading event)
        if (sizeToOpen > 0) {
            openPositionMargin(action, sizeToOpen, price);
        }
        return sizeToOpen;
    }

    public long closeCurrentPositionFutures(final OrderAction action, final long tradeSize, final long tradePrice) {

        // log.debug("{} {} {} {} cur:{}-{} profit={}", uid, action, tradeSize, tradePrice, position, totalSize, profit);

        if (direction == PositionDirection.EMPTY || direction == PositionDirection.of(action)) {
            // nothing to close
            return tradeSize;
        }

        if (openVolume > tradeSize) {
            // current position is bigger than trade size - just reduce position accordingly, don't fix profit
            openVolume -= tradeSize;
            openPriceSum -= tradeSize * tradePrice;
            return 0;
        }

        // current position smaller than trade size, can close completely and calculate profit
        profit += (openVolume * tradePrice - openPriceSum) * direction.getMultiplier();
        openPriceSum = 0;
        direction = PositionDirection.EMPTY;
        final long sizeToOpen = tradeSize - openVolume;
        openVolume = 0;

        // validateInternalState();

        return sizeToOpen;
    }

    public void openPositionMargin(OrderAction action, long sizeToOpen, long tradePrice) {
        openVolume += sizeToOpen;
        openPriceSum += tradePrice * sizeToOpen;
        direction = PositionDirection.of(action);

        // validateInternalState();
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(symbol);
        bytes.writeInt(currency);
        bytes.writeByte((byte) direction.getMultiplier());
        bytes.writeLong(openVolume);
        bytes.writeLong(openPriceSum);
        bytes.writeLong(profit);
        bytes.writeLong(pendingSellSize);
        bytes.writeLong(pendingBuySize);
        bytes.writeInt(leverage);
    }

    public void reset() {

        // log.debug("records: {}, Pending B{} S{} total size: {}", records.size(), pendingBuySize, pendingSellSize, totalSize);

        pendingBuySize = 0;
        pendingSellSize = 0;

        openVolume = 0;
        openPriceSum = 0;
        direction = PositionDirection.EMPTY;

        updateLeverage(0);
    }

    public void validateInternalState() {
        if (direction == PositionDirection.EMPTY && (openVolume != 0 || openPriceSum != 0)) {
            log.error("uid {} : position:{} totalSize:{} openPriceSum:{}", uid, direction, openVolume, openPriceSum);
            throw new IllegalStateException();
        }
        if (direction != PositionDirection.EMPTY && (openVolume <= 0 || openPriceSum <= 0)) {
            log.error("uid {} : position:{} totalSize:{} openPriceSum:{}", uid, direction, openVolume, openPriceSum);
            throw new IllegalStateException();
        }

        if (pendingSellSize < 0 || pendingBuySize < 0) {
            log.error("uid {} : pendingSellSize:{} pendingBuySize:{}", uid, pendingSellSize, pendingBuySize);
            throw new IllegalStateException();
        }
    }

    @Override
    public int stateHash() {
        return Objects.hash(symbol, currency, direction.getMultiplier(), openVolume, openPriceSum, profit, pendingSellSize, pendingBuySize);
    }

    @Override
    public String toString() {
        return "SPR{" +
                "u" + uid +
                " sym" + symbol +
                " cur" + currency +
                " pos" + direction +
                " Σv=" + openVolume +
                " Σp=" + openPriceSum +
                " pnl=" + profit +
                " pendingS=" + pendingSellSize +
                " pendingB=" + pendingBuySize +
                '}';
    }
}
