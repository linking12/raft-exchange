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
package exchange.core2.core.processors;

import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;

/**
 * R2 延迟副作用同步。有的命令对共享状态的改动在 R2 才生效（撮合后结算：仓位、借贷池），有的命令在 R1 读同一状态；
 * grouping 批边界随 wall-clock 漂移，读方 R1 与前序写方 R2 落同批/异批读到不同值 → 各副本分歧。master 用实例逐命令，
 * 读方 R1 前若其维度有未生效的 R2 就先冲完再跑 R1；跨 shard 全局读（reprice）单 shard flush 不够，走
 * {@link #needSyncR2Global} 让其独占 group。
 */
public final class R2Sync {

    /** 对共享状态的改动在 R2 才生效（读方要等它冲完）：撮合后仓位延迟到 R2 更新（symbol / uid+symbol 维度），及 loan 强平在 R2 结算借贷池。 */
    public static boolean takesEffectInR2(OrderCommand cmd) {
        return cmd.command == OrderCommandType.PLACE_ORDER || cmd.command == OrderCommandType.CANCEL_ORDER
            || cmd.command == OrderCommandType.REDUCE_ORDER || cmd.command == OrderCommandType.CLOSE_POSITION
            || cmd.command == OrderCommandType.FORCE_LIQUIDATION || cmd.command == OrderCommandType.IF_TAKEOVER
            || cmd.command == OrderCommandType.AUTO_DELEVERAGING || cmd.command == OrderCommandType.SETTLE_FUNDINGFEES
            || cmd.command == OrderCommandType.LOAN_FORCE_LIQUIDATE
            || cmd.command == OrderCommandType.LOAN_CROSS_FORCE_LIQUIDATE;
    }

    /**
     * R1 前须等本 symbol 的 R2 生效：ADL 挑该 symbol 盈利对手接盘、markprice/funding/pnl 结算都要读到最终仓位，否则读到
     * R1→R2 间的 stale 仓位 → 误判 / OI 泄漏。loan 借款读借贷池也归此（key 走哨兵，见 {@link #symbolKey}）。
     */
    public static boolean needSyncR2ForSymbol(OrderCommand cmd) {
        return cmd.command == OrderCommandType.MARKPRICE_ADJUSTMENT
            || cmd.command == OrderCommandType.SETTLE_FUNDINGFEES || cmd.command == OrderCommandType.SETTLE_PNL
            || cmd.command == OrderCommandType.AUTO_DELEVERAGING || cmd.command == OrderCommandType.LOAN_CREATE
            || cmd.command == OrderCommandType.LOAN_CROSS_BORROW;
    }

    /** R1 前须等本 (uid, symbol) 的 R2 生效：读到该 (uid,symbol) 最终仓位。 */
    public static boolean needSyncR2ForUidSymbol(OrderCommand cmd) {
        return (cmd.command == OrderCommandType.PLACE_ORDER && cmd.isReduceOnly())
            || cmd.command == OrderCommandType.CLOSE_POSITION || cmd.command == OrderCommandType.LEVERAGE_ADJUSTMENT
            || cmd.command == OrderCommandType.MARGIN_ADJUSTMENT || cmd.command == OrderCommandType.FORCE_LIQUIDATION
            || cmd.command == OrderCommandType.IF_TAKEOVER;
    }

    /** R1 前须等全部 R2 生效：reprice 读跨 shard 聚合借贷池，单 shard flush 不够，独占 group（GroupingProcessor 用）。 */
    public static boolean needSyncR2Global(OrderCommand cmd) {
        return cmd.command == OrderCommandType.REPRICE_LOAN_RATES;
    }

    // 借贷池是 shard 级、无 key，用一个哨兵并进 symbols 集合。symbol 是 int，取 int 域外的 long 就绝不与真实 symbol 相撞
    private static final long LOAN_POOL_PENDING = 1L << 32;

    private final LongHashSet symbols = new LongHashSet(); // 待冲的 symbol（含借贷池哨兵 LOAN_POOL_PENDING）
    private final LongHashSet uidSymbols = new LongHashSet(); // 待冲的 (uid, symbol)

    void clearGroup() {
        symbols.clear();
        uidSymbols.clear();
    }

    /** 读方 R1 前调用：其维度有未生效的 R2 则消费掉该 pending 并返回 true（master 据此冲 R2）。 */
    boolean flushNeededBefore(OrderCommand cmd) {
        boolean flush = false;
        if (needSyncR2ForSymbol(cmd) && symbols.remove(symbolKey(cmd))) {
            flush = true;
        }
        if (needSyncR2ForUidSymbol(cmd) && uidSymbols.remove(uidSymbolKey(cmd))) {
            flush = true;
        }
        return flush;
    }

    void record(OrderCommand cmd) {
        if (takesEffectInR2(cmd)) {
            symbols.add(symbolKey(cmd));
            if (!cmd.command.isLoan()) { // loan 是 shard 级、无 (uid,symbol) 仓位维度
                uidSymbols.add(uidSymbolKey(cmd));
            }
        }
    }

    /** symbols 集合的 key：loan 命令 shard 级走哨兵，其余按 cmd.symbol。 */
    private static long symbolKey(OrderCommand cmd) {
        return cmd.command.isLoan() ? LOAN_POOL_PENDING : cmd.symbol;
    }

    private static long uidSymbolKey(OrderCommand cmd) {
        return (cmd.uid << 32) | (cmd.symbol & 0xFFFF_FFFFL);
    }
}
