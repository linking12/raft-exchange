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
package exchange.core2.core.common.api.reports;


import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.Order;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.utils.SerializationUtils;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode
@Getter
@Slf4j
public final class SingleUserReportResult implements ReportResult {

    public static SingleUserReportResult IDENTITY =
            new SingleUserReportResult(0L, null, null, null, null, null, null, null, 0L, null, QueryExecutionStatus.OK);

    private final long uid;

    // risk engine: user profile from
    //private final UserProfile userProfile;

    private final UserStatus userStatus;
    /** 真实持有总额（不再预扣 spot 挂单 hold）。free = accounts - locked。 */
    private final IntLongHashMap accounts;
    /**
     * 现货侧冻结资产（按 currency 聚合）：未成交挂单等占用的部分。
     * 客户端按 currency 算"可支配余额"：
     *   available[c] = accounts[c] - exchangeLocked[c] - Σ_{p: p.currency==c} (p.openInitMarginSum + p.pendingMargin + p.fee)
     */
    private final IntLongHashMap exchangeLocked;
    /**
     * symbol -> positions list:
     * [LONG] always comes before [SHORT] if both exist;
     * if only one position exists, it can be either LONG or SHORT.
     */
    private final IntObjectHashMap<List<Position>> positions;

    // risk engine: 现货借贷持仓（loan）
    /** Isolated 逐仓 loan（每笔 1:1 抵押），含实时 LTV。null = 该 shard 无此用户。 */
    private final List<IsolatedLoan> isolatedLoans;
    /** Cross 全仓 loan（抵押账户级共享，见 crossLoanCollateral），单笔无 LTV，健康度看 crossAccountLtvBps。 */
    private final List<CrossLoan> crossLoans;
    /** Cross 账户级抵押池（currency -> 数量，currencyScale），所有 crossLoans 共享。 */
    private final IntLongHashMap crossLoanCollateral;
    /** Cross 账户级 LTV（bps）：Σ 债务 ÷ Σ 抵押估值（folded 到 numeraire）。numeraire 未配 / 无 cross loan → 0。 */
    private final long crossAccountLtvBps;

    // matching engine: orders placed by user
    // symbol -> orders
    private final IntObjectHashMap<List<Order>> orders;

    // status
    private final QueryExecutionStatus queryExecutionStatus;


    public static SingleUserReportResult createFromMatchingEngine(long uid, IntObjectHashMap<List<Order>> orders) {
        return new SingleUserReportResult(uid, null, null, null, null, null, null, null, 0L, orders, QueryExecutionStatus.OK);
    }

    public static SingleUserReportResult createFromRiskEngineFound(long uid, UserStatus userStatus, IntLongHashMap accounts,
                                                                   IntLongHashMap exchangeLocked, IntObjectHashMap<List<Position>> positions,
                                                                   List<IsolatedLoan> isolatedLoans, List<CrossLoan> crossLoans,
                                                                   IntLongHashMap crossLoanCollateral, long crossAccountLtvBps) {
        return new SingleUserReportResult(uid, userStatus, accounts, exchangeLocked, positions,
                isolatedLoans, crossLoans, crossLoanCollateral, crossAccountLtvBps, null, QueryExecutionStatus.OK);
    }

    public static SingleUserReportResult createFromRiskEngineNotFound(long uid) {
        return new SingleUserReportResult(uid, null, null, null, null, null, null, null, 0L, null, QueryExecutionStatus.USER_NOT_FOUND);
    }

