package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;

@Builder
@EqualsAndHashCode(callSuper = false)
public final class ApiRecoverState extends ApiCommand {

    public long snapshotId;

    @Override
    public String toString() {
        return "[RECOVER]-" + snapshotId;
    }

}
