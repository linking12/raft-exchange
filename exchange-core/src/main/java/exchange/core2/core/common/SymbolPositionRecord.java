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


import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.Getter;
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
    public long openInitMarginSum = 0; //初始保证金总额
    public long openPriceSum = 0; //持仓总成本，openPriceSum/openVolume=平均持仓成本
    public long profit = 0; //已实现盈亏

    // pending orders total size
    // increment before sending order to matching engine
    // decrement after receiving trade confirmation from matching engine
    public long pendingSellSize = 0;
    public long pendingBuySize = 0;
    public long pendingSellAvgPrice = 0;
    public long pendingBuyAvgPrice = 0;

    @Getter
    private int leverage = 1; // 用户自选杠杆，默认 1 倍
    public MarginMode marginMode = MarginMode.ISOLATED; // 默认为逐仓
    public long extraMargin = 0; // 补充保证金，默认 0

    public void initialize(long uid, int symbol, int currency, int leverage, MarginMode marginMode) {
        this.uid = uid;

        this.symbol = symbol;
        this.currency = currency;

        this.direction = PositionDirection.EMPTY;
        this.openVolume = 0;
        this.openInitMarginSum = 0;
        this.openPriceSum = 0;
        this.profit = 0;

        this.pendingSellSize = 0;
        this.pendingBuySize = 0;

        updateLeverage(leverage);
        this.marginMode = marginMode == null ? MarginMode.ISOLATED : marginMode; // 默认为逐仓
        this.extraMargin = 0;
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
        this.openInitMarginSum = bytes.readLong();
        this.openPriceSum = bytes.readLong();
        this.profit = bytes.readLong();

        this.pendingSellSize = bytes.readLong();
        this.pendingBuySize = bytes.readLong();
        this.pendingSellAvgPrice = bytes.readLong();
        this.pendingBuyAvgPrice = bytes.readLong();

        updateLeverage(bytes.readInt());
        this.marginMode = MarginMode.values()[bytes.readInt()];
        this.extraMargin = bytes.readLong();
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

    public void pendingHold(OrderAction orderAction, long size, long price) {
        if (orderAction == OrderAction.ASK) {
            pendingSellAvgPrice = calcAvgPrice(pendingSellAvgPrice, pendingSellSize, price, size);
            pendingSellSize += size;
        } else {
            pendingBuyAvgPrice = calcAvgPrice(pendingBuyAvgPrice, pendingBuySize, price, size);
            pendingBuySize += size;
        }
    }

    private long calcAvgPrice(long currentAvg, long currentSize, long newPrice, long newSize) {
        long totalSize = currentSize + newSize;
        if (totalSize <= 0) return 0;
        return CoreArithmeticUtils.ceilDivide(currentAvg * currentSize + newPrice * newSize, totalSize);
    }

    public long pendingRelease(OrderAction orderAction, long size) {
        long released;
        if (orderAction == OrderAction.ASK) {
            released = Math.min(pendingSellSize, size);
            pendingSellSize -= released;
            if (pendingSellSize == 0) {
                pendingSellAvgPrice = 0;
            }
        } else {
            released = Math.min(pendingBuySize, size);
            pendingBuySize -= released;
            if (pendingBuySize == 0) {
                pendingBuyAvgPrice = 0;
            }
        }
        return released;
    }

    /**
     * P&L = 已实现盈亏 + (当前价 - 开仓均价) * 持仓量 = profit + 当前价 * 持仓量 - 名义价值
     * 返回纯利润，不包含冻结保证金。
     */
    public long estimateProfit(final LastPriceCacheRecord lastPriceCacheRecord) {
        switch (direction) {
            case EMPTY:
                return profit;
            case LONG:
                if (lastPriceCacheRecord != null && lastPriceCacheRecord.bidPrice != 0) {
                    return profit + (openVolume * lastPriceCacheRecord.bidPrice - openPriceSum);
                } else {
                    // fallback: 用开仓均价作为当前价，即浮盈为0
                    return profit;
                }
            case SHORT:
                if (lastPriceCacheRecord != null && lastPriceCacheRecord.askPrice != Long.MAX_VALUE) {
                    return profit + (openPriceSum - openVolume * lastPriceCacheRecord.askPrice);
                } else {
                    // fallback: 用开仓均价作为当前价，即浮盈为0
                    return profit;
                }
            default:
                throw new IllegalStateException();
        }
    }
    
    /**
     * 【强平风险评估用】计算未实现盈亏，基于标记价格。
     * - 多头：(markPrice - 开仓价格) * 数量
     * - 空头：(开仓价格 - markPrice) * 数量
     */
    public long estimateUnrealizedProfit(final LastPriceCacheRecord priceRecord) {
        return direction.getMultiplier() * (openVolume * priceRecord.markPrice - openPriceSum);
    }

    /**
     * 计算强平价格，基于标记价格。
     * 强平触发条件为：账户权益 <= 维持保证金
     * - 多仓（LONG）：equity = 总保证金 + (markPrice - entryPrice) * 仓位数量
     * - 空仓（SHORT）：equity = 总保证金 + (entryPrice - markPrice) * 仓位数量
     * 目标是求出触发强平时的 markPrice，即 P_liq。
     * 推导后统一公式为：
     * P_liq = (sign * (MM - totalMargin) + entryValue) / positionSize
     */
    public long estimateLiquidationPrice(CoreSymbolSpecification spec, LastPriceCacheRecord priceRecord) {
        if (openVolume == 0) {
            return 0; // 无持仓，不存在强平价
        }
        long notional = openVolume * priceRecord.markPrice;
        long maintenanceMargin = spec.calcMaintenanceMargin(notional);
        long totalMargin = openInitMarginSum + extraMargin;
        return (long) ((direction.getMultiplier() * (maintenanceMargin - totalMargin) + openPriceSum) * 1.0 / openVolume);
    }

    /**
     * 【强平风险评估用】只计算 当前持仓*标记价格*维持保证金率，不看pending部分。
     *
     * @param spec
     * @param priceRecord
     * @return
     */
    public long calculateMaintenanceMargin(CoreSymbolSpecification spec, LastPriceCacheRecord priceRecord) {
        if (direction == PositionDirection.EMPTY) {
            return 0;
        }
        long notional = openVolume * priceRecord.markPrice;
        return spec.calcMaintenanceMargin(notional);
    }
    
    /**
     * Calculate required margin based on specification and current position/orders
     *
     * 当前这笔合约持仓（含挂单）所需要冻结的保证金总额
     * 【下单前风控用】不考虑维持保证金，是因为它已经用初始保证金计算了，而初始保证金本来就大于维持保证金
     *
     * @param spec core symbol specification
     * @return required margin
     */
    public long calculateRequiredMarginForFutures(CoreSymbolSpecification spec) {
        return calculateRequiredMarginForFutures(spec, leverage);
    }

    /**
     * 开仓保证金 = markPrice × size × 开仓保证金比率 / 杠杆倍数
     *
     * 开仓时，累加：
     * initMarginSum += markPrice × size × initialMarginRate / leverage
     * 关仓时，按比例释放：
     * initMarginSum -= openInitMarginSum * size / totalOpenVolume;
     *
     * 资金占用估算：
     * 区分开仓部分与挂单部分。已开仓部分用initMarginSum，pending部分按挂单均价估算，pending部分的手续费也以挂单均价估算。
     *
     */
    public long calculateRequiredMarginForFutures(CoreSymbolSpecification spec, int leverage) {
        // 未成交挂单部分 pendingMargin = pendingSize × pendingAvgPrice × 初始保证金率 / 杠杆倍数
        final long pendingBuy = pendingBuySize * pendingBuyAvgPrice;
        final long pendingSell = pendingSellSize * pendingSellAvgPrice;
        // marginBuy or marginSell can be negative, but not both of them
        long pending = Math.max(pendingBuy, pendingSell);
        long pendingMargin = spec.calcInitMargin(pending, leverage);

        // 计算未成单的潜在手续费
        final long feeBuy = CoreArithmeticUtils.calculateTakerFee(pendingBuySize, pendingBuyAvgPrice, spec);
        final long feeSell = CoreArithmeticUtils.calculateTakerFee(pendingSellSize, pendingSellAvgPrice, spec);
        long fee = Math.max(feeBuy, feeSell);

        return openInitMarginSum + pendingMargin + fee;
    }

    /**
     * Calculate required margin based on specification and current position/orders
     * considering extra size added to current position (or outstanding orders)
     *
     * @param spec   symbols specification
     * @param action order action
     * @param extraNotional extra notional to be added
     * @return -1 if order will reduce current exposure (no additional margin required), otherwise full margin for symbol position if order placed/executed
     */
    public long calculateRequiredMarginForOrder(final CoreSymbolSpecification spec, final OrderAction action, final long extraNotional) {
        // 未成交挂单部分：用挂单均价
        final long pendingBuy = pendingBuySize * pendingBuyAvgPrice;
        final long pendingSell = pendingSellSize * pendingSellAvgPrice;
        long pending = Math.max(pendingBuy, pendingSell);
        long pendingMargin = spec.calcInitMargin(pending, leverage);

        long currentMargin = openInitMarginSum + pendingMargin;

        // 使用挂单价格作为新增风险敞口的基础
        long newPendingBuy = pendingBuy + (action == OrderAction.BID ? extraNotional : 0);
        long newPendingSell = pendingSell + (action == OrderAction.ASK ? extraNotional : 0);
        long newPending = Math.max(newPendingBuy, newPendingSell);
        long newPendingMargin = spec.calcInitMargin(newPending, leverage);

        long newMargin = openInitMarginSum + newPendingMargin;

        return (newMargin <= currentMargin) ? -1 : newMargin;
    }

    /**
     * 假设pending部分以及新下单的size都能开出来，估算仓位名义价值。
     */
    public long estimateNotionalForOrder(final OrderAction action, final long size, final long price) {
        long newPendingBuySize = action == OrderAction.BID ? pendingBuySize + size : pendingBuySize;
        long newPendingSellSize = action == OrderAction.ASK ? pendingSellSize + size : pendingSellSize;
        long estimatedSize = openVolume + Math.max(newPendingBuySize, newPendingSellSize);
        return estimatedSize * price;
    }

    public long calculatePendingFeeForOrder(final CoreSymbolSpecification spec, final OrderAction action, final long size, final long price) {
        long newPendingBuySize = action == OrderAction.BID ? pendingBuySize + size : pendingBuySize;
        long newPendingSellSize = action == OrderAction.ASK ? pendingSellSize + size : pendingSellSize;
        long newPendingBuyAvgPrice = action == OrderAction.BID ? calcAvgPrice(pendingBuyAvgPrice, pendingBuySize, price, size) : pendingBuyAvgPrice;
        long newPendingSellAvgPrice = action == OrderAction.ASK ? calcAvgPrice(pendingSellAvgPrice, pendingSellSize, price, size) : pendingSellAvgPrice;

        long feePendingBuy = CoreArithmeticUtils.calculateTakerFee(newPendingBuySize, newPendingBuyAvgPrice, spec);
        long feePendingSell = CoreArithmeticUtils.calculateTakerFee(newPendingSellSize, newPendingSellAvgPrice, spec);
        return Math.max(feePendingBuy, feePendingSell);
    }

    public long closeCurrentPositionFutures(final OrderAction action, final long tradeSize, final long tradePrice) {

        // log.debug("{} {} {} {} cur:{}-{} profit={}", uid, action, tradeSize, tradePrice, position, totalSize, profit);

        if (direction == PositionDirection.EMPTY || direction == PositionDirection.of(action)) {
            // nothing to close
            return tradeSize;
        }

        if (openVolume > tradeSize) {
            // current position is bigger than trade size - just reduce position accordingly, don't fix profit
            openInitMarginSum -= openInitMarginSum * tradeSize / openVolume; // 按比例释放
            openVolume -= tradeSize;
            openPriceSum -= tradeSize * tradePrice;
            return 0;
        }

        // current position smaller than trade size, can close completely and calculate profit
        profit += (openVolume * tradePrice - openPriceSum) * direction.getMultiplier();
        openInitMarginSum = 0;
        openPriceSum = 0;
        direction = PositionDirection.EMPTY;
        final long sizeToOpen = tradeSize - openVolume;
        openVolume = 0;

        // validateInternalState();

        return sizeToOpen;
    }

    public void openPositionMargin(OrderAction action, long sizeToOpen, long tradePrice, CoreSymbolSpecification spec, LastPriceCacheRecord record) {
        openVolume += sizeToOpen;
        openInitMarginSum += spec.calcInitMargin(record.markPrice * sizeToOpen, leverage);
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
        bytes.writeLong(openInitMarginSum);
        bytes.writeLong(openPriceSum);
        bytes.writeLong(profit);
        bytes.writeLong(pendingSellSize);
        bytes.writeLong(pendingBuySize);
        bytes.writeLong(pendingSellAvgPrice);
        bytes.writeLong(pendingBuyAvgPrice);
        bytes.writeInt(leverage);
        bytes.writeInt(marginMode.ordinal());
        bytes.writeLong(extraMargin);
    }

    public void reset() {

        // log.debug("records: {}, Pending B{} S{} total size: {}", records.size(), pendingBuySize, pendingSellSize, totalSize);

        pendingBuySize = 0;
        pendingSellSize = 0;
        pendingBuyAvgPrice = 0;
        pendingSellAvgPrice = 0;

        openVolume = 0;
        openInitMarginSum = 0;
        openPriceSum = 0;
        direction = PositionDirection.EMPTY;

        updateLeverage(0);
        marginMode = MarginMode.ISOLATED;
        extraMargin = 0;
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
        return Objects.hash(symbol, currency, direction.getMultiplier(), openVolume, openInitMarginSum, openPriceSum, profit,
            pendingSellSize, pendingBuySize, pendingSellAvgPrice, pendingBuyAvgPrice, leverage, marginMode, extraMargin);
    }

    @Override
    public String toString() {
        return "SPR{" +
                "u" + uid +
                " sym" + symbol +
                " cur" + currency +
                " pos" + direction +
                " Σv=" + openVolume +
                " ΣinitM=" + openInitMarginSum +
                " Σp=" + openPriceSum +
                " pnl=" + profit +
                " pendingS=" + pendingSellSize +
                " pendingB=" + pendingBuySize +
                " pendingSP=" + pendingSellAvgPrice +
                " pendingBP=" + pendingBuyAvgPrice +
                " lev=" + leverage +
                " mode=" + marginMode +
                " exM=" + extraMargin +
                '}';
    }
}
