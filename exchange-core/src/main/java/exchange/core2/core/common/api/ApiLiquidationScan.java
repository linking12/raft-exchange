package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;

/**
 * 期货强平兜底心跳命令（off-lane leader 定时器周期 submit）。
 *
 * <p>轮转扫描：每 tick 只扫 {@code uid % sliceCount == scanSlice} 的那一批用户，{@code sliceCount} 个 tick
 * 走完一轮。单 tick 成本从 O(全体用户) 降到 O(全体 / sliceCount)，而"任何用户最多多久被访问一次"仍有硬上界
 * （= tick 周期 × sliceCount），且该上界<b>与行情活跃度无关</b>——不会出现忙时把兜底扫描饿死、
 * 只靠利息累积而 LTV 越线的贷款永远无人过问的情况。
 *
 * <p>分片号由发令方决定并<b>随命令走 raft</b>，不是各节点 apply 时自行推算：扫哪一批必须是复制状态的一部分，
 * 否则各节点的扫描集合会不同。{@code sliceCount <= 0} 表示不分片（整扫），保留给旧日志回放。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
public final class ApiLiquidationScan extends ApiCommand {

    /** 本 tick 要扫的分片号，取值 [0, sliceCount)。 */
    public final int scanSlice;

    /** 总分片数；<= 0 视作整扫。 */
    public final int sliceCount;

    @Override
    public String toString() {
        return "[LIQUIDATION_SCAN slice=" + scanSlice + "/" + sliceCount + "]";
    }
}
