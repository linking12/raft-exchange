//! 对应 Java `ADLUserPosition`（42 行）：ADL 候选视图；Java 侵入式链表+对象池不移植，改用 `Vec`（Ruling：不移植对象池化）。
use crate::core::common::position_direction::PositionDirection;

/// 对应 Java `ADLUserPosition`（`uid`/`symbol`/`direction`/`volume`/`score`；`next` 见模块文档）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AdlUserPosition {
    pub uid: i64,
    pub symbol: i32,
    /// 候选仓位方向（与触发 ADL 的 `cmd.action` 相反）。
    pub direction: PositionDirection,
    /// 本次 ADL 中该仓位可贡献的最大数量（R1 预占量 = 对称释放量，不管 merge 实际消费多少）。
    pub volume: i64,
    /// 排序用分值（`LiquidationService::risk_score`），R1 算好写入，merge 只读比较。
    pub score: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_plain_copy_value_type() {
        let a = AdlUserPosition { uid: 1, symbol: 100, direction: PositionDirection::Long, volume: 5, score: 42 };
        let b = a; // Copy，不是 move-then-error
        assert_eq!(a, b);
    }
}
