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

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

import exchange.core2.core.utils.CoreArithmeticUtils;
import org.eclipse.collections.api.map.sorted.MutableSortedMap;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

@Builder
@AllArgsConstructor
@Getter
@ToString
public final class CoreSymbolSpecification implements WriteBytesMarshallable, StateHash {

    // ================================================================
    // 通用：symbol product 基础
    // ================================================================
    public final int symbolId;

    @NonNull
    public final SymbolType type;

    // currency pair specification
    public final int baseCurrency; // base currency
    public final int quoteCurrency; // quote/counter currency (OR futures contract currency)
    public final long baseScaleK; // base currency amount multiplier (lot size in base currency units)
    public final long quoteScaleK; // quote currency amount multiplier (step size in quote currency units)

    // ================================================================
    // 通用：手续费（现货 / 期货共用）
    // ================================================================
    // fees per lot in quote? currency units
    public final long takerFee; // TODO check invariant: taker fee is not less than maker fee
    public final long makerFee;
    public final long liquidationFee;
    public final long feeScaleK; // 0表示固定费用; >0表示按比例费用, rate=fee/feeScaleK

    // ================================================================
    // 期货：margin (type=FUTURES_CONTRACT only)
    // ================================================================
    public final long initMargin;
    public final long initMarginScaleK;
    // <notional, maintenanceMargin>
    public final MutableSortedMap<Long, Long> maintenanceMargin;
    public final long maintenanceMarginScaleK;
    // <notional, maxLeverage>
    public final MutableSortedMap<Long, Long> maxLeverage;

    // ================================================================
    // 借贷 per-symbol 风控配置（type=CURRENCY_EXCHANGE_PAIR only）：LTV 阈值 / 期限 / 单笔上限 / 抵押折价，
    // 归组进 SymbolLoanSpecification。"业务不可变、物理可写"：仅 UPDATE_SYMBOL_LOAN_CONFIG 经 updateLoanConfig(...)
    // 改写，全 0 = 该 pair 借贷未启用。利率不在此——per-loanCurrency 概念，归 LoanService 利率子系统。
    // ================================================================
    @Builder.Default
    public SymbolLoanSpecification loanConfig = new SymbolLoanSpecification();

    public CoreSymbolSpecification(BytesIn bytes) {
        this.symbolId = bytes.readInt();
        this.type = SymbolType.of(bytes.readByte());
        this.baseCurrency = bytes.readInt();
        this.quoteCurrency = bytes.readInt();
        this.baseScaleK = bytes.readLong();
        this.quoteScaleK = bytes.readLong();
        this.takerFee = bytes.readLong();
        this.makerFee = bytes.readLong();
        this.liquidationFee = bytes.readLong();
        this.feeScaleK = bytes.readLong();
        this.initMargin = bytes.readLong();
        this.initMarginScaleK = bytes.readLong();
        this.maintenanceMargin = readTreeMapFromBytes(bytes);
        this.maintenanceMarginScaleK = bytes.readLong();
        this.maxLeverage = readTreeMapFromBytes(bytes);
        // loan config —— 位置追加末尾，冷启无兼容 gate
        this.loanConfig = new SymbolLoanSpecification(bytes);
    }

    /* NOT SUPPORTED YET:
    
    //    order book limits -- for FUTURES only
    //    public final long highLimit;
    //    public final long lowLimit;
    
    //    swaps -- not by
    //    public final long longSwap;
    //    public final long shortSwap;
    
    // activity (inactive, active, expired)
    
      */

    public boolean isFixedFee() {
        return feeScaleK == 0;
    }

    public boolean isValidLeverage(long notional, int leverage) {
        if (leverage < 0) {
            return false; // 负杠杆一律非法：会产生负初始保证金、污染破产价（updateLeverage 也不会归一负数）
        }
        if (maxLeverage == null || maxLeverage.isEmpty()) {
            return true; // 未配档：不限上限（leverage=0 表示默认，由调用方 updateLeverage 归一为 1x）
        }
        Long maxLeverageValue = getFloorValueInSortedMap(maxLeverage, notional);
        if (maxLeverageValue == null) {
            return true;
        }
        return leverage <= maxLeverageValue;
    }

    /**
     * 初始保证金 = 名义价值 × 初始保证金率 / 杠杆
     */
    public long calculateInitMargin(long notional, long leverage) {
        if (initMarginScaleK == 0 || initMargin == 0) {
            return notional / leverage; // 默认按100%初始保证金率处理
        }
        // notional × initMargin / (initMarginScaleK × leverage)
        return CoreArithmeticUtils.ceilMulDiv(notional, initMargin, Math.multiplyExact(initMarginScaleK, leverage));
    }

