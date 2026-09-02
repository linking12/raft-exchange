//! 对应 Java: exchange.core2.core.common.SymbolPositionRecord（Task 1 子集：字段 + 构造/初始化
//! + `is_empty`/`reset`/`state_hash`；保证金/PnL/开平原语见 §1 `:494-669`，本移植落 Task 2）。
//!
//! Java 字段里 `adlEligibility`/`pendingADLSize`/`liquidationFlow` 是 **leader-local**、纯内存、
//! 不持久化、不进 `stateHash` 的字段（强平扫描专用状态，P6 才会用到）。本移植 Task 1 按参考文档
//! §1 的说明整体不落这三个字段——它们不参与任何 Task 1-4 的业务语义，等 P6 落地强平/ADL 时再按
//! 需引入，避免过早搬入无消费者的状态。
use std::collections::BTreeMap;

use crate::core::common::margin_mode::MarginMode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::position_direction::PositionDirection;

/// 对应 Java `SymbolPositionRecord`：期货 / 保证金交易的单 symbol、单方向持仓记录。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SymbolPositionRecord {
    pub uid: i64,
    pub symbol: i32,
    pub currency: i32,

    /// 持仓方向；`EMPTY` 表示当前无持仓（`open_volume==0` 时恒为 `Empty`）。
    pub direction: PositionDirection,
    /// 持仓量（baseScaleK，无符号，符号由 `direction` 带）。
    pub open_volume: i64,
    /// 当前开仓量锁定的初始保证金（sizePriceScale = baseScaleK×quoteScaleK）。
    pub open_init_margin_sum: i64,
    /// 持仓总成本（sizePriceScale）；均价 = `open_price_sum / open_volume`。
    pub open_price_sum: i64,
    /// 已实现盈亏累加器，仅在仓位清空时入账（累加进 accounts）。
    pub profit: i64,

    /// 挂单量（baseScaleK）：R1 发单前加，R2 成交/拒/减确认时减。
    pub pending_sell_size: i64,
    pub pending_buy_size: i64,
    /// 挂单侧加权均价，最坏敞口/费用估算用。
    pub pending_sell_avg_price: i64,
    pub pending_buy_avg_price: i64,

    /// 用户自选杠杆，`updateLeverage` 归一：0 -> 1。
    pub leverage: i32,
    /// 默认逐仓（`ISOLATED`）。
    pub margin_mode: MarginMode,
    /// 补充保证金（sizePriceScale），`MARGIN_ADJUSTMENT` 手动加，清空仓位时整额退。
    pub extra_margin: i64,
}

impl SymbolPositionRecord {
    /// 对应 Java 池化复用前的手工构造场景：直接给定 `(uid, symbol, currency, margin_mode,
    /// leverage)`，其余字段取结构体默认零值（`direction=Empty`）。`leverage` 经
    /// [`Self::update_leverage`] 归一（`0` -> `1`）。
    pub fn new(uid: i64, symbol: i32, currency: i32, margin_mode: MarginMode, leverage: i32) -> Self {
        let mut r = SymbolPositionRecord { uid, symbol, currency, margin_mode, ..Default::default() };
        r.update_leverage(leverage);
        r
    }

    /// 对应 Java `initialize(long uid, int symbol, int currency, OrderAction orderAction,
    /// int leverage, MarginMode marginMode)`：池化复用入口，按开仓方向建立新记录（`direction`
    /// 由 `orderAction` 推导：`BID -> LONG`，否则 `SHORT`），持仓/成本/盈亏清零。
    pub fn initialize(
        &mut self,
        uid: i64,
        symbol: i32,
        currency: i32,
        order_action: OrderAction,
        leverage: i32,
        margin_mode: MarginMode,
    ) {
        self.uid = uid;
        self.symbol = symbol;
        self.currency = currency;

        self.direction = PositionDirection::of_action(order_action);
        self.open_volume = 0;
        self.open_init_margin_sum = 0;
        self.open_price_sum = 0;
        self.profit = 0;

        self.pending_sell_size = 0;
        self.pending_buy_size = 0;

        self.update_leverage(leverage);
        self.margin_mode = margin_mode;
        self.extra_margin = 0;
    }

    /// 对应 Java `updateLeverage(int leverage)`：`0` 归一为 `1`（用户未选 = 默认 1 倍）。
    pub fn update_leverage(&mut self, leverage: i32) {
        self.leverage = if leverage == 0 { 1 } else { leverage };
    }

