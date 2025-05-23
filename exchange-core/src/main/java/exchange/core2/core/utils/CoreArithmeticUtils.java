/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package exchange.core2.core.utils;

import exchange.core2.core.common.CoreSymbolSpecification;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class CoreArithmeticUtils {

    public static long calculateAmountAsk(long size, CoreSymbolSpecification spec) {
        return size * spec.baseScaleK;
    }

    public static long calculateAmountBid(long size, long price, CoreSymbolSpecification spec) {
        return size * (price * spec.quoteScaleK);
    }

    public static long calculateAmountBidTakerFee(long size, long price, CoreSymbolSpecification spec) {
        long tradeAmount = size * price * spec.quoteScaleK;
        long fee = spec.isFixedFee()
                ? size * spec.takerFee
                : ceilDivide(tradeAmount * spec.takerFee, spec.feeScaleK);
        return tradeAmount + fee;
    }

    public static void logAmountBidTakerFee(long result, long size, long price, CoreSymbolSpecification spec) {
        if (spec.isFixedFee()) {
            log.debug("hold amount buy {} = {} * ({} * {} + {})",
                    result, size, price, spec.quoteScaleK, spec.takerFee);
        } else {
            log.debug("hold amount buy {} = {} * {} * {} * (1 + {})",
                    result, size, price, spec.quoteScaleK, ceilDivide(spec.takerFee, spec.feeScaleK));
        }
    }

    /**
     * 计算订单成交后，返还给Maker的超额冻结资金
     * <br>
     * 假设taker费 固定2｜浮动0.1；maker费 固定1｜浮动0.01
     * <br>
     * 下单（taker）5块，100手，扣500；手续费 固定 2 * 100 = 200｜浮动 0.1 * 500 = 50
     * <br>
     * 最终 4块和别人成单（maker），100手，交易额400；手续费 固定 1 * 100 = 100｜浮动 0.01 * 400 = 4
     * <br>
     * 交易部分要退还 (5 - 4) * 100 = 100
     * <br>
     * 手续费退还 固定 100 * (2 - 1) = 100 ｜ 浮动 5 * 100 * 0.1 - 4 * 100 * 0.01 = 46
     * <br>
     *
     * @param size            成交手数
     * @param bidderHoldPrice 下单时的冻结参考价格
     * @param price           实际成交价格
     * @param spec            币种配置
     * @return 应返还的资金（本金差额 + 手续费差额）
     */
    public static long calculateAmountBidReleaseCorrMaker(long size, long bidderHoldPrice, long price, CoreSymbolSpecification spec) {
        long tradeAmountDiff = size * (bidderHoldPrice - price) * spec.quoteScaleK;
        long feeDiff = spec.isFixedFee()
                ? size * (spec.takerFee - spec.makerFee)
                : ceilDivide(bidderHoldPrice * size * spec.takerFee - price * size * spec.makerFee, spec.feeScaleK);
        return tradeAmountDiff + feeDiff;
    }

    public static long calculateAmountBidTakerFeeForBudget(long size, long budgetInSteps, CoreSymbolSpecification spec) {
        long budgetAmount = budgetInSteps * spec.quoteScaleK;
        long fee = spec.isFixedFee()
                ? size * spec.takerFee
                : ceilDivide(budgetAmount * spec.takerFee, spec.feeScaleK);
        return budgetAmount + fee;
    }

    public static void logAmountBidTakerFeeForBudget(long result, long size, long budgetInSteps, CoreSymbolSpecification spec) {
        if (spec.isFixedFee()) {
            log.debug("hold amount budget buy {} = {} * {} + {} * {}",
                    result, budgetInSteps, spec.quoteScaleK, size, spec.takerFee);
        } else {
            log.debug("hold amount budget buy {} = {} * {} + {} * {} * {}",
                    result, budgetInSteps, spec.quoteScaleK, budgetInSteps, spec.quoteScaleK, spec.takerFee / spec.feeScaleK);
        }
    }

    public static boolean isAskPriceTooLow(long price, CoreSymbolSpecification spec) {
        if (spec.isFixedFee()) {
            return price * spec.quoteScaleK < spec.takerFee;
        } else {
            // 假设只成交1手，也要保证收到手续费，才能下单
            return price * spec.quoteScaleK * spec.takerFee < spec.feeScaleK;
        }
    }

    public static long calculateTakerFee(long size, long price, CoreSymbolSpecification spec) {
        if (spec.isFixedFee()) {
            return size * spec.takerFee;
        } else {
            long tradeAmount = size * price * spec.quoteScaleK;
            return ceilDivide(tradeAmount * spec.takerFee, spec.feeScaleK);
        }
    }

    public static long calculateMakerFee(long size, long price, CoreSymbolSpecification spec) {
        if (spec.isFixedFee()) {
            return size * spec.makerFee;
        } else {
            long tradeAmount = size * price * spec.quoteScaleK;
            return ceilDivide(tradeAmount * spec.makerFee, spec.feeScaleK);
        }
    }

    /**
     * 计算强平数量
     * 找一个x，满足：x × price ≥ deficit + x × taker_fee
     *              x ≥ deficit / (price - taker_fee)
     */
    public static long calculateSizeToLiquidate(long deficit, long price) {
        long takerFeePerSize = 0; // 单边收费，减仓不收手续费
        return ceilDivide(deficit, price - takerFeePerSize);
    }

    /**
     * 向上取整计算整数除法，防止出现 x / y = 0
     * ceil(x / y) = (x + y - 1) / y
     * 性能比Math.ceil好，因为不引入浮点数计算
     */
    public static long ceilDivide(long dividend, long divisor) {
        return (dividend + divisor -1 ) / divisor;
    }
}
