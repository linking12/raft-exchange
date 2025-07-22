package exchange.core2.core.common.api.binary;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.utils.SerializationUtils;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Collection;

@AllArgsConstructor
@EqualsAndHashCode
@Getter
public final class BatchAddCurrenciesCommand implements BinaryDataCommand {

    private final IntObjectHashMap<CoreCurrencySpecification> currencies;

    public BatchAddCurrenciesCommand(final CoreCurrencySpecification currency) {
        currencies = IntObjectHashMap.newWithKeysValues(currency.id, currency);
    }

    public BatchAddCurrenciesCommand(final Collection<CoreCurrencySpecification> collection) {
        currencies = new IntObjectHashMap<>(collection.size());
        collection.forEach(c -> currencies.put(c.id, c));
    }


    public BatchAddCurrenciesCommand(final BytesIn bytes) {
        currencies = SerializationUtils.readIntHashMap(bytes, CoreCurrencySpecification::new);
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        SerializationUtils.marshallIntHashMap(currencies, bytes);
    }

    @Override
    public int getBinaryCommandTypeCode() {
        return BinaryCommandType.ADD_CURRENCIES.getCode();
    }
}
