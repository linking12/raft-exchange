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

import java.util.Objects;

import lombok.Getter;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.StateHash;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.utils.HashingUtils;
import exchange.core2.core.utils.SerializationUtils;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

@Slf4j
public class SymbolSpecificationProvider implements WriteBytesMarshallable, StateHash {

    // symbol->specs
    @Getter
    private final IntObjectHashMap<CoreSymbolSpecification> symbolSpecs;

    /**
     * 派生索引：(baseCurrency&lt;&lt;32 | quoteCurrency) → 现货对 symbolId，仅 {@link SymbolType#CURRENCY_EXCHANGE_PAIR}。 命中多个取
     * symbolId 最小者。供 loan 按币种反查现货对（估值/折价/强平）做 O(1) 定位。
     *
     * <p>
     * 纯派生态：不进 {@link #stateHash()}、不参与序列化——由 {@link #symbolSpecs} 唯一决定，反序列化后重建。
     */
    private final LongIntHashMap spotPairIndex = new LongIntHashMap();

    private static final int NO_SYMBOL = Integer.MIN_VALUE;

    public SymbolSpecificationProvider() {
        this.symbolSpecs = new IntObjectHashMap<>();
    }

    public SymbolSpecificationProvider(BytesIn bytes) {
        this.symbolSpecs = SerializationUtils.readIntHashMap(bytes, CoreSymbolSpecification::new);
        rebuildSpotPairIndex();
    }

    public boolean addSymbol(final CoreSymbolSpecification symbolSpecification) {
        if (getSymbolSpecification(symbolSpecification.symbolId) != null) {
            return false; // CommandResultCode.SYMBOL_MGMT_SYMBOL_ALREADY_EXISTS;
        }
        // 现货对 (base,quote) 唯一：loan 按币种反查现货对估值/折价/强平，多个会静默落到不同 pair。
        // 期货/期权豁免——交割合约按交割日合法共享 base/quote。
        if (symbolSpecification.type == SymbolType.CURRENCY_EXCHANGE_PAIR) {
            final CoreSymbolSpecification dup =
                findSpotSymbol(symbolSpecification.baseCurrency, symbolSpecification.quoteCurrency);
            if (dup != null) {
                log.warn("Reject duplicate spot pair {}/{}: already served by symbolId {}",
                    symbolSpecification.baseCurrency, symbolSpecification.quoteCurrency, dup.symbolId);
                return false;
            }
        }
        registerSymbol(symbolSpecification.symbolId, symbolSpecification);
        return true;
    }

    /** 反查 base=baseCurrency, quote=quoteCurrency 的现货对 spec；命中多个取 symbolId 最小者，无则 null。O(1)。 */
    public CoreSymbolSpecification findSpotSymbol(int baseCurrency, int quoteCurrency) {
        final int symbolId = spotPairIndex.getIfAbsent(spotPairKey(baseCurrency, quoteCurrency), NO_SYMBOL);
        return symbolId == NO_SYMBOL ? null : symbolSpecs.get(symbolId);
    }

    private static long spotPairKey(int baseCurrency, int quoteCurrency) {
        return ((long)baseCurrency << 32) | (quoteCurrency & 0xFFFFFFFFL);
    }

    private void rebuildSpotPairIndex() {
        spotPairIndex.clear();
        symbolSpecs.forEachValue(this::indexSpotPair);
    }

    private void indexSpotPair(CoreSymbolSpecification spec) {
        if (spec.type != SymbolType.CURRENCY_EXCHANGE_PAIR) {
            return;
        }
        final long key = spotPairKey(spec.baseCurrency, spec.quoteCurrency);
        final int existing = spotPairIndex.getIfAbsent(key, NO_SYMBOL);
        if (existing == NO_SYMBOL || spec.symbolId < existing) {
            spotPairIndex.put(key, spec.symbolId);
        }
    }

    public CoreSymbolSpecification getSymbolSpecification(int symbol) {
        return symbolSpecs.get(symbol);
    }

    public void registerSymbol(int symbol, CoreSymbolSpecification spec) {
        symbolSpecs.put(symbol, spec);
        indexSpotPair(spec);
    }

    public void reset() {
        symbolSpecs.clear();
        spotPairIndex.clear();
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        SerializationUtils.marshallIntHashMap(symbolSpecs, bytes);
    }

    @Override
    public int stateHash() {
        return Objects.hash(HashingUtils.stateHash(symbolSpecs));
    }

}
