package exchange.core2.core.processors.liquidation;

import exchange.core2.core.common.api.ApiCommand;

/**
 * 强平类 cmd 发布抽象。
 * <p>
 * {@code onApplied} 在 cmd 经 raft 共识 + onApply 后触发，成功失败都会回调。
 * 非 raft 路径（如单机本地 ringbuffer）或 follower no-op 路径下，实现应同步触发 onApplied，
 * 保证 LiquidationEngine 的 in-flight 集合不积累死值。
 * <p>
 * {@code onApplied} 允许为 null，表示发布方对 apply 不关心（如 ApiSystemLiquidationNotify 事件）。
 */
@FunctionalInterface
public interface LiquidationCmdPublisher {
    void publish(ApiCommand cmd, Runnable onApplied);
}
