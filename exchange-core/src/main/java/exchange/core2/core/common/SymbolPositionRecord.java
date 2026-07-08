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
package exchange.core2.core.common;

import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.liquidation.LiquidationContext;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.core.utils.HashingUtils;
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
    public long openInitMarginSum = 0; // 初始保证金总额
    public long openPriceSum = 0; // 持仓总成本，openPriceSum/openVolume=平均持仓成本
    public long profit = 0; // 已实现盈亏

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

    // 强平扫描时动态计算，不持久化，不序列化
    public long adlEligibility = 100; // adl可兑现性，默认 逐仓100 全仓0
    /**
     * ADL 冻结量：语义与下单 pending 类似。 R1 冻结本次 ADL 可用的仓位数量，R2 按 matcherEvent 执行并释放， 防止相邻 cmd 在 R1 阶段看到相同 openVolume 导致重复分配。
     **/
    public long pendingADLSize = 0;

    // ===== 强平流程上下文（仅在强平期间存在）=====
    // 进 snapshot 序列化：强平流程跨 leader failover / 节点重建必须能恢复，否则 follower 重建后流程会
    // 在 IF/ADL apply 时被 illegal-jump gate 静默 skip → state 永久发散。
    public LiquidationContext liquidationCtx;

    public void initialize(long uid, int symbol, int currency, OrderAction orderAction, int leverage,
        MarginMode marginMode) {
        this.uid = uid;

        this.symbol = symbol;
        this.currency = currency;

        this.direction = (orderAction == OrderAction.BID) ? PositionDirection.LONG : PositionDirection.SHORT;
        this.openVolume = 0;
        this.openInitMarginSum = 0;
        this.openPriceSum = 0;
        this.profit = 0;

        this.pendingSellSize = 0;
        this.pendingBuySize = 0;

        updateLeverage(leverage);
        this.marginMode = marginMode == null ? MarginMode.ISOLATED : marginMode; // 默认为逐仓
        this.extraMargin = 0;
        this.adlEligibility = marginMode == MarginMode.ISOLATED ? 100 : 0; // 默认逐仓100 全仓0
        this.pendingADLSize = 0;
        this.liquidationCtx = null;
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
        this.marginMode = MarginMode.of(bytes.readByte());
        this.extraMargin = bytes.readLong();
        this.adlEligibility = marginMode == MarginMode.ISOLATED ? 100 : 0; // 默认逐仓100 全仓0
        this.pendingADLSize = 0;
        this.liquidationCtx = bytes.readByte() == 1 ? new LiquidationContext(bytes) : null;
    }

    /**
     * Check if position is empty (no pending orders, no open trades) - can remove it from hashmap
     *
     * @return true if position is empty (no pending orders, no open trades)
     */
    public boolean isEmpty() {
        return openVolume == 0 && pendingSellSize == 0 && pendingBuySize == 0;
    }

    public void pendingHold(OrderAction orderAction, long size, long price) {
        if (orderAction == OrderAction.ASK) {
            pendingSellAvgPrice = calcAvgPrice(pendingSellAvgPrice, pendingSellSize, price, size);
            // 原: pendingSellSize += size;
            // 现: addExact 早抛，对齐 a813aef/22dad71 在 CoreArithmeticUtils 的策略
            pendingSellSize = Math.addExact(pendingSellSize, size);
        } else {
            pendingBuyAvgPrice = calcAvgPrice(pendingBuyAvgPrice, pendingBuySize, price, size);
            // 原: pendingBuySize += size;
            // 现: addExact 早抛
            pendingBuySize = Math.addExact(pendingBuySize, size);
        }
    }

    /**
     * BUDGET 单专用：直接用 budgetNotional（product scale 总预算）累积 pending notional， 维持「pendingSize × pendingAvgPrice ≈ pending
     * 部分总 notional」的下游契约。
     * <p>
     * 与 {@link #pendingHold} 的差异： limit 单需要把 {@code price × size} 算成 notional 再累加； BUDGET 单的 {@code cmd.price} 本身就是
     * notional（=total budget），无需再乘 size。
     * <p>
     * 采用 ceilDivide 向上取整，使估算的 avg 偏保守（margin pre-check 不会低估风险）。
     */
    public void pendingHoldBudget(OrderAction orderAction, long size, long budgetNotional) {
        if (orderAction == OrderAction.ASK) {
            // 原: long newSize = pendingSellSize + size;
            // 现: addExact
            long newSize = Math.addExact(pendingSellSize, size);
            if (newSize <= 0) {
                return;
            }
            // 原: pendingSellAvgPrice = ceilDivide(pendingSellAvgPrice * pendingSellSize + budgetNotional, newSize);
            // 现: 中间 product / sum 都走 *Exact / addExact，避免 silent wrap 让 ceilDivide 拿到脏 numerator
            long pendingNotional =
                Math.addExact(Math.multiplyExact(pendingSellAvgPrice, pendingSellSize), budgetNotional);
            pendingSellAvgPrice = CoreArithmeticUtils.ceilDivide(pendingNotional, newSize);
            pendingSellSize = newSize;
        } else {
            // 原: long newSize = pendingBuySize + size;
            // 现: addExact
            long newSize = Math.addExact(pendingBuySize, size);
            if (newSize <= 0) {
                return;
            }
            // 原: pendingBuyAvgPrice = ceilDivide(pendingBuyAvgPrice * pendingBuySize + budgetNotional, newSize);
            // 现: 拆出 multiplyExact + addExact
            long pendingNotional =
                Math.addExact(Math.multiplyExact(pendingBuyAvgPrice, pendingBuySize), budgetNotional);
            pendingBuyAvgPrice = CoreArithmeticUtils.ceilDivide(pendingNotional, newSize);
            pendingBuySize = newSize;
        }
    }

    private long calcAvgPrice(long currentAvg, long currentSize, long newPrice, long newSize) {
        // 原: long totalSize = currentSize + newSize;
        // 现: addExact
        long totalSize = Math.addExact(currentSize, newSize);
        if (totalSize <= 0)
            return 0;
        // 原: return ceilDivide(currentAvg * currentSize + newPrice * newSize, totalSize);
        // 现: 两个 product 各 multiplyExact，再 addExact 汇总
        long totalNotional =
            Math.addExact(Math.multiplyExact(currentAvg, currentSize), Math.multiplyExact(newPrice, newSize));
        return CoreArithmeticUtils.ceilDivide(totalNotional, totalSize);
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
     * 估算Pnl = profit(已实现部分) + 未实现盈亏(以markPrice估价)。
     */
    public long estimatePnl(final LastPriceCacheRecord lastPriceCacheRecord) {
        return profit + estimateUnrealizedProfit(lastPriceCacheRecord);
    }

    /**
     * 估算未实现盈亏，基于标记价格。 - 多头：(markPrice - 开仓价格) * 数量 - 空头：(开仓价格 - markPrice) * 数量
     */
    public long estimateUnrealizedProfit(final LastPriceCacheRecord priceRecord) {
        // 原: return direction.getMultiplier() * (openVolume * priceRecord.markPrice - openPriceSum);
        // 现: notional / 减法 / 乘符号 三步都早抛。LiquidationEngine 热路径调用，溢出直接传染到强平判定。
        long notional = Math.multiplyExact(openVolume, priceRecord.markPrice);
        long delta = Math.subtractExact(notional, openPriceSum);
        return Math.multiplyExact((long)direction.getMultiplier(), delta);
    }

    /**
     * 计算破产价格（即权益归零时对应的价格）—— Isolated 单仓路径。
     *
     * <p>基本方程（权益 = 0 时 mark 价即 BP）：
     * <pre>
     *   IM + Side × (BP − EP) × Q = 0
     *   ⇒ BP = EP − Side × IM / Q
     * </pre>
     * 其中 IM = openInitMarginSum + extraMargin，Q = openVolume，
     * EP = openPriceSum / Q，Side = LONG:+1 / SHORT:−1。
     *
     * <p>含 closing fee（BP 结算时还要扣掉 taker 平仓费）：
     * <pre>
     *   Fixed fee：  BP = EP − Side × (IM − takerFee × Q) / Q
     *   Dynamic fee：BP = feeScaleK × (openPriceSum − Side × IM) / (Q × (feeScaleK − takerFee))
     * </pre>
     * 具体拆分见 {@link #calcBankruptcyPriceFromMarginBase}。
     *
     * <p>单位说明：
     * <ul>
     * <li>{@code openInitMarginSum} — sizePriceScale (= baseScaleK × quoteScaleK)</li>
     * <li>{@code extraMargin} — sizePriceScale（与 openInitMarginSum 相同，由 {@code RiskEngine.MARGIN_ADJUSTMENT} 通过
     * {@code currencyToSizePriceScale} 转换写入）</li>
     * <li>{@code openPriceSum} — sizePriceScale</li>
     * <li>返回值 — quoteScaleK（价格单位）</li>
     * </ul>
     * 三者单位一致，加减运算才有意义；若 extraMargin 单位不一致，破产价将严重偏低。
     */
    public long calculateBankruptcyPrice(CoreSymbolSpecification spec) {
        long totalMargin = Math.addExact(openInitMarginSum, extraMargin);
        return calcBankruptcyPriceFromMarginBase(spec, totalMargin);
    }

    /**
     * 计算破产价格 —— Cross 账户级视角。caller（{@code LiquidationEngine}）按 MM 占比把账户级
     * marginBalance 分给每个 CROSS 仓，再换算成本方法所需的 EP-基础 {@code marginBase}：
     * <pre>
     *   allocated_i  = marginBalance × mm_i / Σ MM   （每仓按维持保证金公平分账户余额）
     *   marginBase_i = allocated_i − UPnL_i           （MP 基础 → EP 基础）
     * </pre>
     * 单仓时 mm_i/ΣMM = 1，marginBase = crossAvailable，跟旧 4 参重载单仓语义代数等价。
     *
     * <p>{@code marginBase} 与 openInitMarginSum 同 scale（{@code sizePriceScale = baseScaleK × quoteScaleK}）。
     * 该重载仅供 Cross 路径调用（caller 已在 marginMode 上分派）；Isolated 走单参重载。
     */
    public long calculateBankruptcyPrice(CoreSymbolSpecification spec, long marginBase) {
        return calcBankruptcyPriceFromMarginBase(spec, marginBase);
    }

    /**
     * 单仓 BP 公式共用核。BP 处账户权益归零，marginBase 同时承担价格波动亏损 + taker 平仓费 + 强平费。
     *
     * <p>推导（marginBase + sign × (BP − EP) × Q − fee(BP) = 0）：
     * <pre>
     *   固定 fee（{@code spec.isFixedFee()==true}）：fee(BP) = (takerFee + liquidationFee) × Q
     *     openPriceSum − sign × BP × Q = marginBase − (t + l) × Q
     *     ⇒ BP = (openPriceSum − sign × maxLoss) / Q,  maxLoss = marginBase − (t+l)×Q
     *
     *   动态 fee（rate = (t+l) / feeScaleK）：fee(BP) = Q × BP × (t+l) / feeScaleK
     *     LONG :  openPriceSum − BP × Q = marginBase − Q × BP × (t+l)/feeScaleK
     *             ⇒ BP × Q × (feeScaleK − t − l) = feeScaleK × (openPriceSum − marginBase)
     *     SHORT:  BP × Q − openPriceSum = marginBase − Q × BP × (t+l)/feeScaleK
     *             ⇒ BP × Q × (feeScaleK + t + l) = feeScaleK × (openPriceSum + marginBase)
     *   合并：denom 用 (feeScaleK − sign × (t+l))，numer 用 (openPriceSum − sign × marginBase)
     * </pre>
     * <b>SHORT 平仓（BID）价格越高 fee 越大——denom 必须用 sign × totalFee 才能覆盖两个方向。</b>
     * ceilMulDiv 走 128-bit slow path 兜住 feeScaleK 极大值的溢出，避免 BigInteger。
     */
    private long calcBankruptcyPriceFromMarginBase(CoreSymbolSpecification spec, long marginBase) {
        final long sign = direction.getMultiplier();
        final long totalFee = Math.addExact(spec.takerFee, spec.liquidationFee);
        if (spec.isFixedFee()) {
            final long maxLoss = Math.subtractExact(marginBase, Math.multiplyExact(totalFee, openVolume));
            final long numer = Math.subtractExact(openPriceSum, Math.multiplyExact(sign, maxLoss));
            return CoreArithmeticUtils.ceilDivide(numer, openVolume);
        } else {
            final long numer = Math.subtractExact(openPriceSum, Math.multiplyExact(sign, marginBase));
            final long denom = Math.multiplyExact(openVolume,
                Math.subtractExact(spec.feeScaleK, Math.multiplyExact(sign, totalFee)));
            return CoreArithmeticUtils.ceilMulDiv(numer, spec.feeScaleK, denom);
        }
    }

    /**
     * 估算强平价（触发条件：账户权益 = 维持保证金）。空仓返回 0；CROSS 无解返回 -1。
     *
     * <p>ISOLATED（单仓 self-solve，不看账户其他资源）：
     * <pre>
     *   openInitMarginSum + extraMargin + sign × (LP − EP) × Q = MM(LP × Q)
     *   ⇒ LP = (sign × (MM(LP × Q) − totalIsolatedMargin) + openPriceSum) / Q
     * </pre>
     * MM 是分档函数依赖 LP → 不动点迭代：MM(markNotional) 起手做一阶近似，再用上一步 LP × Q 处
     * 的真实 MM 精化 2 次。同 bracket 内 2 步内收敛；LP 跨 bracket 边界会发散，落到 markPrice
     * "错侧"（LONG: LP ≤ 0 或 ≥ mark；SHORT: LP ≤ mark）即停在上一步。
     *
     * <p>CROSS（本仓外账户资源做常量，线性近似）：
     * <pre>
     *   totalBalance + pnlOther + sign × (LP − EP) × Q = MM(LP × Q) + mmOther
     *   其中 pnlOther = totalPnl − 本仓 UPnL，mmOther = totalMM − 本仓 MM(markNotional)
     *   假设 MM(LP × Q) ≈ MM(MP) × LP / MP（线性外推），代入解得
     *     LP = numerator × markNotional / (Q × diff)
     *     numerator = sign × openPriceSum − totalBalance − pnlOther + mmOther
     *     diff      = sign × markNotional − MM(markNotional)
     * </pre>
     * diff = 0（分母 0）或 LP 越过 markPrice 反侧 → 返回 -1。
     *
     * <p>单位：openInitMarginSum / extraMargin / openPriceSum / MM 均 sizePriceScale；openVolume
     * baseScaleK；结果 LP 是价格（quoteScaleK）。<b>extraMargin 必须 sizePriceScale 存储</b>，否则
     * 单位不一致 LP 偏高。
     */
    public long estimateLiquidationPrice(CoreSymbolSpecification spec, LastPriceCacheRecord priceRecord,
        long totalBalance, long totalPnl, long totalMM) {
        if (openVolume == 0) {
            return 0;
        }
        final long sign = direction.getMultiplier();
        final long markPrice = priceRecord.markPrice;
        final long markNotional = Math.multiplyExact(openVolume, markPrice);
        final long maintenanceMarginAtMark = spec.calcMaintenanceMargin(markNotional);

        if (marginMode == MarginMode.ISOLATED) {
            // 定点方程推导（触发条件：账户权益 = 维持保证金）：
            //   totalIsolatedMargin + sign × (LP − EP_avg) × Q = MM(LP × Q)
            //   记 EP_avg × Q = openPriceSum，展开得：
            //   totalIsolatedMargin + sign × LP × Q − sign × openPriceSum = MM(LP × Q)
            //   两边同乘 sign（sign² = 1）解出 LP：
            //   LP = (sign × (MM(LP × Q) − totalIsolatedMargin) + openPriceSum) / Q
            final long totalIsolatedMargin = Math.addExact(openInitMarginSum, extraMargin);

            // iter 0：一阶近似 —— MM 用 markNotional 处的常量代入，等价旧单档实现
            long liquidationPrice = Math.addExact(
                Math.multiplyExact(sign, Math.subtractExact(maintenanceMarginAtMark, totalIsolatedMargin)),
                openPriceSum) / openVolume;

            // iter 1..2：不动点精化，用上一步 LP × Q 处的真实 MM 反解。
            // 收敛示例（LONG, mark=50000, Q=10, openPriceSum=500000, totalMargin=50000, MMR=8%）：
            //   iter 0: MM(500000)=40000 → LP=(40000−50000+500000)/10 = 49000
            //   iter 1: MM(490000)=39200 → LP=(39200−50000+500000)/10 = 48920
            //   iter 2: MM(489200)=39136 → LP=(39136−50000+500000)/10 = 48913（fixed point）
            for (int iter = 1; iter < 3; iter++) {
                final long lpNotional = Math.abs(Math.multiplyExact(liquidationPrice, openVolume));
                final long mmAtLp = spec.calcMaintenanceMargin(lpNotional);
                final long nextLp = Math.addExact(
                    Math.multiplyExact(sign, Math.subtractExact(mmAtLp, totalIsolatedMargin)),
                    openPriceSum) / openVolume;

                if (nextLp == liquidationPrice) {
                    break; // fixed point 命中
                }
                // LP 落到 markPrice "错侧"（LONG: LP ≤ 0 或 ≥ mark；SHORT: LP ≤ mark）说明位置已透支
                // 或跨 MM bracket 发散，停在上一步的稳定解（iter 0 的一阶近似作 sentinel）。
                final boolean crossedBracket = direction == PositionDirection.LONG
                    ? nextLp <= 0 || nextLp >= markPrice
                    : nextLp <= markPrice;
                if (crossedBracket) {
                    break;
                }
                liquidationPrice = nextLp;
            }
            return liquidationPrice;
        }

        // CROSS 定点方程推导：账户权益 = 全部持仓 MM
        //   totalBalance + totalPnl(LP) = totalMM(LP)
        //   拆本仓 vs 其他仓：
        //     totalPnl(LP) = pnlOther + sign × (LP × Q − openPriceSum)
        //     totalMM(LP)  = mmOther  + MM(LP × Q)
        //   ⇒ LP = (sign × (MM(LP × Q) − crossExternalMargin) + openPriceSum) / Q
        //     crossExternalMargin = totalBalance + pnlOther − mmOther （本仓可依赖的外部保证金）
        //
        // 与 ISOLATED 同构（只是 crossExternalMargin 换掉 totalIsolatedMargin），复用同一套迭代模板。
        final long pnlOther = Math.subtractExact(totalPnl, estimateUnrealizedProfit(priceRecord));
        final long mmOther = Math.subtractExact(totalMM, maintenanceMarginAtMark);
        final long crossExternalMargin = Math.subtractExact(Math.addExact(totalBalance, pnlOther), mmOther);

        // iter 0：线性外推 MM(LP × Q) ≈ MMR_mark × LP × Q，闭式解作为初值。
        //   LP = numerator × markNotional / (Q × diff)
        //   numerator = sign × openPriceSum − totalBalance − pnlOther + mmOther
        //   diff      = sign × markNotional − MM(markNotional)
        // 线性外推同 bracket 内比"点代入"更接近真解，充当好初值让 iter 1 通常一步命中定点。
        final long numerator = Math.addExact(
            Math.subtractExact(Math.subtractExact(Math.multiplyExact(sign, openPriceSum), totalBalance), pnlOther),
            mmOther);
        final long diff = Math.subtractExact(Math.multiplyExact(sign, markNotional), maintenanceMarginAtMark);
        if (diff == 0) {
            return -1; // 分母 0，无解
        }
        // truncMulDiv 走 128-bit slow path 兜溢出；SHORT diff<0 → denom<0 走裸除保持原语义。
        // liquidationPrice 只入事件 payload，不参与撮合决策，溢出退 fallback 而非 throw。
        final long denom = Math.multiplyExact(openVolume, diff);
        long liquidationPrice = CoreArithmeticUtils.truncMulDiv(numerator, markNotional, denom);
        // 线性外推 LP 越过 markPrice 反侧说明账户在 mark 处已达强平点，无解
        if (liquidationPrice < 0
            || (direction == PositionDirection.LONG && liquidationPrice > markPrice)
            || (direction == PositionDirection.SHORT && liquidationPrice < markPrice)) {
            return -1;
        }

        // iter 1..2：点代入精化，用 LP × Q 处真实 MM 反解，覆盖跨 bracket 场景（线性外推假设 MMR 恒定）。
        for (int iter = 1; iter < 3; iter++) {
            final long lpNotional = Math.abs(Math.multiplyExact(liquidationPrice, openVolume));
            final long mmAtLp = spec.calcMaintenanceMargin(lpNotional);
            final long nextLp = Math.addExact(
                Math.multiplyExact(sign, Math.subtractExact(mmAtLp, crossExternalMargin)),
                openPriceSum) / openVolume;

            if (nextLp == liquidationPrice) {
                break; // fixed point 命中
            }
            // LP 落到 markPrice "错侧"（LONG: ≤0 或 ≥mark；SHORT: ≤mark）说明跨 bracket 发散，
            // 停在上一步的稳定解（iter 0 的线性外推近似作 sentinel）。
            final boolean crossedBracket = direction == PositionDirection.LONG
                ? nextLp <= 0 || nextLp >= markPrice
                : nextLp <= markPrice;
            if (crossedBracket) {
                break;
            }
            liquidationPrice = nextLp;
        }
        return liquidationPrice;
    }

    /**
     * 计算保证金比率 = 维持保证金 / 仓位权益 注意结果乘以了maintenanceMarginScaleK进行缩放
     */
    public long estimateMarginRatioScaleK(CoreSymbolSpecification spec, LastPriceCacheRecord priceRecord,
        long totalMargin) {
        if (openVolume == 0) {
            return 0; // 无资金，不算保证金比率
        }
        if (totalMargin <= 0) {
            // 只要totalMargin <= maintenanceMargin就应该被强平了，如果还没有强平，保证金比率会大于100%；
            // 如果依然没有强平，totalMargin <=0 则返回-1，表示强平风险极大。
            // 原: return spec.maintenanceMarginScaleK * -1;
            // 现: multiplyExact（理论上 scaleK 不会大到溢出，但对齐策略）
            return Math.multiplyExact(spec.maintenanceMarginScaleK, -1L);
        }
        // 原: long notional = openVolume * priceRecord.markPrice;
        // 现: multiplyExact
        long notional = Math.multiplyExact(openVolume, priceRecord.markPrice);
        long maintenanceMargin = spec.calcMaintenanceMargin(notional);
        // 原: return spec.maintenanceMarginScaleK * maintenanceMargin / totalMargin;
        // 现: multiplyExact 包住 product，再 / totalMargin 保持 truncation
        return Math.multiplyExact(spec.maintenanceMarginScaleK, maintenanceMargin) / totalMargin;
    }

    /**
     * 【强平风险评估用】只计算 当前持仓*标记价格*维持保证金率，不看pending部分。
     *
     * @param spec
     * @param priceRecord
     * @return
     */
    public long calculateMaintenanceMargin(CoreSymbolSpecification spec, LastPriceCacheRecord priceRecord) {
        if (openVolume == 0) {
            return 0;
        }
        // 原: long notional = openVolume * priceRecord.markPrice;
        // 现: multiplyExact
        long notional = Math.multiplyExact(openVolume, priceRecord.markPrice);
        return spec.calcMaintenanceMargin(notional);
    }

    /**
     * Calculate required margin based on specification and current position/orders
     *
     * 当前这笔合约持仓（含挂单）所需要冻结的保证金总额 【下单前风控用】不考虑维持保证金，是因为它已经用初始保证金计算了，而初始保证金本来就大于维持保证金
     *
     * @param spec core symbol specification
     * @return required margin
     */
    public long calculateRequiredMarginForFutures(CoreSymbolSpecification spec) {
        return calculateRequiredMarginForFutures(spec, leverage);
    }

    /**
     * 持仓 + 挂单需要锁定的总保证金。
     *
     * <p>字段来源：
     * <ul>
     *   <li>{@link #openInitMarginSum} — 已成交仓位的初始保证金（开仓时按开仓价 × 初始保证金率已计入）</li>
     *   <li>{@link #openPriceSum} + {@link #direction} + {@link #openVolume} — 现有仓位的有向名义价值</li>
     *   <li>{@link #pendingBuySize} / {@link #pendingBuyAvgPrice} — BID 挂单</li>
     *   <li>{@link #pendingSellSize} / {@link #pendingSellAvgPrice} — ASK 挂单</li>
     * </ul>
     *
     * <p>挂单只对"能扩大最坏敞口"的部分收保证金。反向单在 openVolume 范围内不占额外保证金——它成交时
     * 会按比例释放 openInitMarginSum，账户敞口反而缩小。例：LONG 5 挂 ASK 3，ASK 全成后 openVolume=2，
     * 已锁的 3 份 openInitMarginSum 释放，无需为这 3 手 ASK 再锁保证金。
     *
     * <p>数学：把仓位有向化，比较 BID 全成 vs ASK 全成两种极端下的敞口最大值：
     * <pre>
     *   openNotional        = direction × openPriceSum   // 有向：LONG 正，SHORT 负，空仓 0
     *   worstCaseNotional   = max(|openNotional + bidNotional|, |openNotional − askNotional|)
     *   newExposureNotional = max(0, worstCaseNotional − |openNotional|)   // 真正新增的敞口
     *   总保证金 = openInitMarginSum
     *           + newExposureNotional 对应的初始保证金
     *           + max(BID 挂单手续费, ASK 挂单手续费)
     * </pre>
     */
    public long calculateRequiredMarginForFutures(CoreSymbolSpecification spec, int leverage) {
        final long openNotional = openVolume == 0 ? 0
            : Math.multiplyExact((long) direction.getMultiplier(), openPriceSum);
        final long bidNotional = Math.multiplyExact(pendingBuySize, pendingBuyAvgPrice);
        final long askNotional = Math.multiplyExact(pendingSellSize, pendingSellAvgPrice);

        final long worstCaseNotional = Math.max(
            Math.abs(Math.addExact(openNotional, bidNotional)),
            Math.abs(Math.subtractExact(openNotional, askNotional)));
        final long newExposureNotional = Math.max(0L, Math.subtractExact(worstCaseNotional, Math.abs(openNotional)));

        final long bidFee = CoreArithmeticUtils.calculateTakerFee(pendingBuySize, pendingBuyAvgPrice, spec);
        final long askFee = CoreArithmeticUtils.calculateTakerFee(pendingSellSize, pendingSellAvgPrice, spec);
        return Math.addExact(
            Math.addExact(openInitMarginSum, spec.calcInitMargin(newExposureNotional, leverage)),
            Math.max(bidFee, askFee));
    }

    /**
     * 把新单 {@code orderNotional} 落在 {@code action} 侧后，仓位需要的总保证金
     * （openInitMarginSum + 新增敞口对应的初始保证金）。
     * 敞口口径与 {@link #calculateRequiredMarginForFutures} 一致：把新单加到对应 pending 边后重算
     * worstCaseNotional 并扣除 |openNotional|。新单不扩敞口（纯反向或抵消现有 pending）时返回 -1，
     * caller 直接放行不扣新保证金。
     */
    public long calculateRequiredMarginForOrder(final CoreSymbolSpecification spec, final OrderAction action,
        final long orderNotional) {
        final long openNotional = openVolume == 0 ? 0
            : Math.multiplyExact((long) direction.getMultiplier(), openPriceSum);
        final long absOpenNotional = Math.abs(openNotional);
        final long bidNotional = Math.multiplyExact(pendingBuySize, pendingBuyAvgPrice);
        final long askNotional = Math.multiplyExact(pendingSellSize, pendingSellAvgPrice);
        final long newBidNotional = action == OrderAction.BID ? Math.addExact(bidNotional, orderNotional) : bidNotional;
        final long newAskNotional = action == OrderAction.ASK ? Math.addExact(askNotional, orderNotional) : askNotional;

        final long currentExposureNotional = Math.max(0L, Math.subtractExact(Math.max(
            Math.abs(Math.addExact(openNotional, bidNotional)),
            Math.abs(Math.subtractExact(openNotional, askNotional))), absOpenNotional));
        final long newExposureNotional = Math.max(0L, Math.subtractExact(Math.max(
            Math.abs(Math.addExact(openNotional, newBidNotional)),
            Math.abs(Math.subtractExact(openNotional, newAskNotional))), absOpenNotional));

        final long newTotalMargin = Math.addExact(openInitMarginSum, spec.calcInitMargin(newExposureNotional, leverage));
        final long currentTotalMargin =
            Math.addExact(openInitMarginSum, spec.calcInitMargin(currentExposureNotional, leverage));
        return newTotalMargin <= currentTotalMargin ? -1 : newTotalMargin;
    }

    /**
     * 假设pending部分以及新下单的size都能开出来，估算仓位名义价值。
     */
    public long estimateNotionalForOrder(final OrderAction action, final long size, final long price) {
        // 原: newPendingBuySize = (action==BID ? pendingBuySize + size : pendingBuySize); newPendingSellSize 同
        // long estimatedSize = openVolume + Math.max(newPendingBuySize, newPendingSellSize);
        // return estimatedSize * price;
        // 现: 三处 +/* 全部 addExact / multiplyExact
        long newPendingBuySize = action == OrderAction.BID ? Math.addExact(pendingBuySize, size) : pendingBuySize;
        long newPendingSellSize = action == OrderAction.ASK ? Math.addExact(pendingSellSize, size) : pendingSellSize;
        long estimatedSize = Math.addExact(openVolume, Math.max(newPendingBuySize, newPendingSellSize));
        return Math.multiplyExact(estimatedSize, price);
    }

    public long calculatePendingFeeForOrder(final CoreSymbolSpecification spec, final OrderAction action,
        final long size, final long price) {
        // 原: newPendingBuySize = pendingBuySize + size; newPendingSellSize 同
        // 现: addExact
        long newPendingBuySize = action == OrderAction.BID ? Math.addExact(pendingBuySize, size) : pendingBuySize;
        long newPendingSellSize = action == OrderAction.ASK ? Math.addExact(pendingSellSize, size) : pendingSellSize;
        // avg 价格通过 calcAvgPrice 已经 *Exact / addExact
        long newPendingBuyAvgPrice = action == OrderAction.BID
            ? calcAvgPrice(pendingBuyAvgPrice, pendingBuySize, price, size) : pendingBuyAvgPrice;
        long newPendingSellAvgPrice = action == OrderAction.ASK
            ? calcAvgPrice(pendingSellAvgPrice, pendingSellSize, price, size) : pendingSellAvgPrice;

        long feePendingBuy = CoreArithmeticUtils.calculateTakerFee(newPendingBuySize, newPendingBuyAvgPrice, spec);
        long feePendingSell = CoreArithmeticUtils.calculateTakerFee(newPendingSellSize, newPendingSellAvgPrice, spec);
        return Math.max(feePendingBuy, feePendingSell);
    }

    /**
     * BUDGET 单专用：与 {@link #calculatePendingFeeForOrder} 同构， 区别是用 {@code budgetNotional}（product-scale 总预算）直接累加
     * notional， 而不是 {@code price × size}。维持下游 {@code size × avgPrice ≈ pending notional} 契约。
     */
    public long calculatePendingFeeForOrderBudget(final CoreSymbolSpecification spec, final OrderAction action,
        final long size, final long budgetNotional) {
        // 原: newPendingBuySize = pendingBuySize + size; newPendingSellSize 同
        // 现: addExact
        long newPendingBuySize = action == OrderAction.BID ? Math.addExact(pendingBuySize, size) : pendingBuySize;
        long newPendingSellSize = action == OrderAction.ASK ? Math.addExact(pendingSellSize, size) : pendingSellSize;
        // 原: ceilDivide(pendingBuyAvgPrice * pendingBuySize + budgetNotional, newPendingBuySize)
        // ceilDivide(pendingSellAvgPrice * pendingSellSize + budgetNotional, newPendingSellSize)
        // 现: 内层 product / sum 拆成 multiplyExact + addExact
        long newPendingBuyAvgPrice = action == OrderAction.BID && newPendingBuySize > 0 ? CoreArithmeticUtils
            .ceilDivide(Math.addExact(Math.multiplyExact(pendingBuyAvgPrice, pendingBuySize), budgetNotional),
                newPendingBuySize)
            : pendingBuyAvgPrice;
        long newPendingSellAvgPrice = action == OrderAction.ASK && newPendingSellSize > 0 ? CoreArithmeticUtils
            .ceilDivide(Math.addExact(Math.multiplyExact(pendingSellAvgPrice, pendingSellSize), budgetNotional),
                newPendingSellSize)
            : pendingSellAvgPrice;

        long feePendingBuy = CoreArithmeticUtils.calculateTakerFee(newPendingBuySize, newPendingBuyAvgPrice, spec);
        long feePendingSell = CoreArithmeticUtils.calculateTakerFee(newPendingSellSize, newPendingSellAvgPrice, spec);
        return Math.max(feePendingBuy, feePendingSell);
    }

    public long closeCurrentPositionFutures(final OrderAction action, final long tradeSize, final long tradePrice) {

        // log.debug("{} {} {} {} cur:{}-{} profit={}", uid, action, tradeSize, tradePrice, position, totalSize,
        // profit);

        if (openVolume == 0 || direction == PositionDirection.of(action)) {
            // nothing to close
            return tradeSize;
        }

        if (openVolume > tradeSize) {
            // current position is bigger than trade size - just reduce position accordingly, don't fix profit
            // 原: openInitMarginSum -= openInitMarginSum * tradeSize / openVolume;
            // openVolume -= tradeSize;
            // openPriceSum -= tradeSize * tradePrice;
            // 现: 所有乘法 multiplyExact、减法 subtractExact。silent wrap 出来的脏值会落 raft snapshot 跨节点污染。
            // `openVolume -= tradeSize` 此处不用 subtractExact，因为 openVolume > tradeSize 已保证不会变负或溢出。
            long marginRelease = CoreArithmeticUtils.truncMulDiv(openInitMarginSum, tradeSize, openVolume);
            openInitMarginSum = Math.subtractExact(openInitMarginSum, marginRelease);
            openVolume -= tradeSize;
            openPriceSum = Math.subtractExact(openPriceSum, Math.multiplyExact(tradeSize, tradePrice));
            return 0;
        }

        // current position smaller than trade size, can close completely and calculate profit
        // 原: profit += (openVolume * tradePrice - openPriceSum) * direction.getMultiplier();
        // final long sizeToOpen = tradeSize - openVolume;
        // 现: notional / 减法 / 乘符号 / 累加 profit 四步全 *Exact / addExact / subtractExact
        long closeNotional = Math.multiplyExact(openVolume, tradePrice);
        long pnlRaw = Math.subtractExact(closeNotional, openPriceSum);
        long pnlSigned = Math.multiplyExact(pnlRaw, (long)direction.getMultiplier());
        profit = Math.addExact(profit, pnlSigned);
        openInitMarginSum = 0;
        openPriceSum = 0;
        final long sizeToOpen = Math.subtractExact(tradeSize, openVolume);
        openVolume = 0;

        // validateInternalState();

        return sizeToOpen;
    }

    public void openPositionMargin(OrderAction action, long sizeToOpen, long tradePrice, CoreSymbolSpecification spec,
        LastPriceCacheRecord record) {
        // 原: openVolume += sizeToOpen;
        // openInitMarginSum += spec.calcInitMargin(record.markPrice * sizeToOpen, leverage);
        // openPriceSum += tradePrice * sizeToOpen;
        // 现: 把内部 product 和外层 += 全部换成 *Exact / addExact。
        // 先算 notional / margin delta，再依次 addExact 累加到 state 字段。
        long openNotional = Math.multiplyExact(record.markPrice, sizeToOpen);
        long initMarginDelta = spec.calcInitMargin(openNotional, leverage);
        long priceNotional = Math.multiplyExact(tradePrice, sizeToOpen);
        openVolume = Math.addExact(openVolume, sizeToOpen);
        openInitMarginSum = Math.addExact(openInitMarginSum, initMarginDelta);
        openPriceSum = Math.addExact(openPriceSum, priceNotional);
        direction = PositionDirection.of(action);

        // validateInternalState();
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(symbol);
        bytes.writeInt(currency);
        bytes.writeByte((byte)direction.getMultiplier());
        bytes.writeLong(openVolume);
        bytes.writeLong(openInitMarginSum);
        bytes.writeLong(openPriceSum);
        bytes.writeLong(profit);
        bytes.writeLong(pendingSellSize);
        bytes.writeLong(pendingBuySize);
        bytes.writeLong(pendingSellAvgPrice);
        bytes.writeLong(pendingBuyAvgPrice);
        bytes.writeInt(leverage);
        bytes.writeByte(marginMode.getCode());
        bytes.writeLong(extraMargin);
        if (liquidationCtx != null) {
            bytes.writeByte((byte) 1);
            liquidationCtx.writeMarshallable(bytes);
        } else {
            bytes.writeByte((byte) 0);
        }
    }

    public void reset() {

        // log.debug("records: {}, Pending B{} S{} total size: {}", records.size(), pendingBuySize, pendingSellSize,
        // totalSize);

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
        adlEligibility = 100;
        pendingADLSize = 0;
        liquidationCtx = null;
    }

    public void validateInternalState() {
        if (direction == PositionDirection.EMPTY && (openVolume != 0 || openPriceSum != 0)) {
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
        // marginMode 是 enum，必须用 enumStateHash 否则 identityHashCode 跨 JVM 漂移。
        // direction.getMultiplier() 已是 int，安全。
        // liquidationCtx 进 raft state：缺失它 hash 不能反映强平流程阶段，发散检测失效。
        return Objects.hash(symbol, currency, direction.getMultiplier(), openVolume, openInitMarginSum, openPriceSum,
            profit, pendingSellSize, pendingBuySize, pendingSellAvgPrice, pendingBuyAvgPrice, leverage,
            HashingUtils.enumStateHash(marginMode), extraMargin,
            liquidationCtx == null ? 0 : liquidationCtx.stateHash());
    }

    @Override
    public String toString() {
        return "SPR{" + "u" + uid + " sym" + symbol + " cur" + currency + " pos" + direction + " Σv=" + openVolume
            + " ΣinitM=" + openInitMarginSum + " Σp=" + openPriceSum + " pnl=" + profit + " pendingS=" + pendingSellSize
            + " pendingB=" + pendingBuySize + " pendingSP=" + pendingSellAvgPrice + " pendingBP=" + pendingBuyAvgPrice
            + " lev=" + leverage + " mode=" + marginMode + " exM=" + extraMargin + " adl%=" + adlEligibility
            + " pendingADL=" + pendingADLSize + " Lctx=" + liquidationCtx + '}';
    }
}
