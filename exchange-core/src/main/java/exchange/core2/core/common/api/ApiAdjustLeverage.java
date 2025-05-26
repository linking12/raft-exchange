package exchange.core2.core.common.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;

@Builder
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
public class ApiAdjustLeverage extends ApiCommand {

    public final long uid;
    public final int symbol;
    public final int leverage;

    @Override
    public String toString() {
        return "[ADJUST_LEVERAGE u" + uid + " s" + symbol + " l" + leverage + "]";
    }
}
