//! 对应 Java: `exchange.core2.core.processors.liquidation.LiquidationService`（376 行）。
//! 参考文档 §2.1/§2.3。
//!
//! 保险基金（IF）复制状态：per-shard 单例，`RiskEngine` 持有（`RiskEngine.loan_service` 同级
//! 字段）。**`notionals`/`positions` 进 state_hash/snapshot**——与 Java 版本
//! `symbolToUsers`（非复制索引，本移植 Task 7 排期）/ `LiquidationFlow`（同上）不同，这两个桶是
//! IF 的真实资金池状态，必须跨节点强一致（Ruling P6-E）。
//!
//! # 未移植：orderId 编码 / ADL 候选构造（Task 7 排期）
//! Java 版本另有 `generateLiquidationOrderId`/`generateIFOrderId`/`generateADLOrderId`/
//! `isLiquidationOrderId`（静态 orderId 位编码工具）与 `computeProfitablePositionsBySymbol`/
//! `addCrossPositionsIfUserSafe`/`addProfitablePosition`（ADL 候选扫描，依赖
//! `UserProfileService`/`SymbolSpecificationProvider`/`lastPriceCache` 三个 provider，Java 版本
//! 经 `updateProvider` 注入）。这些函数的唯一调用方是 `LiquidationEngine`
//! （`buildForceCmd`/`buildIFCmd`/`buildADLCmd`/`advanceLiquidation`——参考文档 §1，Task 7 排期），
//! `IFCommandProcessor`（本 Task 范围）自身从不调用它们——本文件不落地，Task 7 落地时回来补齐。
//! `riskScore`/`unrealizedPnl`/`saturatingMultiply` 同理（ADL 排序键，`LiquidationEngine`/
//! `ADLCommandProcessor` 消费，Task 6/7 排期）。

use std::collections::BTreeMap;

use crate::core::common::position_direction::PositionDirection;

/// 对应 Java `Math.multiplyExact(long, long)`：局部私有重复一份，同仓库既有 helper 风格
/// （`funding_fee_command_processor.rs`/`risk_engine.rs` 同名 helper）。
fn mul_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

/// 对应 Java `LiquidationService.IFNotional`：IF 单 symbol 名义资金。`available` 可动用，
/// `reserved` 是强平流程中的预冻结部分（R1 `reserve_if_notional` 写入，R2 finalize
/// `release_reserved_if_notional` 释放，与真实扣款 `accept_if_position` 是两条独立记账线，见
/// [`LiquidationService`] 模块文档）。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct IfNotional {
    pub available: i64,
    pub reserved: i64,
}

impl IfNotional {
    /// 折叠进父结构 `LiquidationService::state_hash` 的 rolling hash（风格对齐
    /// `UserProfile::state_hash`/`LoanService::state_hash`：`h=h*31+field`，不保证与 Java
    /// `Objects.hash` 数值相等，只保证「同状态 -> 同 hash，不同状态 -> 不同 hash」）。
    fn fold_hash(&self, h: i64) -> i64 {
        let h = h.wrapping_mul(31).wrapping_add(self.available);
        h.wrapping_mul(31).wrapping_add(self.reserved)
    }
}

/// 对应 Java `LiquidationService.IFPositionRecord`：IF 自身接管仓位——某 symbol+方向 累计接管的
/// 持仓量与开仓成本（反向出清估值用，Task 7 ADL/清算编排消费）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IfPositionRecord {
    pub symbol: i32,
    pub direction: PositionDirection,
    pub open_volume: i64,
    pub open_price_sum: i64,
}

impl IfPositionRecord {
    fn fold_hash(&self, h: i64) -> i64 {
        // direction 用 multiplier()（已是 i32）保持跨节点/跨版本稳定，同 Java 注释
        // "direction 是 enum：用 multiplier 保持跨 JVM 稳定"。
        let h = h.wrapping_mul(31).wrapping_add(self.symbol as i64);
        let h = h.wrapping_mul(31).wrapping_add(self.direction.multiplier() as i64);
        let h = h.wrapping_mul(31).wrapping_add(self.open_volume);
        h.wrapping_mul(31).wrapping_add(self.open_price_sum)
    }
}

