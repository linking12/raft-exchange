//! 对应 Java `LiquidationFlow`（+ `LiquidationState`）：单仓强平流程的 leader-local 内存状态机（FORCE→IF→ADL），不序列化、不进 state_hash（Ruling P6-E），换届后靠 R1 `normalize_cmd_position_size` 保正确性（§1.5）。

/// 对应 Java `LiquidationFlow`：字段逐一对应，构造即进入 [`LiquidationState::Liquidating`]。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LiquidationFlow {
    pub state: LiquidationState,
    pub bankruptcy_price: i64,
    pub size: i64,
    /// FORCE 阶段生成的根 orderId，IF/ADL 的 orderId 由此派生。
    pub original_order_id: i64,
}

impl LiquidationFlow {
    /// 对应 Java 构造器：初始态恒为 [`LiquidationState::Liquidating`]。
    pub fn new(bankruptcy_price: i64, size: i64, original_order_id: i64) -> Self {
        LiquidationFlow { state: LiquidationState::Liquidating, bankruptcy_price, size, original_order_id }
    }
}

/// 对应 Java `LiquidationFlow.LiquidationState`：强平推进方向——先市价强平（FORCE），失败转 IF
/// 接管，再失败转 ADL 摊派。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LiquidationState {
    /// FORCE_LIQUIDATION 已提交、等待其 apply（对应 Java `LIQUIDATING`）。
    Liquidating,
    /// FORCE 部分/零成交（REJECT）→ IF_TAKEOVER 已提交、等待其 apply（对应 `WAIT_IF_EXECUTION`）。
    WaitIfExecution,
    /// IF 接管不足（REJECT）→ AUTO_DELEVERAGING 已提交、等待其 apply（对应 `WAIT_ADL_EXECUTION`）。
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
        let b = a; // Copy，非 move
        assert_eq!(a, b);
    }
}
