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

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.TenPowers;
import exchange.core2.core.processors.RiskEngine;
import lombok.extern.slf4j.Slf4j;

/**
 * 撮合内核定点算术工具。所有金额/价格/手续费都按整数定点表示（约定见 scale 体系），不引入 {@code double} 或
 * {@link java.math.BigDecimal}，保证 hot path 单条命令 ≤ µs 级且结果可逐字节复现（raft 一致性前提）。
 *
 * <h3>三组职责</h3>
 * <ul>
 * <li><b>订单冻结/手续费</b>：
 * {@link #calculateAmountBid} / {@link #calculateAmountBidTakerFee} / {@link #calculateAmountBidReleaseCorrMaker}
 * / {@link #calculateTakerFee} / {@link #calculateMakerFee} / {@link #calculateLiquidationFee}。下单时按 taker 费率冻结上限；
 * 实际以更优 maker 价成交后通过 release 路径退回本金差 + 手续费差。</li>
 * <li><b>强平计算</b>：{@link #calculateSizeToLiquidate} 求权益重回维持保证金线所需的最小强平手数；
 * {@link #calculateDeficitAfterLiquidate} 预估强平 x 手后总缺口变化。两者都从公式推导直接以整数表达，不做中间转浮点。</li>
 * <li><b>单位换算</b>：{@link #sizePriceToCurrencyScale} / {@link #currencyToSizePriceScale} /
 * {@link #symbolToCurrencyScale} / {@link #currencyToSymbolScale} 在以下三套 scale 之间换算：
 * <ol>
 * <li><i>currency 记账单位</i>（{@link CoreCurrencySpecification#getCurrencyScaleK} —— 账户余额、手续费、PnL）</li>
 * <li><i>symbol 交易单位</i>（base / quote 各自的 {@code baseScaleK} / {@code quoteScaleK} —— 持仓量、报价精度）</li>
 * <li><i>撮合内部乘积单位</i>（{@code baseScaleK * quoteScaleK} —— 任何 {@code price × size} 计算的中间结果）</li>
 * </ol>
 * 跨域写盘 / 跨 API 边界都必须显式经过这些转换函数，禁止裸做乘除。</li>
 * </ul>
 *
 * <h3>溢出策略：hybrid fast/slow path</h3>
 * fee 公式 {@code size × price × fee / feeScaleK} 在大单（size≈1e8）× 高价（price≈1e12）× 千分之一以下费率下，
 * 中间积会越过 {@code Long.MAX_VALUE}。本类的乘除组合（{@link #ceilMulDiv} / {@link #ceilMulMulDiv} /
 * {@link #truncMulDiv}）统一走两段式。
 *
 * <h4>fast path（~1–10 ns）</h4>
 * 分块算法 {@code (a/c)·b + ceil((a%c)·b / c)}：先把被除数拆成"整除部分商 × b" 加"余数部分 × b 再除"，
 * 让中间积尽量留在 {@code Long} 内。两个乘法都包在 {@link Math#multiplyExact} 里，JIT 编译后等价于
 * {@code imul + jo}（never-taken 分支几乎零成本），不取这条分支时性能接近裸 {@code (a*b)/c}。
 *
 * <h4>slow path（~200 ns）</h4>
 * 任一子乘溢出 → catch {@link ArithmeticException} → fallback 到 {@link #ceilMulDiv128} /
 * {@link #truncMulDiv128} 走 128-bit 全精度长除，四步：
 * <ol>
 * <li><b>拼 128-bit 积</b>：{@link Math#multiplyHigh}(a, b) 拿高 64 bit，{@code a * b} 截断的低 64 bit 自然就是积的低位，
 * 合起来 (hi, lo) 是 signed 128-bit 表示</li>
 * <li><b>取绝对值</b>：负数走两补码 {@code hi = ~hi + (lo==0 ? 1 : 0); lo = -lo}，进位从 lo 传到 hi，
 * 避免后续无符号比较时符号位干扰</li>
 * <li><b>128/64 → 64 无符号长除</b>：余数 r 初始 = hi，把 lo 的 64 bit 自高位向低位逐位左移入 r，64 次迭代；
 * 每步判断 {@code r ≥ c}（无符号）则 {@code r -= c} 并在商 q 的对应位置 1。
 * 关键技巧：左移前若 r 的 MSB = 1，shift 后高位丢失但实际值溢出到第 65 位，隐含 {@code +2^64}，
 * 必大于任何 {@code c ≤ 2^63-1}，所以无条件触发减除——bit 级条件简化为 {@code carry || r ≥ c}。
 * 算法见 Hacker's Delight Ch.9 / Knuth TAoCP Vol.2 §4.3.1 Algorithm D 在 128/64→64 情形下的退化版</li>
 * <li><b>还原符号 + 取整</b>：正值 {@code ceil = q + (r ≠ 0 ? 1 : 0)}；负值 {@code ceil(-X/c) = -floor(X/c) = -q}
 * （因为非负整数除法本就向 0 截断 = 对正数的 floor）。trunc 路径则不做 +1 修正</li>
 * </ol>
 *
 * <h4>为什么不用 BigInteger</h4>
 * BigInteger 单次 ~2–5 µs（两次 {@code new BigInteger}、一次 mul、一次 divide），还有堆分配压力。
 * 128-bit 长除全程栈上 {@code long}，64 次循环每次 ~3 指令，加上 multiplyHigh 是 JDK intrinsic 单指令，
 * 整体 ~200 ns，比 BigInteger 快 10–20× 且零 GC。fast path 占 99.9% 流量，slow path 仅在大单极端价位触发，
 * hot path 不受影响。
 *
 * <h3>取整语义</h3>
 * 手续费、强平手数等向用户/平台不利方向取整：用户应付费用 {@link #ceilDivide} 向上取整（平台不少收），
 * 强平手数同样 ceil（保证强平后权益足额）。盈利方向（如 maker 退回、deficit 计算）用 {@link #truncMulDiv} 向零截断。
 * 改取整方向会破坏资金守恒，所有调用方都已按此约定。
 *
 * <h3>线程模型</h3>
 * 纯静态工具，无可变状态，可在 Disruptor 任意阶段调用；{@link Math#multiplyExact} 等 JDK intrinsic 在并发下天然安全。
 */
