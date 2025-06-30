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
import java.util.Objects;

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

    public final int symbolId;

    @NonNull
    public final SymbolType type;

    // currency pair specification
    public final int baseCurrency; // base currency
    public final int quoteCurrency; // quote/counter currency (OR futures contract currency)
    public final long baseScaleK; // base currency amount multiplier (lot size in base currency units)
    public final long quoteScaleK; // quote currency amount multiplier (step size in quote currency units)

    // fees per lot in quote? currency units
    public final long takerFee; // TODO check invariant: taker fee is not less than maker fee
    public final long makerFee;
    public final long feeScaleK; // 0表示固定费用; >0表示按比例费用, rate=fee/feeScaleK

    // margin settings (for type=FUTURES_CONTRACT only)
    public final long initMargin;
    public final long initMarginScaleK;
    // <notional, maintenanceMargin>
    public final MutableSortedMap<Long, Long> maintenanceMargin;
    public final long maintenanceMarginScaleK;
    // <notional, maxLeverage>
    public final MutableSortedMap<Long, Long> maxLeverage;

    public CoreSymbolSpecification(BytesIn bytes) {
        this.symbolId = bytes.readInt();
        this.type = SymbolType.of(bytes.readByte());
        this.baseCurrency = bytes.readInt();
        this.quoteCurrency = bytes.readInt();
        this.baseScaleK = bytes.readLong();
        this.quoteScaleK = bytes.readLong();
        this.takerFee = bytes.readLong();
        this.makerFee = bytes.readLong();
        this.feeScaleK = bytes.readLong();
        this.initMargin = bytes.readLong();
        this.initMarginScaleK = bytes.readLong();
        this.maintenanceMargin = readTreeMapFromBytes(bytes);
        this.maintenanceMarginScaleK = bytes.readLong();
        this.maxLeverage = readTreeMapFromBytes(bytes);
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
        if (maxLeverage == null || maxLeverage.isEmpty()) {
            return true;
        }
        Long maxLeverageValue = floorValue(maxLeverage, notional);
        if (maxLeverageValue == null) {
            return true;
        }
        return leverage >= 0 && leverage <= maxLeverageValue;
    }

    public long getInitMarginRate() {
        if (initMarginScaleK == 0 || initMargin == 0) {
            return 1;
        }
        return initMargin / initMarginScaleK;
    }

    public long getMaintenanceMarginRate(long notional) {
        if (maintenanceMarginScaleK == 0 || maintenanceMargin.isEmpty()) {
            return 1;
        }
        Long marginValue = floorValue(maintenanceMargin, notional);
        if (marginValue == null) {
            return 1;
        }
        return marginValue / maintenanceMarginScaleK;
    }

    private static Long floorValue(MutableSortedMap<Long, Long> map, long key) {
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
        bytes.writeLong(feeScaleK);
        writeTreeMapToBytes(maintenanceMargin, bytes);
        bytes.writeLong(maintenanceMarginScaleK);
        writeTreeMapToBytes(maxLeverage, bytes);
    }

    @Override
    public int stateHash() {
        return Objects.hash(symbolId, type.getCode(), baseCurrency, quoteCurrency, baseScaleK, quoteScaleK, takerFee, makerFee, feeScaleK, maintenanceMargin,
            maintenanceMarginScaleK, maxLeverage);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        CoreSymbolSpecification that = (CoreSymbolSpecification)o;
        return symbolId == that.symbolId && baseCurrency == that.baseCurrency && quoteCurrency == that.quoteCurrency && baseScaleK == that.baseScaleK
            && quoteScaleK == that.quoteScaleK && takerFee == that.takerFee && makerFee == that.makerFee && feeScaleK == that.feeScaleK
            && maintenanceMargin == that.maintenanceMargin && maintenanceMarginScaleK == that.maintenanceMarginScaleK && maxLeverage == that.maxLeverage
            && type == that.type;
    }

}