    /**
     * 维持保证金 = 各分档段 seg × MMR_seg / scaleK 之和。{@link #maintenanceMargin} 以 (Floor, MMR) 分档存表， 仓位跨档时逐段累加。notional 小于最小
     * Floor 时用最小档 rate 整段兜底；表空或 scaleK=0 时按 100% 返回 notional。
     *
     * @param notional 仓位名义价值
     * @return 维持保证金
     */
    public long calculateMaintenanceMargin(long notional) {
        if (maintenanceMarginScaleK == 0 || maintenanceMargin == null || maintenanceMargin.isEmpty()) {
            return notional;
        }
        long firstFloor = maintenanceMargin.firstKey();
        long firstRate = maintenanceMargin.get(firstFloor);
        if (notional <= firstFloor) {
            return CoreArithmeticUtils.truncMulDiv(notional, firstRate, maintenanceMarginScaleK);
        }
        long mm = 0;
        long prevFloor = 0L;
        long prevRate = firstRate;
        for (Map.Entry<Long, Long> entry : maintenanceMargin.entrySet()) {
            long floor = entry.getKey();
            long seg = Math.min(notional, floor) - prevFloor;
            mm = Math.addExact(mm, CoreArithmeticUtils.truncMulDiv(seg, prevRate, maintenanceMarginScaleK));
            if (notional <= floor) {
                return mm;
            }
            prevFloor = floor;
            prevRate = entry.getValue();
        }
        return Math.addExact(mm,
            CoreArithmeticUtils.truncMulDiv(notional - prevFloor, prevRate, maintenanceMarginScaleK));
    }

    private static Long getFloorValueInSortedMap(MutableSortedMap<Long, Long> map, long key) {
        MutableSortedMap<Long, Long> headMap = map.headMap(key);
        if (!headMap.isEmpty()) {
            return map.get(headMap.lastKey());
        } else {
            return map.get(map.keySet().min());
        }
    }

    private static MutableSortedMap<Long, Long> readTreeMapFromBytes(BytesIn bytes) {
        MutableSortedMap<Long, Long> map = TreeSortedMap.newMap(Comparator.naturalOrder());
        long size = bytes.readStopBit();
        for (long i = 0; i < size; i++) {
            long key = bytes.readLong();
            long value = bytes.readLong();
            map.put(key, value);
        }
        return map;
    }

    private static void writeTreeMapToBytes(MutableSortedMap<Long, Long> map, BytesOut bytes) {
        if (map == null || map.isEmpty()) {
            bytes.writeStopBit(0); // 写入大小为 0
            return;
        }
        bytes.writeStopBit(map.size());
        map.forEachKeyValue((key, value) -> {
            bytes.writeLong(key);
            bytes.writeLong(value);
        });
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(symbolId);
        bytes.writeByte(type.getCode());
        bytes.writeInt(baseCurrency);
        bytes.writeInt(quoteCurrency);
        bytes.writeLong(baseScaleK);
        bytes.writeLong(quoteScaleK);
        bytes.writeLong(takerFee);
        bytes.writeLong(makerFee);
        bytes.writeLong(liquidationFee);
        bytes.writeLong(feeScaleK);
        bytes.writeLong(initMargin);
        bytes.writeLong(initMarginScaleK);
        writeTreeMapToBytes(maintenanceMargin, bytes);
        bytes.writeLong(maintenanceMarginScaleK);
        writeTreeMapToBytes(maxLeverage, bytes);
        // loan config —— 位置追加末尾，冷启无兼容 gate
        loanConfig.writeMarshallable(bytes);
    }

    @Override
    public int stateHash() {
        return Objects.hash(symbolId, type.getCode(), baseCurrency, quoteCurrency, baseScaleK, quoteScaleK, takerFee,
            makerFee, liquidationFee, feeScaleK, maintenanceMargin, maintenanceMarginScaleK, maxLeverage,
            loanConfig.stateHash());
    }

    /**
     * loan config 唯一 mutation point，仅供 {@code UPDATE_SYMBOL_LOAN_CONFIG} handler 调用。 caller
     * 需保证已完成字段层校验（阈值序、范围、symbolType）；本方法只做 5 字段原子写。
     */
    public void updateLoanConfig(int loanInitialLtvBps, int loanLiquidationLtvBps, int loanMarginCallLtvBps,
        long loanMaxAmount, int loanMaxTermDays) {
        loanConfig.update(loanInitialLtvBps, loanLiquidationLtvBps, loanMarginCallLtvBps, loanMaxAmount,
            loanMaxTermDays);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        CoreSymbolSpecification that = (CoreSymbolSpecification)o;
        return symbolId == that.symbolId && baseCurrency == that.baseCurrency && quoteCurrency == that.quoteCurrency
            && baseScaleK == that.baseScaleK && quoteScaleK == that.quoteScaleK && takerFee == that.takerFee
            && makerFee == that.makerFee && liquidationFee == that.liquidationFee && feeScaleK == that.feeScaleK
            && maintenanceMargin == that.maintenanceMargin && maintenanceMarginScaleK == that.maintenanceMarginScaleK
            && maxLeverage == that.maxLeverage && type == that.type && loanConfig.equals(that.loanConfig);
    }

}