@Slf4j
public final class CoreArithmeticUtils {

    public static long calculateAmountAsk(long size) {
        return size;
    }

    public static long calculateAmountBid(long size, long price) {
        return Math.multiplyExact(size, price);
    }

    public static long calculateAmountBidTakerFee(long size, long price, CoreSymbolSpecification spec) {
        long tradeAmount = Math.multiplyExact(size, price);
        long fee = spec.isFixedFee() ? Math.multiplyExact(size, spec.takerFee)
            : ceilMulMulDiv(size, price, spec.takerFee, spec.feeScaleK);
        return Math.addExact(tradeAmount, fee);
    }

    public static void logAmountBidTakerFee(long result, long size, long price, CoreSymbolSpecification spec) {
        if (spec.isFixedFee()) {
            log.debug("hold amount buy {} = {} * ({} + {})", result, size, price, spec.takerFee);
        } else {
            log.debug("hold amount buy {} = {} * {} * (1 + {})", result, size, price,
                ceilDivide(spec.takerFee, spec.feeScaleK));
        }
    }

    /**
     * 计算订单成交后，返还给Maker的超额冻结资金 <br>
     * 假设taker费 固定2｜浮动0.1；maker费 固定1｜浮动0.01 <br>
     * 下单（taker）5块，100手，扣500；手续费 固定 2 * 100 = 200｜浮动 0.1 * 500 = 50 <br>
     * 最终 4块和别人成单（maker），100手，交易额400；手续费 固定 1 * 100 = 100｜浮动 0.01 * 400 = 4 <br>
     * 交易部分要退还 (5 - 4) * 100 = 100 <br>
     * 手续费退还 固定 100 * (2 - 1) = 100 ｜ 浮动 5 * 100 * 0.1 - 4 * 100 * 0.01 = 46 <br>
     *
     * @param size 成交手数
     * @param bidderHoldPrice 下单时的冻结参考价格
     * @param price 实际成交价格
     * @param spec 币种配置
     * @return 应返还的资金（本金差额 + 手续费差额）
     */
    public static long calculateAmountBidReleaseCorrMaker(long size, long bidderHoldPrice, long price,
        CoreSymbolSpecification spec) {
        long tradeAmountDiff = Math.multiplyExact(size, Math.subtractExact(bidderHoldPrice, price));
        long feeDiff;
        if (spec.isFixedFee()) {
            feeDiff = Math.multiplyExact(size, Math.subtractExact(spec.takerFee, spec.makerFee));
        } else {
            // 内层乘法 hold*taker / price*maker 用 multiplyExact 提早抛溢出（typical price≤1e12 × fee≤1e6 = 1e18 安全）
            // 外层 size×(差) 走 ceilMulDiv（自带 hybrid，溢出 fallback 到 Int128）
            long innerNumer = Math.subtractExact(Math.multiplyExact(bidderHoldPrice, spec.takerFee),
                Math.multiplyExact(price, spec.makerFee));
            feeDiff = ceilMulDiv(size, innerNumer, spec.feeScaleK);
        }
        return Math.addExact(tradeAmountDiff, feeDiff);
    }