    public Map<Long, Order> fetchIndexedOrders() {
        return orders.stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toMap(Order::getOrderId, ord -> ord));
    }

    private SingleUserReportResult(final BytesIn bytesIn) {
        this.uid = bytesIn.readLong();
//        this.userProfile = bytesIn.readBoolean() ? new UserProfile(bytesIn) : null;
        this.userStatus = bytesIn.readBoolean() ? UserStatus.of(bytesIn.readByte()) : null;
        this.accounts = bytesIn.readBoolean() ? SerializationUtils.readIntLongHashMap(bytesIn) : null;
        this.exchangeLocked = bytesIn.readBoolean() ? SerializationUtils.readIntLongHashMap(bytesIn) : null;
        this.positions = bytesIn.readBoolean() ? SerializationUtils.readIntHashMap(bytesIn, b -> SerializationUtils.readList(b, Position::new)) : null;
        this.isolatedLoans = bytesIn.readBoolean() ? SerializationUtils.readList(bytesIn, IsolatedLoan::new) : null;
        this.crossLoans = bytesIn.readBoolean() ? SerializationUtils.readList(bytesIn, CrossLoan::new) : null;
        this.crossLoanCollateral = bytesIn.readBoolean() ? SerializationUtils.readIntLongHashMap(bytesIn) : null;
        this.crossAccountLtvBps = bytesIn.readLong();
        this.orders = bytesIn.readBoolean() ? SerializationUtils.readIntHashMap(bytesIn, b -> SerializationUtils.readList(b, Order::new)) : null;
        this.queryExecutionStatus = QueryExecutionStatus.of(bytesIn.readInt());
    }

    @Override
    public void writeMarshallable(final BytesOut bytes) {

        bytes.writeLong(uid);

//        bytes.writeBoolean(userProfile != null);
//        if (userProfile != null) {
//            userProfile.writeMarshallable(bytes);
//        }

        bytes.writeBoolean(userStatus != null);
        if (userStatus != null) {
            bytes.writeByte(userStatus.getCode());
        }

        bytes.writeBoolean(accounts != null);
        if (accounts != null) {
            SerializationUtils.marshallIntLongHashMap(accounts, bytes);
        }

        bytes.writeBoolean(exchangeLocked != null);
        if (exchangeLocked != null) {
            SerializationUtils.marshallIntLongHashMap(exchangeLocked, bytes);
        }

        bytes.writeBoolean(positions != null);
        if (positions != null) {
            SerializationUtils.marshallIntHashMap(positions, bytes, pos -> SerializationUtils.marshallList(pos, bytes));
        }

        bytes.writeBoolean(isolatedLoans != null);
        if (isolatedLoans != null) {
            SerializationUtils.marshallList(isolatedLoans, bytes);
        }

        bytes.writeBoolean(crossLoans != null);
        if (crossLoans != null) {
            SerializationUtils.marshallList(crossLoans, bytes);
        }

        bytes.writeBoolean(crossLoanCollateral != null);
        if (crossLoanCollateral != null) {
            SerializationUtils.marshallIntLongHashMap(crossLoanCollateral, bytes);
        }

        bytes.writeLong(crossAccountLtvBps);

        bytes.writeBoolean(orders != null);
        if (orders != null) {
            SerializationUtils.marshallIntHashMap(orders, bytes, symbolOrders -> SerializationUtils.marshallList(symbolOrders, bytes));
        }
        bytes.writeInt(queryExecutionStatus.code);

    }

    @Getter
    public enum QueryExecutionStatus {
        OK(0),
        USER_NOT_FOUND(1);

        private final int code;

        QueryExecutionStatus(int code) {
            this.code = code;
        }

        public static QueryExecutionStatus of(int code) {
            switch (code) {
                case 0:
                    return OK;
                case 1:
                    return USER_NOT_FOUND;
                default:
                    throw new IllegalArgumentException("unknown ExecutionStatus:" + code);
            }
        }
    }

    public static SingleUserReportResult merge(final Stream<BytesIn> pieces) {
        return pieces
                .sequential() // 强制串行流，不用ForkJoinPool
                .map(SingleUserReportResult::new)
                .reduce(
                        IDENTITY,
                        (a, b) -> new SingleUserReportResult(
                                //以前是a.uid 因为这里是个reduce 所以旧的实现会导致a.uid一直是IDENTITY.uid 一直为0
                                //不符合预期 所以改成这样了
                                a.uid == 0 ? b.uid : a.uid,
//                                SerializationUtils.preferNotNull(a.userProfile, b.userProfile),
                                SerializationUtils.preferNotNull(a.userStatus, b.userStatus),
                                SerializationUtils.preferNotNull(a.accounts, b.accounts),
                                SerializationUtils.preferNotNull(a.exchangeLocked, b.exchangeLocked),
                                SerializationUtils.mergeOverride(a.positions, b.positions),
                                // loan 只由 risk engine section 产出（另一 section 恒 null）：preferNotNull 取到有值那份
                                SerializationUtils.preferNotNull(a.isolatedLoans, b.isolatedLoans),
                                SerializationUtils.preferNotNull(a.crossLoans, b.crossLoans),
                                SerializationUtils.preferNotNull(a.crossLoanCollateral, b.crossLoanCollateral),
                                a.crossAccountLtvBps != 0 ? a.crossAccountLtvBps : b.crossAccountLtvBps,
                                SerializationUtils.mergeOverride(a.orders, b.orders),
                                a.queryExecutionStatus != QueryExecutionStatus.OK ? a.queryExecutionStatus : b.queryExecutionStatus));
    }

    @RequiredArgsConstructor
    @Getter
    public static class Position implements WriteBytesMarshallable {

        public final int quoteCurrency;
        // open positions state (for margin trades only)
        public final PositionDirection direction;
        public final long openVolume;
        public final long openInitMarginSum;
        public final long openPriceSum;
        public final long profit;

        // pending orders total size
        public final long pendingSellSize;
        public final long pendingBuySize;
        public final long pendingSellAvgPrice;
        public final long pendingBuyAvgPrice;

        public final int leverage;
        public final MarginMode marginMode;
        public final long extraMargin;

        // 计算值
        public final long unrealizedProfit;
        public final long liquidationPrice;
        public final long marginRatioScaleK;
        // 额外字段
        public final long markPrice;


        private Position(BytesIn bytes) {

            this.quoteCurrency = bytes.readInt();

            this.direction = PositionDirection.of(bytes.readByte());
            this.openVolume = bytes.readLong();
            this.openInitMarginSum = bytes.readLong();
            this.openPriceSum = bytes.readLong();
            this.profit = bytes.readLong();

            this.pendingSellSize = bytes.readLong();
            this.pendingBuySize = bytes.readLong();
            this.pendingSellAvgPrice = bytes.readLong();
            this.pendingBuyAvgPrice = bytes.readLong();

            this.leverage = bytes.readInt();
            this.marginMode = MarginMode.of(bytes.readByte());
            this.extraMargin = bytes.readLong();

            this.unrealizedProfit = bytes.readLong();
            this.liquidationPrice = bytes.readLong();
            this.marginRatioScaleK = bytes.readLong();
            this.markPrice = bytes.readLong();
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            bytes.writeInt(quoteCurrency);
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
            bytes.writeByte(marginMode.getCode());
            bytes.writeLong(extraMargin);
            bytes.writeLong(unrealizedProfit);
            bytes.writeLong(liquidationPrice);
            bytes.writeLong(marginRatioScaleK);
            bytes.writeLong(markPrice);
        }
    }

    /** Isolated 逐仓 loan 快照 + 实时健康度。抵押与本笔 1:1 绑定，故带 per-loan LTV。 */
    @RequiredArgsConstructor
    @Getter
    public static class IsolatedLoan implements WriteBytesMarshallable {

        public final long loanId;
        public final int symbolId;          // 所属现货 pair
        public final int collateralCurrency; // 抵押币（= spec.baseCurrency）
        public final int loanCurrency;       // 借出币（= spec.quoteCurrency）
        public final int rateBps;            // 年化利率（开仓锁定）
        public final long openedAtTs;        // 开仓时间（ms），期限强平参照
        public final long collateralAmount;  // 抵押数量（currencyScale）
        public final long outstandingPrincipal; // 剩余本金（loanCurrency scale）
        public final long accumulatedInterest;  // 已落账利息
        public final long displayInterest;   // 含未落账 pending accrue 到 now 的利息（≥ accumulatedInterest）
        public final long ltvBps;            // 实时 LTV：(本金 + displayInterest) ÷ 抵押估值；spec/markPrice 缺失 → 0
        public final long markPrice;         // 估值用 markPrice（0 = 喂价缺失，ltvBps 不可信）

        private IsolatedLoan(BytesIn b) {
            this.loanId = b.readLong();
            this.symbolId = b.readInt();
            this.collateralCurrency = b.readInt();
            this.loanCurrency = b.readInt();
            this.rateBps = b.readInt();
            this.openedAtTs = b.readLong();
            this.collateralAmount = b.readLong();
            this.outstandingPrincipal = b.readLong();
            this.accumulatedInterest = b.readLong();
            this.displayInterest = b.readLong();
            this.ltvBps = b.readLong();
            this.markPrice = b.readLong();
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            bytes.writeLong(loanId);
            bytes.writeInt(symbolId);
            bytes.writeInt(collateralCurrency);
            bytes.writeInt(loanCurrency);
            bytes.writeInt(rateBps);
            bytes.writeLong(openedAtTs);
            bytes.writeLong(collateralAmount);
            bytes.writeLong(outstandingPrincipal);
            bytes.writeLong(accumulatedInterest);
            bytes.writeLong(displayInterest);
            bytes.writeLong(ltvBps);
            bytes.writeLong(markPrice);
        }
    }

    /** Cross 全仓 loan 快照。抵押账户级共享（见外层 crossLoanCollateral / crossAccountLtvBps），单笔不含抵押 / LTV。 */
    @RequiredArgsConstructor
    @Getter
    public static class CrossLoan implements WriteBytesMarshallable {

        public final long loanId;
        public final int symbolId;      // 借款时匹配的现货 pair
        public final int loanCurrency;  // 借出币（= 该 pair 的 quoteCurrency）
        public final int rateBps;       // 年化利率（开仓锁定）
        public final long openedAtTs;   // 开仓时间（ms），期限强平参照
        public final long outstandingPrincipal; // 剩余本金（loanCurrency scale）
        public final long accumulatedInterest;  // 已落账利息
        public final long displayInterest;       // 含未落账 pending accrue 到 now 的利息

        private CrossLoan(BytesIn b) {
            this.loanId = b.readLong();
            this.symbolId = b.readInt();
            this.loanCurrency = b.readInt();
            this.rateBps = b.readInt();
            this.openedAtTs = b.readLong();
            this.outstandingPrincipal = b.readLong();
            this.accumulatedInterest = b.readLong();
            this.displayInterest = b.readLong();
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            bytes.writeLong(loanId);
            bytes.writeInt(symbolId);
            bytes.writeInt(loanCurrency);
            bytes.writeInt(rateBps);
            bytes.writeLong(openedAtTs);
            bytes.writeLong(outstandingPrincipal);
            bytes.writeLong(accumulatedInterest);
            bytes.writeLong(displayInterest);
        }
    }


    @Override
    public String toString() {
        return "SingleUserReportResult{" +
                "userProfile=" + userStatus +
                ", accounts=" + accounts +
                ", exchangeLocked=" + exchangeLocked +
                ", isolatedLoans=" + isolatedLoans +
                ", crossLoans=" + crossLoans +
                ", crossLoanCollateral=" + crossLoanCollateral +
                ", crossAccountLtvBps=" + crossAccountLtvBps +
                ", orders=" + orders +
                ", queryExecutionStatus=" + queryExecutionStatus +
                '}';
    }
}
