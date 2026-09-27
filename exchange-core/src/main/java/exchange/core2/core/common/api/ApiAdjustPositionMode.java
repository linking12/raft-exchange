package exchange.core2.core.common.api;

import exchange.core2.core.common.PositionMode;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public final class ApiAdjustPositionMode extends ApiCommand {

    public final long uid;

    @NonNull
    public final PositionMode positionMode;

    @Override
    public String toString() {
        return "[ADJUST_POSITION_MODE " + uid + " " + positionMode + "]";
    }
}
