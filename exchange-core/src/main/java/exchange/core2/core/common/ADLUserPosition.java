package exchange.core2.core.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

/**
 * A lightweight view of a user's position used for ADL decision and execution.
 * <p>
 * Built in R1, selected in ME, and applied in R2.
 */
@AllArgsConstructor
@NoArgsConstructor
@Builder
public final class ADLUserPosition {
    public long uid;
    public int symbol;
    public PositionDirection direction; // LONG / SHORT（与被强平仓位方向相反）
    public long volume; // 本次 ADL 中该仓位可贡献的最大数量
    public long score; // 排序用分值，由 RE 计算，ME 只比较
    public ADLUserPosition next;

    public static ADLUserPosition createChain(int chainLength) {
        final ADLUserPosition head = new ADLUserPosition();
        ADLUserPosition current = head;
        for (int i = 1; i < chainLength; i++) {
            ADLUserPosition next = new ADLUserPosition();
            current.next = next;
            current = next;
        }
        return head;
    }

    public void reset() {
        uid = 0;
        symbol = 0;
        direction = PositionDirection.EMPTY;
        volume = 0;
        score = 0;
        next = null;
    }
}