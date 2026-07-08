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

import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.core.utils.HashingUtils;
import exchange.core2.core.utils.SerializationUtils;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;

@Slf4j
public final class UserProfile implements WriteBytesMarshallable, StateHash {

    // ================================================================
    // 通用字段（跨现货 / 期货 / 借贷共用）
    // ================================================================

    public final long uid;

    // ACTIVE / SUSPENDED；SUSPEND 命令 apply 前守卫要求 accounts / locks / loans 全空。
    public UserStatus userStatus;

    // 外部触发命令的幂等表（BALANCE_ADJUSTMENT、MARGIN_ADJUSTMENT、所有 LOAN_* 用户命令等共用）。
    public final BoundedLongDedupSet processedExternalIds;

    // currency -> balance（用户物理余额总额，未拆锁定）。scale = currencyScale。
    public final IntLongHashMap accounts;

    // ================================================================
    // 现货
    // ================================================================

    // 现货挂单冻结：currency -> locked amount（scale 与 accounts 一致）。
    // 语义：accounts 是"真实持有总额"，exchangeLocked 是其中"已被现货挂单冻结、不可立即支配"的部分。
    // 来源目前仅现货未成交挂单的 hold；未来可扩展到其他现货冻结类型（合规冻结、提现锁定等）。
    // RiskEngine.calculateLocked 会算进 locked，使得 free + locked = accounts。
    public final IntLongHashMap exchangeLocked;

    // ================================================================
    // 期货
    // ================================================================

    // 单向 / 双向持仓；双向持仓时 positions key 用 ±symbol 区分多空。
    public PositionMode positionMode;

    // symbol -> margin position record。HEDGE 模式下正 symbol 为多头、负 symbol 为空头。
    public final IntObjectHashMap<SymbolPositionRecord> positions;

    // ================================================================
    // 借贷（loan，现货 spot loan）
    // ================================================================

    // Isolated 借贷：loanId -> record（loanId 客户端提供、per-user 唯一，跟 Cross 命名空间独立）。
    // 抵押跟单笔 loan 绑定，收集在 record.collateralAmount。
    public final LongObjectHashMap<IsolatedLoanRecord> isolatedLoans;

    // Cross 借贷账户级多币种抵押池：currency -> amount。跟单笔 debt 解耦，账户级共享。
    // 跟 exchangeLocked 原理一致（accounts 里物理不动的虚锁 flag），但本设计有意不给独立 bucket
    // 承接（详见 loan.md §4.1）；只在 RiskEngine.calculateLocked 里作扣项体现。
    public final IntLongHashMap crossLoanCollateral;

    // Cross 借贷单笔债务凭证：loanId -> record。无抵押字段，抵押走 crossLoanCollateral 账户级池化。
    public final LongObjectHashMap<CrossLoanRecord> crossLoans;

    // ================================================================
    // 构造 / 序列化
    // ================================================================

    public UserProfile(long uid, UserStatus userStatus) {
        this.uid = uid;
        this.userStatus = userStatus;
        this.processedExternalIds = new BoundedLongDedupSet();
        this.accounts = new IntLongHashMap();
        this.exchangeLocked = new IntLongHashMap();
        this.positionMode = PositionMode.ONEWAY;
        this.positions = new IntObjectHashMap<>();
        this.isolatedLoans = new LongObjectHashMap<>();
        this.crossLoanCollateral = new IntLongHashMap();
        this.crossLoans = new LongObjectHashMap<>();
    }

    public UserProfile(BytesIn bytesIn) {
        // 通用
        this.uid = bytesIn.readLong();
        this.userStatus = UserStatus.of(bytesIn.readByte());
        this.processedExternalIds = new BoundedLongDedupSet(bytesIn);
        this.accounts = SerializationUtils.readIntLongHashMap(bytesIn);
        // 现货
        this.exchangeLocked = SerializationUtils.readIntLongHashMap(bytesIn);
        // 期货
        this.positionMode = PositionMode.of(bytesIn.readByte());
        this.positions = SerializationUtils.readIntHashMap(bytesIn, b -> new SymbolPositionRecord(uid, b));
        // 借贷
        this.isolatedLoans = SerializationUtils.readLongHashMap(bytesIn, b -> new IsolatedLoanRecord(uid, b));
        this.crossLoanCollateral = SerializationUtils.readIntLongHashMap(bytesIn);
        this.crossLoans = SerializationUtils.readLongHashMap(bytesIn, b -> new CrossLoanRecord(uid, b));
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        // 通用
        bytes.writeLong(uid);
        bytes.writeByte(userStatus.getCode());
        processedExternalIds.writeMarshallable(bytes);
        SerializationUtils.marshallIntLongHashMap(accounts, bytes);
        // 现货
        SerializationUtils.marshallIntLongHashMap(exchangeLocked, bytes);
        // 期货
        bytes.writeByte(positionMode.getCode());
        SerializationUtils.marshallIntHashMap(positions, bytes);
        // 借贷
        SerializationUtils.marshallLongHashMap(isolatedLoans, bytes);
        SerializationUtils.marshallIntLongHashMap(crossLoanCollateral, bytes);
        SerializationUtils.marshallLongHashMap(crossLoans, bytes);
    }