    public static long calculateAmountBidTakerFeeForBudget(long size, long budgetInSteps,
        CoreSymbolSpecification spec) {
        long budgetAmount = budgetInSteps;
        long fee = spec.isFixedFee() ? Math.multiplyExact(size, spec.takerFee)
            : ceilMulDiv(budgetAmount, spec.takerFee, spec.feeScaleK);
        return Math.addExact(budgetAmount, fee);
    }

    public static void logAmountBidTakerFeeForBudget(long result, long size, long budgetInSteps,
        CoreSymbolSpecification spec) {
        log.debug("hold amount budget buy {} = {} + {} * {}", result, budgetInSteps, size,
            spec.isFixedFee() ? spec.takerFee : ceilDivide(spec.takerFee, spec.feeScaleK));
    }

    public static boolean isAskPriceTooLow(long price, CoreSymbolSpecification spec) {
        if (spec.isFixedFee()) {
            return price < spec.takerFee;
        }
        // takerFee == 0 是免手续费配置，不存在"too low"概念
        if (spec.takerFee == 0) {
            return false;
        }
        // 假设只成交1手，也要保证收到手续费才能下单。
        // 原写法 price * takerFee < feeScaleK 在 price≈1e12 × takerFee≈1e6 = 1e18 接近 Long 上界，
        // 会因溢出给出错误判定。等价改写 p < ceilDiv(f, t) 规避溢出。
        return price < ceilDivide(spec.feeScaleK, spec.takerFee);
    }

    public static long calculateTakerFee(long size, long price, CoreSymbolSpecification spec) {
        return spec.isFixedFee() ? Math.multiplyExact(size, spec.takerFee)
            : ceilMulMulDiv(size, price, spec.takerFee, spec.feeScaleK);
    }

    public static long calculateMakerFee(long size, long price, CoreSymbolSpecification spec) {
        return spec.isFixedFee() ? Math.multiplyExact(size, spec.makerFee)
            : ceilMulMulDiv(size, price, spec.makerFee, spec.feeScaleK);
    }

    public static long calculateLiquidationFee(long size, long price, CoreSymbolSpecification spec) {
        return spec.isFixedFee() ? Math.multiplyExact(size, spec.liquidationFee)
            : ceilMulMulDiv(size, price, spec.liquidationFee, spec.feeScaleK);
    }

    /**
     * 计算强平数量 x。
     * 
     * <pre>
     * 原权益：     E = openInitMarginSum + unrealizedPnl
     * 减保证金：   ΔIM = openInitMarginSum * x / Q
     * 减未实现P&L：ΔPnl = sign * (Pm - Pe) * x
     * 新权益：     E' = E - ΔIM - ΔPnl
     * 新权益须 ≥ 维持保证金：E' ≥ Pm * (Q - x) * Rmm
     *   ⇒  E - IMS * x / Q - sign * (Pm - Pe) * x ≥ Pm * (Q - x) * Rmm
     *   ⇒  x ≥ (E - Pm * Q * Rmm) / (IMS / Q + Pm * (sign * 1 - Rmm) - sign * Pe)
     *         其中 Rmm = MM / (Pm * Q),  Pe = openPriceSum / Q
     *   ⇒  x ≥ (E - MM) / (IMS / Q + sign * Pm - MM / Q - sign * openPriceSum / Q)
     *   ⇒  x ≥ (E - MM) * Q / (IMS + sign * Pm * Q - MM - sign * openPriceSum)
     * </pre>
     */
    public static long calculateSizeToLiquidate(SymbolPositionRecord position, CoreSymbolSpecification spec,
        RiskEngine.LastPriceCacheRecord priceRecord) {
        long E = position.openInitMarginSum + position.estimateUnrealizedProfit(priceRecord);
        long MM = position.calculateMaintenanceMargin(spec, priceRecord);
        long Q = position.openVolume;
        long Pm = priceRecord.markPrice;
        int sign = position.direction.getMultiplier();
        // 分子 (E-MM)*Q：大持仓 + 巨大 PnL 场景下溢出，用 multiplyExact 早抛
        long numerator = Math.multiplyExact(E - MM, Q);
        // 分母 Pm*Q：notional 大单同样可能溢出
        long denominator =
            position.openInitMarginSum + sign * Math.multiplyExact(Pm, Q) - MM - sign * position.openPriceSum;
        return ceilDivide(numerator, denominator);
    }