/// 对应 Java `LiquidationService`（IF 状态职责子集——orderId 编码 / ADL 候选构造见模块文档
/// "未移植"一节）。
///
/// - `notionals`：`symbol -> IFNotional{available, reserved}`——`available` 是撮合内部乘积单位
///   （`size*price`，即 sizePrice scale，非币种记账单位）下的可动用 IF 余额。
/// - `positions`：`(direction.multiplier() * symbol) -> IFPositionRecord`——key 用符号编码区分
///   同 symbol 的多/空两条独立持仓记录（brief 明确 key 类型 `i64`，虽然 Java 原版
///   `IntObjectHashMap` 是 `int` key；`i64` 宽域不改变语义，`direction.multiplier() ∈ {-1,0,1}`
///   与 `symbol: i32` 相乘在两种宽度下数值恒等）。
#[derive(Debug, Clone, Default)]
pub struct LiquidationService {
    pub notionals: BTreeMap<i32, IfNotional>,
    pub positions: BTreeMap<i64, IfPositionRecord>,
}

impl LiquidationService {
    pub fn new() -> Self {
        LiquidationService::default()
    }

    /// 对应 Java `creditLiquidationFee`：强平手续费计入 IF 可用资金池（Task 7
    /// `collectLiquidationFee` 消费；本 Task 只落地这个记账原语本身）。
    pub fn credit_liquidation_fee(&mut self, symbol: i32, notional_fee: i64) {
        let n = self.notionals.entry(symbol).or_default();
        n.available += notional_fee;
    }

    /// 对应 Java `depositToInsuranceFund`：外部充值 IF 可用资金池（admin `IF_DEPOSIT`）。入参已是
    /// notional（size*price）尺度，scale 换算由调用方（`RiskEngine::if_deposit`）完成。
    pub fn deposit_to_insurance_fund(&mut self, symbol: i32, notional_amount: i64) {
        let n = self.notionals.entry(symbol).or_default();
        n.available += notional_amount;
    }

    /// 对应 Java `withdrawFromInsuranceFund`：`IF_WITHDRAW` 支持——从 `available` 扣款，含非负
    /// 校验。只扣 `available`、不动 `reserved`（reserved 是正在保护某笔强平的预冻结部分，运营不能
    /// 拿走）。`false` = notional 不存在或 `available` 不足以覆盖（调用方据此返回
    /// `RiskIfInsufficient`）。
    pub fn withdraw_from_insurance_fund(&mut self, symbol: i32, notional_amount: i64) -> bool {
        let Some(n) = self.notionals.get_mut(&symbol) else {
            return false;
        };
        if n.available < notional_amount {
            return false;
        }
        n.available -= notional_amount;
        true
    }

    /// 对应 Java `reserveIFNotional`（R1）：预冻结 IF 可用名义金额，返回实际能冻结的量
    /// （`min(available - reserved, requestSize * price)`）——**自限、永不为负**：不管请求多大，
    /// 最多只冻结当前真实可用的部分，caller 永远不会把 IF 推向负数（对比 loan LIF 允许为负，
    /// 参考文档 §2.3 "natural braking"）。
    pub fn reserve_if_notional(&mut self, symbol: i32, request_size: i64, price: i64) -> i64 {
        let n = self.notionals.entry(symbol).or_default();
        let available = n.available - n.reserved;
        let needed = mul_exact(request_size, price);
        let can_cover = available.min(needed);
        n.reserved += can_cover;
        can_cover
    }

    /// 对应 Java `releaseReservedIFNotional`（R2 finalize）：释放 R1 预冻结的名义金额，与
    /// `reserve_if_notional` 对称——`IFCommandProcessor::finalize` 无论接管成功/全拒都调用它
    /// （参考文档 §2.2 "always release"）。
    pub fn release_reserved_if_notional(&mut self, symbol: i32, reserved_notional: i64) {
        if let Some(n) = self.notionals.get_mut(&symbol) {
            n.reserved -= reserved_notional;
        }
    }

    /// 对应 Java `acceptIFPosition`（R2 per-event）：IF 正式接管仓位——从 `available` 扣款，累加到
    /// 该 symbol+方向 的持仓量与成本。要求 `notionals[symbol]` 已存在（由同一条命令的 R1
    /// `reserve_if_notional` 保证，同 Java `notionals.get(symbol)` 的隐式非空契约——若在此之前从未
    /// reserve 过，说明调用方违反了 R1→R2 顺序契约，panic 而非静默创建虚假余额）。
    pub fn accept_if_position(&mut self, symbol: i32, direction: PositionDirection, size: i64, price: i64) {
        let spend = mul_exact(size, price);
        let n = self
            .notionals
            .get_mut(&symbol)
            .unwrap_or_else(|| panic!("accept_if_position: no IFNotional reserved for symbol {symbol}"));
        n.available -= spend;

        let key = (direction.multiplier() as i64) * (symbol as i64);
        let pos = self.positions.entry(key).or_insert_with(|| IfPositionRecord {
            symbol,
            direction,
            open_volume: 0,
            open_price_sum: 0,
        });
        pos.open_volume += size;
        pos.open_price_sum += spend;
    }