    // ================================================================
    // 期货持仓辅助方法
    // ================================================================

    public int createPositionsKey(int symbol, OrderAction orderAction, OrderCommandType command) {
        if (positionMode == PositionMode.HEDGE) {
            int key = orderAction == OrderAction.BID ? symbol : -symbol;
            if (command == OrderCommandType.CLOSE_POSITION || command == OrderCommandType.FORCE_LIQUIDATION) {
                return -key; // 平仓时翻转到对侧仓位
            }
            return key;
        }
        return symbol;
    }

    public int createPositionsKey(SymbolPositionRecord position) {
        if (positionMode == PositionMode.HEDGE) {
            return position.direction.getMultiplier() * position.symbol;
        }
        return position.symbol;
    }

    /**
     * 统计指定symbol下，满足predicate的仓位记录数量
     */
    public int countPositionRecord(int symbol, Predicate<SymbolPositionRecord> predicate) {
        int count = 0;
        SymbolPositionRecord longRecord = positions.get(symbol);
        if (longRecord != null && predicate.test(longRecord)) {
            count++;
        }
        if (positionMode == PositionMode.HEDGE) {
            SymbolPositionRecord shortRecord = positions.get(-symbol);
            if (shortRecord != null && predicate.test(shortRecord)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 用consumer处理指定symbol下所有仓位
     */
    public void processPositionRecord(int symbol, Consumer<SymbolPositionRecord> consumer) {
        SymbolPositionRecord longRecord = positions.get(symbol);
        if (longRecord != null) {
            consumer.accept(longRecord);
        }
        if (positionMode == PositionMode.HEDGE) {
            SymbolPositionRecord shortRecord = positions.get(-symbol);
            if (shortRecord != null) {
                consumer.accept(shortRecord);
            }
        }
    }

    /**
     * cross 可支配余额（currency scale）= accounts − exchangeLocked − Σ 同 currency 逐仓虚拟锁定 margin。
     *
     * <p>openInitMarginSum 开仓时未从 accounts 物理扣除（只在仓位记录里虚拟锁定），要显式剥离；
     * extraMargin 已在 MARGIN_ADJUSTMENT 时从 accounts 扣走，不重复减。逐仓 pending 单占用（含 pending fee）
     * 由 {@link SymbolPositionRecord#calculateRequiredMarginForFutures} 一并扣除。
     *
     * <p>三条路径共享同一口径：强平触发（LiquidationEngine.checkLiquidationCross）、账户报表
     * （SingleUserReportQuery）、事件下发（FundEventsHelper.calc）—— 避免客户端展示的 LP / marginRatio
     * 跟真实强平点脱节。
     *
     * <p>{@code symbolSpecLookup} 用函数解耦，避免 common 包反向依赖 processors 里的
     * SymbolSpecificationProvider；caller 通常传 {@code provider::getSymbolSpecification}。
     */
    public long calculateCrossAvailable(int currency, CoreCurrencySpecification currencySpec,
        IntFunction<CoreSymbolSpecification> symbolSpecLookup) {
        long crossAvailable = accounts.get(currency) - exchangeLocked.get(currency);
        for (SymbolPositionRecord iso : positions) {
            if (iso.marginMode != MarginMode.ISOLATED || iso.currency != currency) continue;
            final CoreSymbolSpecification isoSpec = symbolSpecLookup.apply(iso.symbol);
            if (isoSpec == null) continue; // spec 缺失兜底不扣，宁可 equity 略高估也不 NPE
            crossAvailable -= CoreArithmeticUtils.sizePriceToCurrencyScale(
                iso.calculateRequiredMarginForFutures(isoSpec), isoSpec, currencySpec);
        }
        return crossAvailable;
    }

    public SymbolPositionRecord getPositionRecordOrThrowEx(int key) {
        final SymbolPositionRecord record = positions.get(key);
        if (record == null) {
            throw new IllegalStateException("not found position for key " + key);
        }
        return record;
    }

    // ================================================================
    // toString / stateHash
    // ================================================================

    @Override
    public String toString() {
        return "UserProfile{" +
                "uid=" + uid +
                ", userStatus=" + userStatus +
                ", processedExternalIds=" + processedExternalIds.size() +
                ", accounts=" + accounts +
                ", exchangeLocked=" + exchangeLocked +
                ", positionMode=" + positionMode +
                ", positions=" + positions.size() +
                ", isolatedLoans=" + isolatedLoans.size() +
                ", crossLoanCollateral=" + crossLoanCollateral +
                ", crossLoans=" + crossLoans.size() +
                '}';
    }

    @Override
    public int stateHash() {
        // enum 字段必须经 HashingUtils.enumStateHash 稳定化，否则跨 JVM identityHashCode 漂移 → raft 集群 stateHash 不收敛。
        return Objects.hash(
                uid,
                HashingUtils.enumStateHash(userStatus),
                processedExternalIds.stateHash(),
                accounts.hashCode(),
                exchangeLocked.hashCode(),
                HashingUtils.enumStateHash(positionMode),
                HashingUtils.stateHash(positions),
                HashingUtils.stateHash(isolatedLoans),
                crossLoanCollateral.hashCode(),
                HashingUtils.stateHash(crossLoans));
    }
}