    /**
     * 估算强平 x 手后，对缺口的改善。
     * 
     * <pre>
     * deficit  = totalMM - totalEquity
     * Δdeficit = ΔMM - ΔE,  其中 ΔE = ΔIM + ΔPnl
     * ΔD = ΔMM - ΔIM - ΔPnl
     *    = ΔMM - openInitMarginSum * x / Q - sign * (Pm - Pe) * x,   Pe = openPriceSum / Q
     *    = ΔMM - openInitMarginSum * x / Q - sign * (Pm - openPriceSum / Q) * x
     *    = ΔMM - (openInitMarginSum + sign * (Pm * Q - openPriceSum) * x) / Q
     * </pre>
     */
    public static long calculateDeficitAfterLiquidate(long size, SymbolPositionRecord position,
        CoreSymbolSpecification spec, RiskEngine.LastPriceCacheRecord priceRecord) {
        int sign = position.direction.getMultiplier();
        // notional = openVolume * markPrice 在大仓位巨额合约场景下可能溢出，用 multiplyExact 早抛
        long notionalNow = Math.multiplyExact(position.openVolume, priceRecord.markPrice);
        long notionalAfter = Math.multiplyExact(Math.subtractExact(position.openVolume, size), priceRecord.markPrice);
        long deltaMM = spec.calcMaintenanceMargin(notionalNow) - spec.calcMaintenanceMargin(notionalAfter);
        long numerator = position.openInitMarginSum
            + sign * (Math.multiplyExact(priceRecord.markPrice, position.openVolume) - position.openPriceSum);
        // numerator * size 在大仓位场景溢出（同 fee 路径 bug 同款 pattern），ceilMulDiv 自带 hybrid 兜底
        // numerator 可能为负（sign=-1 short），故把 size（恒正）放第一参，numerator 放第二参（ceilMulDiv 支持 b 为负）
        return deltaMM - ceilMulDiv(size, numerator, position.openVolume);
    }

    /**
     * 向上取整的整数除法，防止 {@code x / y = 0}。 性能比 {@link Math#ceil(double)} 好——不引入浮点数计算。
     */
    public static long ceilDivide(long dividend, long divisor) {
        return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
    }

    /**
     * 计算 {@code ceil((a × b × c) / d)}，<b>hybrid fast/slow path</b> 覆盖 fee 类 4-操作数公式
     * （{@code size × price × fee / feeScaleK}）。
     * <ol>
     * <li>fast path：{@code a*b} 不溢出，走分块算法</li>
     * <li>slow path：{@code a*b} 溢出时重排为 {@code a × (b*c) / d}（{@code b*c} 典型场景下安全）， 并走 {@link #ceilMulDiv128} 128-bit
     * 兜底</li>
     * </ol>
     *
     * <p>
     * 前提：a, c, d > 0；b 任意（fee 通常 > 0，maker rebate 时可负）。
     */
    public static long ceilMulMulDiv(long a, long b, long c, long d) {
        try {
            long ab = Math.multiplyExact(a, b);
            return ceilMulDiv(ab, c, d);
        } catch (ArithmeticException overflow) {
            // a*b 溢出：重排成 a × (b*c) / d，b*c 通常 ≤ 1e18 安全
            long bc = Math.multiplyExact(b, c);
            return ceilMulDiv128(a, bc, d);
        }
    }

