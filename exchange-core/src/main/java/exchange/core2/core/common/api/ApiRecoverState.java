package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;

@Builder
@EqualsAndHashCode(callSuper = false)
public class ApiRecoverState extends ApiCommand {

    public long snapshotId;
    public String root;

    @Override
    public String toString() {
        return "[RECOVER]-" + snapshotId + " root=" + root;
    }

}
