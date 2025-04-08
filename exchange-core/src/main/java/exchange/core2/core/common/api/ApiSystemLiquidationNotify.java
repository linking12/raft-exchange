package exchange.core2.core.common.api;

import exchange.core2.core.common.FundEvent;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiSystemLiquidationNotify extends ApiCommand {

    public final FundEvent fundEvent;

    @Override
    public String toString() {
        return "[SYSTEM_LIQUIDATION_NOTIFY]";
    }

}