    /**
     * 计算 {@code ceil((a × b) / c)}，<b>hybrid fast/slow path</b>：
     * <ol>
     * <li>fast path（~10 ns）：分块算法 {@code (a/c)·b + ceil((a%c)·b / c)}， 内部用 {@link Math#multiplyExact} 检测子项溢出</li>
     * <li>slow path（~200 ns）：触发溢出时自动 fallback 到 {@link #ceilMulDiv128} 的 128-bit 长除</li>
     * </ol>
     *
     * <p>
     * JIT 编译下 multiplyExact ≈ {@code imul + jo}，never-taken 分支接近零成本， 因此 hot path 性能几乎等同原版裸分块。
     *
     * <p>
     * 前提：a >= 0，c > 0，b 可正可负。
     */
    public static long ceilMulDiv(long a, long b, long c) {
        try {
            long q = a / c;
            long r = a - q * c; // a % c，>= 0 when a >= 0
            long whole = Math.multiplyExact(q, b);
            long partial = Math.multiplyExact(r, b);
            long correction = partial >= 0 ? (partial + c - 1) / c // 正 partial 向 +∞ 取整
                : partial / c; // 负 partial：Java / 向 0 截断 == ceil
            return Math.addExact(whole, correction);
        } catch (ArithmeticException overflow) {
            // 分块子项溢出（极大 a 或 |b|），fallback 到 128-bit 全精度
            return ceilMulDiv128(a, b, c);
        }
    }

    /**
     * 计算 {@code ceil((a × b) / c)} 全 128-bit 中间精度（hot path 慢路径兜底）。
     *
     * <p>
     * 用 {@link Math#multiplyHigh} 拼 128-bit signed 积；bit-by-bit 长除避免 BigInteger 分配（参考 Hacker's Delight Ch.9 / Knuth
     * TAoCP Vol.2 §4.3.1 Algorithm D 在 128/64 → 64 情形下的退化版）。
     *
     * <p>
     * 性能：单次 ~200 ns，比 BigInteger 快 10-20x；仅在分块 fast path 溢出时被调用， hot path 不会受影响。
     *
     * <p>
     * 结果须可表示为 signed long；超出则仅低 64 bit 有效（同 ceilMulDiv 语义）。
     */
    public static long ceilMulDiv128(long a, long b, long c) {
        if (c <= 0) {
            throw new ArithmeticException("c must be positive: " + c);
        }
        // 1) 128-bit signed product: (hi, lo)
        long lo = a * b;
        long hi = Math.multiplyHigh(a, b);

        // 2) 取绝对值（128-bit 两补码：~x + 1，进位从 lo 传到 hi）
        boolean negative = hi < 0;
        if (negative) {
            hi = ~hi + (lo == 0 ? 1 : 0);
            lo = -lo;
        }

        // 3) 无符号 128/64 → 64 长除：商 q，余 r
        long q;
        long r;
        if (hi == 0) {
            // 快路径：积本就在 64 bit 内
            q = Long.divideUnsigned(lo, c);
            r = Long.remainderUnsigned(lo, c);
        } else {
            // bit-by-bit 长除：r 初始 = hi（高位段），把 lo 的 64 bit 自高位向低位逐位 shift 入参与减除
            //
            // 关键技巧：当 r 在左移前 MSB 已 = 1，shift 后高位丢失，但实际值溢出到第 65 位 →
            // 此时 r' 隐含 +2^64，无条件大于 c（c ≤ 2^63-1 时显然，c 任意正值时见下），
            // 故触发减除；64-bit 算术下 (r - c) 自动等同 (r + 2^64) - c 的低 64 bit。
            r = hi;
            q = 0L;
            for (int i = 63; i >= 0; i--) {
                boolean carry = r < 0; // shift 前 MSB
                r = (r << 1) | ((lo >>> i) & 1L);
                if (carry || Long.compareUnsigned(r, c) >= 0) {
                    r -= c;
                    q |= 1L << i;
                }
            }
        }

        // 4) 还原符号 + ceil
        if (negative) {
            // X ≥ 0, c > 0: ceil(-X/c) = -floor(X/c) = -q（X/c = q + r/c，r/c ∈ [0,1)，正向截断即 q）
            return -q;
        }
        // 正值：ceil = q + (余非零 ? 1 : 0)
        return r != 0 ? Math.addExact(q, 1L) : q;
    }

    /**
     * 计算 {@code (a × b) / c}（向零截断），<b>hybrid fast/slow path</b>：
     * <ol>
     * <li>fast path（~1 ns）：{@link Math#multiplyExact} 不溢出时直接 64-bit 除法</li>
     * <li>slow path（~200 ns）：溢出时 fallback 到 {@link #truncMulDiv128} 128-bit 长除</li>
     * </ol>
     *
     * <p>
     * 前提：a, b, c 任意符号，c ≠ 0。
     */
    public static long truncMulDiv(long a, long b, long c) {
        try {
            return Math.multiplyExact(a, b) / c;
        } catch (ArithmeticException overflow) {
            return truncMulDiv128(a, b, c);
        }
    }

