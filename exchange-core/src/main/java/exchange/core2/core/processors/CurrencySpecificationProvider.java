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
package exchange.core2.core.processors;


import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.StateHash;
import exchange.core2.core.utils.HashingUtils;
import exchange.core2.core.utils.SerializationUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Objects;

@Slf4j
public final class CurrencySpecificationProvider implements WriteBytesMarshallable, StateHash {

    // currency->specs
    @Getter
    private final IntObjectHashMap<CoreCurrencySpecification> currencySpecs;

    public CurrencySpecificationProvider() {
        this.currencySpecs = new IntObjectHashMap<>();
    }

    public CurrencySpecificationProvider(BytesIn bytes) {
        this.currencySpecs = SerializationUtils.readIntHashMap(bytes, CoreCurrencySpecification::new);
    }


    public boolean addCurrency(final CoreCurrencySpecification currencySpecification) {
        if (getCurrencySpecification(currencySpecification.id) != null) {
            return false; // CommandResultCode.SYMBOL_MGMT_SYMBOL_ALREADY_EXISTS;
        } else {
            registerCurrency(currencySpecification.id, currencySpecification);
            return true;
        }
    }

    /**
     * Get currency specification
     *
     * @param currency - currency code
     * @return currency specification
     */
    public CoreCurrencySpecification getCurrencySpecification(int currency) {
        return currencySpecs.get(currency);
    }

    /**
     * register new currency specification
     *
     * @param currency - currency id
     * @param spec   - currency specification
     */
    public void registerCurrency(int currency, CoreCurrencySpecification spec) {
        currencySpecs.put(currency, spec);
    }

    /**
     * Reset state
     */
    public void reset() {
        currencySpecs.clear();
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        // write currencySpecs
        SerializationUtils.marshallIntHashMap(currencySpecs, bytes);
    }

    @Override
    public int stateHash() {
        return Objects.hash(HashingUtils.stateHash(currencySpecs));
    }

}
