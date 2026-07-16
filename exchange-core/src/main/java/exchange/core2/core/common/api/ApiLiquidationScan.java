package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;

/**
 * 期货强平兜底心跳命令（off-lane leader 定时器周期 submit）。空 payload：
 * 各分片 apply 时全量整扫用户 on-lane 扫描破产仓，破产则重发 FORCE_LIQUIDATION。
 * 无 payload（触发纯由复制状态 + cmd.timestamp 决定）。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
public final class ApiLiquidationScan extends ApiCommand {
    @Override
    public String toString() {
        return "[LIQUIDATION_SCAN]";
    }
}
