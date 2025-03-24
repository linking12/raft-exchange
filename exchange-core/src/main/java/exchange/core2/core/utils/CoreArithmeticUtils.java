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
        return size * (price * spec.quoteScaleK + spec.takerFee);
    }

    public static long calculateAmountBidReleaseCorrMaker(long size, long priceDiff, CoreSymbolSpecification spec) {
        return size * (priceDiff * spec.quoteScaleK + (spec.takerFee - spec.makerFee));
    }

    public static long calculateAmountBidTakerFeeForBudget(long size, long budgetInSteps, CoreSymbolSpecification spec) {

        return budgetInSteps * spec.quoteScaleK + size * spec.takerFee;
    }

    /**
     * 计算基于数量的吃单手续费。
     * 
     * @param size 交易数量（基础货币单位）
     * @param spec 交易对规格
     * @return 报价货币的手续费金额
     */
    public static long calculateTakerFee(long size, CoreSymbolSpecification spec) {
        if (size < 0 || spec.takerFee < 0) {
            log.warn("Invalid input for taker fee calculation: size={}, takerFee={}", size, spec.takerFee);
            throw new IllegalArgumentException("Size and takerFee must be non-negative");
        }
        if (size > Long.MAX_VALUE / spec.takerFee) {
            log.warn("Potential overflow in taker fee calculation: size={}, takerFee={}", size, spec.takerFee);
            throw new ArithmeticException("Taker fee calculation overflow");
        }
        return size * spec.takerFee;
    }

    /**
     * 计算基于数量的挂单手续费。
     * 
     * @param size 交易数量（基础货币单位）
     * @param spec 交易对规格
     * @return 报价货币的手续费金额
     */
    public static long calculateMakerFee(long size, CoreSymbolSpecification spec) {
        if (size < 0 || spec.makerFee < 0) {
            log.warn("Invalid input for maker fee calculation: size={}, makerFee={}", size, spec.makerFee);
            throw new IllegalArgumentException("Size and makerFee must be non-negative");
        }
        if (size > Long.MAX_VALUE / spec.makerFee) {
            log.warn("Potential overflow in maker fee calculation: size={}, makerFee={}", size, spec.makerFee);
            throw new ArithmeticException("Maker fee calculation overflow");
        }
        return size * spec.makerFee;
    }
}