    /// 对应 Java `isSameLeverage(int leverage)`：按同一 `0 -> 1` 归一规则比较。
    pub fn is_same_leverage(&self, leverage: i32) -> bool {
        self.leverage == if leverage == 0 { 1 } else { leverage }
    }

    /// 对应 Java `isEmpty()`：无挂单、无持仓——拆记录（从 map 移除）的触发条件。
    pub fn is_empty(&self) -> bool {
        self.open_volume == 0 && self.pending_sell_size == 0 && self.pending_buy_size == 0
    }

    /// 对应 Java `reset()`：池复用清零（Rust 无对象池，保留方法以便调用方显式清空复用一条记录）。
    /// 与 `Default::default()` 的区别：`Default` 会把 `uid`/`symbol`/`currency` 也清零，
    /// 而 `reset()` 与 Java 一致——保留 identity 字段不动，只清业务状态。
    pub fn reset(&mut self) {
        self.pending_buy_size = 0;
        self.pending_sell_size = 0;
        self.pending_buy_avg_price = 0;
        self.pending_sell_avg_price = 0;

        self.open_volume = 0;
        self.open_init_margin_sum = 0;
        self.open_price_sum = 0;
        self.direction = PositionDirection::Empty;

        self.update_leverage(0);
        self.margin_mode = MarginMode::Isolated;
        self.extra_margin = 0;
    }

    /// 对应 Java `stateHash()`：`Objects.hash(symbol, currency, direction.getMultiplier(),
    /// openVolume, openInitMarginSum, openPriceSum, profit, pendingSellSize, pendingBuySize,
    /// pendingSellAvgPrice, pendingBuyAvgPrice, leverage, enumStateHash(marginMode),
    /// extraMargin)`——**不含 `uid`**（逐字对齐 Java，非遗漏）。风格对齐
    /// `UserProfile::state_hash`（`h=h*31+field` 滚动折叠）；不保证与 Java 数值相等，只保证
    /// 「同状态 -> 同 hash，不同状态 -> 不同 hash」。
    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.symbol as i64);
        h = h.wrapping_mul(31).wrapping_add(self.currency as i64);
        h = h.wrapping_mul(31).wrapping_add(self.direction.multiplier() as i64);
        h = h.wrapping_mul(31).wrapping_add(self.open_volume);
        h = h.wrapping_mul(31).wrapping_add(self.open_init_margin_sum);
        h = h.wrapping_mul(31).wrapping_add(self.open_price_sum);
        h = h.wrapping_mul(31).wrapping_add(self.profit);
        h = h.wrapping_mul(31).wrapping_add(self.pending_sell_size);
        h = h.wrapping_mul(31).wrapping_add(self.pending_buy_size);
        h = h.wrapping_mul(31).wrapping_add(self.pending_sell_avg_price);
        h = h.wrapping_mul(31).wrapping_add(self.pending_buy_avg_price);
        h = h.wrapping_mul(31).wrapping_add(self.leverage as i64);
        h = h.wrapping_mul(31).wrapping_add(self.margin_mode.code() as i64);
        h = h.wrapping_mul(31).wrapping_add(self.extra_margin);
        ((h >> 32) as i32) ^ (h as i32)
    }
}

