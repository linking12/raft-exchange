package exchange.core2.core.common.api;

public class ApiSystemCheckPositionCommand extends ApiCommand {

    public final static ApiSystemCheckPositionCommand INSTANCE = new ApiSystemCheckPositionCommand();

    @Override
    public String toString() {
        return "[SYSTEM_CHECK_POSITION]";
    }
}
