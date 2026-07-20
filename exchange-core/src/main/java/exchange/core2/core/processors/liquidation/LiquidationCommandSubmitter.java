package exchange.core2.core.processors.liquidation;

import exchange.core2.core.common.api.ApiCommand;

/**
 * 强平命令提交出口（函数式接口）。实现负责把命令送入 raft 共识；非 leader 节点上应为 no-op——权威的
 * leader 门控在 server 侧实现里做。
 *
 * <p>
 * {@code onApplied}：命令 apply 后的可选回调，允许为 null（提交方不关心 apply 结果时，如
 * {@code ApiSystemLiquidationNotify} 事件）。
 */
@FunctionalInterface
public interface LiquidationCommandSubmitter {
    void submit(ApiCommand cmd, Runnable onApplied);
}