    /// 对应 Java `reset`：清空全部 IF 状态（测试/重建用）。
    pub fn reset(&mut self) {
        self.notionals.clear();
        self.positions.clear();
    }

    /// 对应 Java `stateHash`：`notionals`/`positions` 都进复制态 hash（Ruling P6-E）。风格对齐
    /// `LoanService::state_hash`（`h=h*31+field` 滚动折叠 + 高低 32 位异或收窄）。
    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        for (&symbol, n) in &self.notionals {
            h = h.wrapping_mul(31).wrapping_add(symbol as i64);
            h = n.fold_hash(h);
        }
        for (&key, p) in &self.positions {
            h = h.wrapping_mul(31).wrapping_add(key);
            h = p.fold_hash(h);
        }
        ((h >> 32) as i32) ^ (h as i32)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- credit_liquidation_fee / deposit / withdraw ----

    #[test]
    fn credit_liquidation_fee_accumulates_into_available_not_reserved() {
        let mut s = LiquidationService::new();
        s.credit_liquidation_fee(1, 100);
        s.credit_liquidation_fee(1, 50);
        assert_eq!(s.notionals[&1], IfNotional { available: 150, reserved: 0 });
    }

    #[test]
    fn deposit_to_insurance_fund_accumulates_into_available() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);
        s.deposit_to_insurance_fund(1, 500);
        assert_eq!(s.notionals[&1].available, 1_500);
    }

    #[test]
    fn withdraw_from_insurance_fund_debits_available_only() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);
        s.reserve_if_notional(1, 10, 10); // reserved = 100, available 不变
        assert!(s.withdraw_from_insurance_fund(1, 300));
        assert_eq!(s.notionals[&1], IfNotional { available: 700, reserved: 100 }, "只扣 available，不动 reserved");
    }

    #[test]
    fn withdraw_from_insurance_fund_rejects_when_missing_or_insufficient() {
        let mut s = LiquidationService::new();
        assert!(!s.withdraw_from_insurance_fund(1, 1), "从未 deposit 过的 symbol -> false");

        s.deposit_to_insurance_fund(2, 100);
        assert!(!s.withdraw_from_insurance_fund(2, 101), "available 不足 -> false，不能透支");
        assert_eq!(s.notionals[&2].available, 100, "拒绝的提取不改状态");
    }

    // ---- reserve_if_notional：min 上限 + IF 永不为负 ----

    #[test]
    fn reserve_if_notional_caps_at_available_never_over_promises() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000); // available=1000
        let cover = s.reserve_if_notional(1, 100, 20); // needed = 2000 > available
        assert_eq!(cover, 1_000, "只能冻结当前真实可用的部分，不会超额承诺");
        assert_eq!(s.notionals[&1], IfNotional { available: 1_000, reserved: 1_000 });
    }

    #[test]
    fn reserve_if_notional_never_goes_negative_across_repeated_reserves() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 500);
        let c1 = s.reserve_if_notional(1, 10, 40); // needed=400, cover=min(500,400)=400
        assert_eq!(c1, 400);
        let c2 = s.reserve_if_notional(1, 10, 40); // available-reserved=100, needed=400, cover=100
        assert_eq!(c2, 100, "第二次 reserve 只能拿走剩余可用部分（自限，不会让 available 变负）");
        assert!(s.notionals[&1].available - s.notionals[&1].reserved >= 0, "IF 永不为负");
    }

    #[test]
    fn reserve_if_notional_exact_cover_when_sufficient() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 10_000);
        let cover = s.reserve_if_notional(1, 5, 100); // needed = 500 <= available
        assert_eq!(cover, 500);
    }

    // ---- release_reserved_if_notional ----

    #[test]
    fn release_reserved_if_notional_is_symmetric_with_reserve() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);
        let cover = s.reserve_if_notional(1, 5, 100);
        s.release_reserved_if_notional(1, cover);
        assert_eq!(s.notionals[&1], IfNotional { available: 1_000, reserved: 0 }, "释放后 reserved 归零，available 不受影响");
    }

    #[test]
    fn release_reserved_if_notional_missing_symbol_is_noop() {
        let mut s = LiquidationService::new();
        s.release_reserved_if_notional(99, 100); // 不 panic，静默 no-op（对应 Java notionals.get==null 时 NPE 前置不可达，这里防御性放行）
        assert!(!s.notionals.contains_key(&99));
    }

    // ---- accept_if_position ----

    #[test]
    fn accept_if_position_debits_available_and_accumulates_position() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 10_000);
        s.reserve_if_notional(1, 5, 100); // reserved=500
        s.accept_if_position(1, PositionDirection::Long, 5, 100);

        assert_eq!(s.notionals[&1].available, 10_000 - 500, "available 按 size*price 真实扣款");
        let key = 1i64; // Long.multiplier()=1 * symbol=1
        assert_eq!(s.positions[&key], IfPositionRecord { symbol: 1, direction: PositionDirection::Long, open_volume: 5, open_price_sum: 500 });
    }

    #[test]
    fn accept_if_position_long_and_short_same_symbol_do_not_collide() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(7, 100_000);
        s.reserve_if_notional(7, 10, 50);
        s.accept_if_position(7, PositionDirection::Long, 10, 50);
        s.reserve_if_notional(7, 4, 50);
        s.accept_if_position(7, PositionDirection::Short, 4, 50);

        assert_eq!(s.positions[&7i64].open_volume, 10);
        assert_eq!(s.positions[&(-7i64)].open_volume, 4);
        assert_eq!(s.positions.len(), 2, "符号编码 key 防止多空两条记录互相覆盖");
    }

    #[test]
    fn accept_if_position_accumulates_across_multiple_calls_same_direction() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 100_000);
        s.reserve_if_notional(1, 5, 100);
        s.accept_if_position(1, PositionDirection::Long, 5, 100);
        s.reserve_if_notional(1, 3, 100);
        s.accept_if_position(1, PositionDirection::Long, 3, 100);

        let key = 1i64;
        assert_eq!(s.positions[&key].open_volume, 8);
        assert_eq!(s.positions[&key].open_price_sum, 800);
    }

    #[test]
    #[should_panic(expected = "no IFNotional reserved")]
    fn accept_if_position_without_prior_reserve_panics() {
        let mut s = LiquidationService::new();
        s.accept_if_position(1, PositionDirection::Long, 1, 1);
    }

    // ---- reset ----

    #[test]
    fn reset_clears_both_buckets() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 100);
        s.reserve_if_notional(1, 1, 10);
        s.accept_if_position(1, PositionDirection::Long, 1, 10);
        s.reset();
        assert!(s.notionals.is_empty());
        assert!(s.positions.is_empty());
    }

    // ---- state_hash ----

    #[test]
    fn state_hash_deterministic_for_same_state() {
        let mut a = LiquidationService::new();
        a.deposit_to_insurance_fund(1, 100);
        let mut b = LiquidationService::new();
        b.deposit_to_insurance_fund(1, 100);
        assert_eq!(a.state_hash(), b.state_hash());
    }

    #[test]
    fn state_hash_changes_when_notionals_change() {
        let base = LiquidationService::new();
        let h0 = base.state_hash();

        let mut deposited = LiquidationService::new();
        deposited.deposit_to_insurance_fund(1, 1);
        assert_ne!(h0, deposited.state_hash(), "available 变化必须反映到 hash");

        let mut reserved = LiquidationService::new();
        reserved.deposit_to_insurance_fund(1, 100);
        let h_before_reserve = reserved.state_hash();
        reserved.reserve_if_notional(1, 1, 1);
        assert_ne!(h_before_reserve, reserved.state_hash(), "reserved 变化必须反映到 hash（即使 available 不变）");
    }

    #[test]
    fn state_hash_changes_when_positions_change() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 10_000);
        let h0 = s.state_hash();
        s.reserve_if_notional(1, 5, 100);
        let h1 = s.state_hash();
        s.accept_if_position(1, PositionDirection::Long, 5, 100);
        let h2 = s.state_hash();
        assert_ne!(h0, h1, "reserve 阶段先改变 notionals hash");
        assert_ne!(h1, h2, "accept 阶段新增 positions 条目必须改变 hash");
    }
}