/// 对应 Java `positions` map 的 key，形如 `BTreeMap<i32, SymbolPositionRecord>`（HEDGE 模式用
/// ±symbol 区分多空）；本移植归属 `UserProfile`（见 `user_profile.rs`）。
pub type PositionsMapKey = i32;
pub type PositionsMap = BTreeMap<PositionsMapKey, SymbolPositionRecord>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_sets_identity_and_normalizes_leverage() {
        let r = SymbolPositionRecord::new(7, 100, 2, MarginMode::Cross, 0);
        assert_eq!(r.uid, 7);
        assert_eq!(r.symbol, 100);
        assert_eq!(r.currency, 2);
        assert_eq!(r.margin_mode, MarginMode::Cross);
        assert_eq!(r.leverage, 1); // 0 -> 1 归一
        assert_eq!(r.direction, PositionDirection::Empty);
        assert!(r.is_empty());
    }

    #[test]
    fn new_keeps_explicit_leverage() {
        let r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 10);
        assert_eq!(r.leverage, 10);
    }

    #[test]
    fn initialize_sets_direction_from_action_and_clears_state() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 5);
        r.open_volume = 100;
        r.profit = 50;

        r.initialize(2, 3, 4, OrderAction::Bid, 0, MarginMode::Cross);
        assert_eq!(r.uid, 2);
        assert_eq!(r.symbol, 3);
        assert_eq!(r.currency, 4);
        assert_eq!(r.direction, PositionDirection::Long);
        assert_eq!(r.open_volume, 0);
        assert_eq!(r.profit, 0);
        assert_eq!(r.leverage, 1);
        assert_eq!(r.margin_mode, MarginMode::Cross);

        r.initialize(2, 3, 4, OrderAction::Ask, 3, MarginMode::Isolated);
        assert_eq!(r.direction, PositionDirection::Short);
        assert_eq!(r.leverage, 3);
    }

    #[test]
    fn is_same_leverage_normalizes_zero_to_one() {
        let r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        assert!(r.is_same_leverage(0));
        assert!(r.is_same_leverage(1));
        assert!(!r.is_same_leverage(2));
    }

    #[test]
    fn is_empty_true_only_when_no_open_and_no_pending() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        assert!(r.is_empty());

        r.open_volume = 10;
        assert!(!r.is_empty());
        r.open_volume = 0;

        r.pending_sell_size = 5;
        assert!(!r.is_empty());
        r.pending_sell_size = 0;

        r.pending_buy_size = 5;
        assert!(!r.is_empty());
    }

    #[test]
    fn reset_clears_business_state_but_keeps_identity() {
        let mut r = SymbolPositionRecord::new(9, 100, 2, MarginMode::Cross, 5);
        r.open_volume = 10;
        r.open_init_margin_sum = 20;
        r.open_price_sum = 30;
        r.profit = 40;
        r.pending_sell_size = 1;
        r.pending_buy_size = 2;
        r.pending_sell_avg_price = 3;
        r.pending_buy_avg_price = 4;
        r.extra_margin = 5;
        r.direction = PositionDirection::Long;

        r.reset();

        assert_eq!(r.uid, 9); // identity 保留
        assert_eq!(r.symbol, 100);
        assert_eq!(r.currency, 2);

        assert_eq!(r.open_volume, 0);
        assert_eq!(r.open_init_margin_sum, 0);
        assert_eq!(r.open_price_sum, 0);
        // Java `reset()`（`:693-711`）逐字不清 `profit`——已实现盈亏累加器在池复用清零时保留
        // （非遗漏；本移植逐字对齐，不做"看起来更对"的修正）。
        assert_eq!(r.profit, 40);
        assert_eq!(r.pending_sell_size, 0);
        assert_eq!(r.pending_buy_size, 0);
        assert_eq!(r.pending_sell_avg_price, 0);
        assert_eq!(r.pending_buy_avg_price, 0);
        assert_eq!(r.extra_margin, 0);
        assert_eq!(r.direction, PositionDirection::Empty);
        assert_eq!(r.leverage, 1);
        assert_eq!(r.margin_mode, MarginMode::Isolated);
    }

    #[test]
    fn state_hash_deterministic_and_excludes_uid() {
        let a = SymbolPositionRecord::new(1, 100, 2, MarginMode::Isolated, 5);
        let b = SymbolPositionRecord::new(999, 100, 2, MarginMode::Isolated, 5); // uid 不同
        assert_eq!(a.state_hash(), b.state_hash(), "stateHash 逐字对齐 Java：不含 uid");
    }

    #[test]
    fn state_hash_changes_with_business_fields() {
        let base = SymbolPositionRecord::new(1, 100, 2, MarginMode::Isolated, 5);
        let h0 = base.state_hash();

        let mut diff_symbol = base.clone();
        diff_symbol.symbol = 101;
        assert_ne!(h0, diff_symbol.state_hash());

        let mut diff_open_volume = base.clone();
        diff_open_volume.open_volume = 1;
        assert_ne!(h0, diff_open_volume.state_hash());

        let mut diff_margin_mode = base.clone();
        diff_margin_mode.margin_mode = MarginMode::Cross;
        assert_ne!(h0, diff_margin_mode.state_hash());

        let mut diff_extra_margin = base.clone();
        diff_extra_margin.extra_margin = 1;
        assert_ne!(h0, diff_extra_margin.state_hash());
    }

    #[test]
    fn default_is_all_zero_empty() {
        let r = SymbolPositionRecord::default();
        assert_eq!(r.uid, 0);
        assert_eq!(r.leverage, 0); // Default derive 不走 update_leverage 归一，逐字是原始零值
        assert_eq!(r.margin_mode, MarginMode::Isolated);
        assert_eq!(r.direction, PositionDirection::Empty);
        assert!(r.is_empty());
    }
}
