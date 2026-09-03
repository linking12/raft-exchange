//! 对应 Java: `exchange.core2.core.processors.liquidation.LiquidationFlow`（+ 内嵌枚举
//! `LiquidationState`）。单个持仓正在进行的强平流程：**leader-local 内存状态机**
//! （FORCE → IF → ADL）。
//!
//! 挂在 [`crate::core::common::symbol_position_record::SymbolPositionRecord::liquidation_flow`]
//! 上（`Option<LiquidationFlow>`，`None` = 无进行中流程），**不序列化、不进 `state_hash`**
//! （Ruling P6-E，同 `pending_adl_size`/`adl_eligibility`）；流程闭环时置回 `None`。换届后新
//! leader 侧全为 `None`，残余破产仓被重新检测、重走一遍 FORCE 恢复——正确性靠 R1
//! `normalize_cmd_position_size` 把 `cmd.size` 夹到当前 `open_volume`（陈旧/重复命令永不会
//! 超平仓位，参考文档 §1.5）。

/// 对应 Java `LiquidationFlow`。字段与 Java 逐一对应：`state`/`bankruptcy_price`/`size`/
/// `original_order_id`。构造即进入 [`LiquidationState::Liquidating`]（FORCE 已提交、待其 apply）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LiquidationFlow {
    pub state: LiquidationState,
    pub bankruptcy_price: i64,
    pub size: i64,
    /// FORCE 阶段生成的根 orderId，IF/ADL 的 orderId 由此派生（见 `LiquidationService::
    /// generate_if_order_id`/`generate_adl_order_id`，Task 7 编排层）。
    pub original_order_id: i64,
}

impl LiquidationFlow {
    /// 对应 Java `new LiquidationFlow(bankruptcyPrice, size, originalOrderId)`：初始态恒为
    /// [`LiquidationState::Liquidating`]（对应 Java 构造器里 `this.state = LIQUIDATING`）。
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
