//! 对应 Java `LiquidationFlow`（`exchange.core2.core.processors.liquidation.LiquidationFlow`）。
//!
//! 单个持仓正在进行的强平流程：leader-local 内存状态机（FORCE → IF → ADL）。
//! 挂在持仓记录上，不序列化、不进 state hash；流程闭环后即被清除。换届后新 leader
//! 侧该状态为空，残余的破产仓会被重新识别并走一遍 FORCE 恢复——因此本结构体本身
//! 就是纯派生的“进度快照”，没有独立的重试计数器等需要跨节点保持一致的状态。

/// 单个持仓正在进行的强平流程的快照：`bankruptcy_price`/`size`/`original_order_id`
/// 在 FORCE 阶段确定后不再改变，`state` 随流程推进（Liquidating → WaitIfExecution/
/// WaitAdlExecution）。是 Copy 值类型，不持有句柄、不做资源管理。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LiquidationFlow {
    pub state: LiquidationState,
    pub bankruptcy_price: i64,
    pub size: i64,
    // FORCE 阶段生成的根 orderId；IF/ADL 阶段的 orderId 由此派生，用于串联同一持仓
    // 在强平流水线各阶段产生的订单。
    pub original_order_id: i64,
}

impl LiquidationFlow {
    pub fn new(bankruptcy_price: i64, size: i64, original_order_id: i64) -> Self {
        LiquidationFlow { state: LiquidationState::Liquidating, bankruptcy_price, size, original_order_id }
    }
}

/// 强平推进方向：先市价强平（Liquidating），失败转保险基金接管
/// （WaitIfExecution），再失败转 ADL 摊派（WaitAdlExecution）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LiquidationState {
    Liquidating,
    WaitIfExecution,
    WaitAdlExecution,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_starts_in_liquidating_state() {
        let f = LiquidationFlow::new(100, 50, 7);
        assert_eq!(f.state, LiquidationState::Liquidating);
        assert_eq!(f.bankruptcy_price, 100);
        assert_eq!(f.size, 50);
        assert_eq!(f.original_order_id, 7);
    }

    #[test]
    fn is_copy_value_type() {
        let a = LiquidationFlow::new(1, 2, 3);
        let b = a;
        assert_eq!(a, b);
    }
}