    /**
     * 计算 {@code (a × b) / c}（向零截断）全 128-bit 中间精度（{@link #truncMulDiv} 慢路径兜底）。
     *
     * <p>
     * 取三操作数绝对值，用 {@link Math#multiplyHigh} 拼 128-bit 无符号积（非负输入下 signed == unsigned）， bit-by-bit 长除还原商，最后按符号位还原。与
     * {@link #ceilMulDiv128} 同款算法；区别：截断而非向上取整， 且 c 可为负（全仓强平价场景 SHORT 时 denom &lt; 0）。
     *
     * <p>
     * 性能：单次 ~200 ns，比 BigInteger 快 10-20x；仅在 fast path 溢出时调用。
     *
     * <p>
     * 前提：a, b, c 任意符号，c ≠ 0；结果须可表示为 signed long，超出则仅低 64 bit 有效。
     */
    public static long truncMulDiv128(long a, long b, long c) {
        if (c == 0)
            throw new ArithmeticException("/ by zero");
        boolean negative = ((a < 0) ^ (b < 0)) ^ (c < 0);
        long absA = a < 0 ? -a : a;
        long absB = b < 0 ? -b : b;
        long absC = c < 0 ? -c : c;
        long lo = absA * absB;
        long hi = Math.multiplyHigh(absA, absB);
        long q;
        if (hi == 0) {
            q = Long.divideUnsigned(lo, absC);
        } else {
            long r = hi;
            q = 0L;
            for (int i = 63; i >= 0; i--) {
                boolean carry = r < 0;
                r = (r << 1) | ((lo >>> i) & 1L);
                if (carry || Long.compareUnsigned(r, absC) >= 0) {
                    r -= absC;
                    q |= 1L << i;
                }
            }
        }
        return negative ? -q : q;
    }

    /**
     * 撮合内部乘积单位(baseScale * quoteScale) → 币种记账单位(currency) 只要计算涉及了price * size都是内部乘积单位
     */
    public static long sizePriceToCurrencyScale(long amount, CoreSymbolSpecification spec,
        CoreCurrencySpecification currency) {
        return convertScale(amount, spec.baseScaleK * spec.quoteScaleK, currency.getCurrencyScaleK());
    }

    /**
     * 币种记账单位(currency) → 撮合内部乘积单位(baseScale * quoteScale)
     */
    public static long currencyToSizePriceScale(long amount, CoreSymbolSpecification spec,
        CoreCurrencySpecification currency) {
        return convertScale(amount, currency.getCurrencyScaleK(), spec.baseScaleK * spec.quoteScaleK);
    }

    /**
     * 币对交易单位(symbol.base或quote) → 币种记账单位(currency) 返还补充保证金，现货加减base用到
     */
    public static long symbolToCurrencyScale(long amount, CoreSymbolSpecification spec,
        CoreCurrencySpecification currency) {
        if (currency.id == spec.baseCurrency) {
            return convertScale(amount, spec.baseScaleK, currency.getCurrencyScaleK());
        } else if (currency.id == spec.quoteCurrency) {
            return convertScale(amount, spec.quoteScaleK, currency.getCurrencyScaleK());
        }
        throw new IllegalArgumentException("currency id: " + currency.id + " not part of symbol: " + spec.symbolId);
    }

    /**
     * 币种记账单位(currency) → 币对交易单位(symbol.base或quote) 逐仓追加补充保证金用到
     */
    public static long currencyToSymbolScale(long amount, CoreSymbolSpecification spec,
        CoreCurrencySpecification currency) {
        if (currency.id == spec.baseCurrency) {
            return convertScale(amount, currency.getCurrencyScaleK(), spec.baseScaleK);
        } else if (currency.id == spec.quoteCurrency) {
            return convertScale(amount, currency.getCurrencyScaleK(), spec.quoteScaleK);
        }
        throw new IllegalArgumentException("currency id: " + currency.id + " not part of symbol: " + spec.symbolId);
    }

    private static long convertScale(long amount, long fromScale, long toScale) {
        if (fromScale == toScale) {
            return amount;
        }
        int diff = TenPowers.log10(fromScale) - TenPowers.log10(toScale);
        if (diff > 0) { // 缩小：除法不溢出
            return amount / TenPowers.pow10(diff);
        }
        // 放大：amount × 10^|diff| 在 amount 大 + 跨多个数量级时静默溢出，用 multiplyExact 早抛
        return Math.multiplyExact(amount, TenPowers.pow10(-diff));
    }
}
