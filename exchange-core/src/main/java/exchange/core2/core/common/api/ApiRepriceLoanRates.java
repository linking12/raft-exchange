package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;

/**
 * 动态利率重定价命令（周期系统命令，见 loan.md §13.2）。leader 侧调度器周期 submit，
 * 两步处理：R1 各 shard 收本地借贷池 → merge 跨 shard 算全局利用率 + 曲线 → R2 各 shard 写 currentRateBps。
 * 无 payload（利率纯由复制状态 + cmd.timestamp 决定）。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
public final class ApiRepriceLoanRates extends ApiCommand {
    @Override
    public String toString() {
        return "[REPRICE_LOAN_RATES]";
    }
}
