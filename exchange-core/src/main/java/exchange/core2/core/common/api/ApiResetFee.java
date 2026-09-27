package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;

@Builder
@EqualsAndHashCode(callSuper = false)
public final class ApiResetFee extends ApiCommand {
    @Override
    public String toString() {
        return "[RESET_FEE]";
    }
}
