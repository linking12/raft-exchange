//! 对应 Java: `exchange.core2.core.common.ADLUserPosition`（42 行）：ADL 决策 + 执行用的单条候选
//! 视图。R1 `collect_input` 构造（含预占后的 `volume`），merge 阶段挑选/消费，R2 `finalize` 走原
//! 表对称释放 `pending_adl_size`（见 `processors::adl_command_processor` 模块文档）。
//!
//! Java 版本是侵入式单链表节点（`next` 字段 + 对象池 `chainHead`/`reset`/`createChain`），本移植
//! 用 `Vec<AdlUserPosition>`（`OrderCommand::adl_user_positions`）取代链表——Rust 没有对象池，
//! `next`/`reset`/`createChain` 不落地（P6 通篇的 Ruling：不移植 Java 的对象池化机制，见
//! `loan_rate_pricing_processor.rs`/`if_command_processor.rs` 等既有先例）。`direction` 字段
//! 保留（对齐 Java 结构），但下游查找一律用 `cmd.action.opposite()` 重算 key（见处理器模块文档），
//! 与 Java 实际读取路径一致——`direction` 字段本身在 Java 源码里也从未被下游读取过，纯粹是结构体
//! 字段对称保留。
use crate::core::common::position_direction::PositionDirection;

/// 对应 Java `ADLUserPosition`（`uid`/`symbol`/`direction`/`volume`/`score`；`next` 见模块文档）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AdlUserPosition {
    pub uid: i64,
    pub symbol: i32,
    /// 候选仓位自身的方向（与触发 ADL 的 `cmd.action` 相反——见
    /// `AdlCommandProcessor::collect_input` 的筛选条件）。
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
