package exchange.core2.core.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Builder
public final class ADLCandidate {
    public long uid;
    public int symbol;
    public PositionDirection direction; // LONG / SHORT（与被强平仓位方向相反）
    public long volume; // 本次 ADL 中该仓位可贡献的最大数量
    public long score; // 排序用分值，由 RE 计算，ME 只比较
    public ADLCandidate nextCandidate;

    public static ADLCandidate createCandidateChain(int chainLength) {
        final ADLCandidate head = new ADLCandidate();
        ADLCandidate current = head;
        for (int i = 1; i < chainLength; i++) {
            ADLCandidate next = new ADLCandidate();
            current.nextCandidate = next;
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
        nextCandidate = null;
    }
}