//! 对应 Java: exchange.core2.core.common.CoreSymbolSpecification
//! （现货子集 + P4 Task 1 期货保证金字段扩展：symbolId/type/base+quote currency/
//! base+quoteScaleK/taker+makerFee/feeScaleK + initMargin/maintenanceMargin/maxLeverage；
//! loan 相关字段本期不移植）。
use std::collections::BTreeMap;

use crate::core::common::symbol_type::SymbolType;
use crate::core::utils::core_arithmetic_utils::{ceil_mul_div, trunc_mul_div};

/// 对应 Java `CoreSymbolSpecification`（现货子集 + 期货保证金字段）。
///
/// Ruling P4-B：`#[derive(Default)]` 让新增的期货字段（`init_margin` 等）在所有既有 P1-P3
/// 构造点上零值兜底——spot symbol 不填这些字段即代表"未配置期货保证金"（`calculate_init_margin`
/// /`calculate_maintenance_margin`/`is_valid_leverage` 在字段为 0/空表时的行为分别是：
/// 100% 初始保证金率、100% 维持保证金率、不限杠杆上限）。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct CoreSymbolSpecification {
    pub symbol_id: i32,
    pub symbol_type: SymbolType,
    pub base_currency: i32,
    pub quote_currency: i32,
    pub base_scale_k: i64,
    pub quote_scale_k: i64,
    pub taker_fee: i64,
    pub maker_fee: i64,
    pub fee_scale_k: i64,

    // ================================================================
    // 期货：保证金（type=FUTURES_CONTRACT_* only；spot symbol 全 0/空）
    // ================================================================
    pub init_margin: i64,
    pub init_margin_scale_k: i64,
    /// <notional, maintenanceMargin>，按 notional floor 分档；`BTreeMap` 天然有序。
    pub maintenance_margin: BTreeMap<i64, i64>,
    pub maintenance_margin_scale_k: i64,
    /// <notional, maxLeverage>，按 notional floor 分档。
    pub max_leverage: BTreeMap<i64, i64>,
}

impl CoreSymbolSpecification {
    /// 对应 Java `CoreSymbolSpecification.isFixedFee()`：`feeScaleK == 0` 表示固定费用（非比例费率）。
    pub fn is_fixed_fee(&self) -> bool {
        self.fee_scale_k == 0
    }

    /// 对应 Java `calculateInitMargin(long notional, long leverage)`（`:135-141`）：
    /// 初始保证金 = 名义价值 × 初始保证金率 / 杠杆。
    /// `init_margin_scale_k == 0 || init_margin == 0`（未配置）时按 100% 初始保证金率处理，
    /// 即 `notional / leverage`；否则 `ceil(notional × init_margin / (init_margin_scale_k × leverage))`。
    pub fn calculate_init_margin(&self, notional: i64, leverage: i64) -> i64 {
        if self.init_margin_scale_k == 0 || self.init_margin == 0 {
            return notional / leverage;
        }
        let denom = self
            .init_margin_scale_k
            .checked_mul(leverage)
            .unwrap_or_else(|| panic!("overflow: init_margin_scale_k * leverage"));
        ceil_mul_div(notional, self.init_margin, denom)
    }

    /// 对应 Java `calculateMaintenanceMargin(long notional)`（`:150-174`）：维持保证金 =
    /// 各分档段 seg × MMR_seg / scaleK 之和。`maintenance_margin` 以 (floor, MMR) 分档存表，
    /// 仓位跨档时逐段累加；notional 小于最小 floor 时用最小档 rate 整段兜底；表空或
    /// `scaleK == 0` 时按 100% 返回 notional。
    pub fn calculate_maintenance_margin(&self, notional: i64) -> i64 {
        if self.maintenance_margin_scale_k == 0 || self.maintenance_margin.is_empty() {
            return notional;
        }
        let mut iter = self.maintenance_margin.iter();
        let (&first_floor, &first_rate) = iter.next().expect("non-empty checked above");
        if notional <= first_floor {
            return trunc_mul_div(notional, first_rate, self.maintenance_margin_scale_k);
        }
        let mut mm: i64 = 0;
        let mut prev_floor: i64 = 0;
        let mut prev_rate: i64 = first_rate;
        for (&floor, &rate) in self.maintenance_margin.iter() {
            let seg = notional.min(floor) - prev_floor;
            mm += trunc_mul_div(seg, prev_rate, self.maintenance_margin_scale_k);
            if notional <= floor {
                return mm;
            }
            prev_floor = floor;
            prev_rate = rate;
        }
        mm + trunc_mul_div(notional - prev_floor, prev_rate, self.maintenance_margin_scale_k)
    }

    /// 对应 Java `isValidLeverage(long notional, int leverage)`（`:118-130`）：
    /// 负杠杆一律非法；`max_leverage` 未配档（空表）时不限上限；否则按 notional 做 floor 分档
    /// 查表，`leverage <= max_leverage_value` 才合法。
    pub fn is_valid_leverage(&self, notional: i64, leverage: i32) -> bool {
        if leverage < 0 {
            return false;
        }
        if self.max_leverage.is_empty() {
            return true;
        }
        match Self::floor_value(&self.max_leverage, notional) {
            Some(max_leverage_value) => (leverage as i64) <= max_leverage_value,
            None => true,
        }
    }

    /// 对应 Java `getFloorValueInSortedMap`：`headMap(key)`（**严格小于** `key`，不含 `key`
    /// 本身）非空时取其最大 key 对应的值；为空（`key` 小于等于表中最小 floor）时退化取表中
    /// 最小 floor 的值。注意 `key` 恰好等于某个 floor 时也走"严格小于"分支——逐字对齐 Java
    /// 原始实现（非"标准 `<=` floor 查找"，是 Java 既有行为，不做"修正"）。
    fn floor_value(map: &BTreeMap<i64, i64>, key: i64) -> Option<i64> {
        match map.range(..key).next_back() {
            Some((_, &v)) => Some(v),
            None => map.iter().next().map(|(_, &v)| v),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spot_spec(fee_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 1,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 10,
            maker_fee: 5,
            fee_scale_k,
            ..Default::default()
        }
    }

    fn futures_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 1,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        }
    }

    #[test]
    fn is_fixed_fee_true_when_fee_scale_k_zero() {
        assert!(spot_spec(0).is_fixed_fee());
    }

    #[test]
    fn is_fixed_fee_false_when_fee_scale_k_positive() {
        assert!(!spot_spec(10_000).is_fixed_fee());
    }

    #[test]
    fn default_futures_fields_are_zero_and_empty() {
        let s = spot_spec(0);
        assert_eq!(s.init_margin, 0);
        assert_eq!(s.init_margin_scale_k, 0);
        assert!(s.maintenance_margin.is_empty());
        assert_eq!(s.maintenance_margin_scale_k, 0);
        assert!(s.max_leverage.is_empty());
    }

    // ------------------------------------------------------------------
    // calculate_init_margin — Java `:135-141`
    // ------------------------------------------------------------------

    #[test]
    fn calculate_init_margin_default_notional_over_leverage_when_unconfigured() {
        let spec = futures_spec();
        assert_eq!(spec.calculate_init_margin(10_000, 10), 1_000);
    }

    #[test]
    fn calculate_init_margin_proportional_tier_ceils() {
        let mut spec = futures_spec();
        spec.init_margin = 500; // 5% at scale 10_000 (500/10_000 = 0.05)
        spec.init_margin_scale_k = 10_000;
        // notional=10_000, leverage=3: ceil(10_000*500/(10_000*3)) = ceil(500/3) = ceil(166.67)=167
        assert_eq!(spec.calculate_init_margin(10_000, 3), 167);
    }

    #[test]
    fn calculate_init_margin_zero_init_margin_falls_back_to_default() {
        let mut spec = futures_spec();
        spec.init_margin = 0;
        spec.init_margin_scale_k = 10_000; // 有 scaleK 但 init_margin=0：仍按默认处理
        assert_eq!(spec.calculate_init_margin(10_000, 5), 2_000);
    }

    // ------------------------------------------------------------------
    // calculate_maintenance_margin — Java `:150-174`
    // ------------------------------------------------------------------

    #[test]
    fn calculate_maintenance_margin_returns_notional_when_unconfigured() {
        let spec = futures_spec();
        assert_eq!(spec.calculate_maintenance_margin(12_345), 12_345);
    }

    #[test]
    fn calculate_maintenance_margin_below_first_floor_uses_first_rate_flat() {
        let mut spec = futures_spec();
        spec.maintenance_margin_scale_k = 10_000;
        spec.maintenance_margin.insert(50_000, 50); // 0.5% up to 50_000
        spec.maintenance_margin.insert(200_000, 100); // 1% up to 200_000
        // notional=10_000 <= firstFloor(50_000): trunc(10_000*50/10_000)=50
        assert_eq!(spec.calculate_maintenance_margin(10_000), 50);
    }

    #[test]
    fn calculate_maintenance_margin_tiers_across_segments() {
        let mut spec = futures_spec();
        spec.maintenance_margin_scale_k = 10_000;
        spec.maintenance_margin.insert(50_000, 50); // tier1 rate 0.5%
        spec.maintenance_margin.insert(200_000, 100); // tier2 rate 1%
        // Java 循环体在算完某个 floor 对应的 segment 之后，才把 `prevRate` 更新成该 floor 自己
        // 的 rate（供下一轮使用）——即每一档的 rate 实际生效区间是 `[本档 floor, 下一档 floor]`，
        // 比配置表面上看起来的"到本档 floor 为止"晚一档兑现（首段 `[0, firstFloor]` 例外，直接
        // 用 `firstRate` 兜底，故首段与第二段恰好用了同一个 rate）。逐字对齐 Java `:150-174`
        // 循环体，不是"修正"过的直觉分档语义。
        //
        // notional=100_000 逐步推演：
        //   entry(50_000,50): seg=min(100_000,50_000)-0=50_000, mm+=trunc(50_000*50/10_000)=250;
        //                      100_000>50_000 继续；prevFloor=50_000, prevRate=50（本档 rate）
        //   entry(200_000,100): seg=min(100_000,200_000)-50_000=50_000,
        //                      mm+=trunc(50_000*prevRate(50，非 100)/10_000)=250 -> mm=500；
        //                      100_000<=200_000 -> return 500
        assert_eq!(spec.calculate_maintenance_margin(100_000), 500);
    }

    #[test]
    fn calculate_maintenance_margin_beyond_last_tier_uses_last_rate() {
        let mut spec = futures_spec();
        spec.maintenance_margin_scale_k = 10_000;
        spec.maintenance_margin.insert(50_000, 50);
        spec.maintenance_margin.insert(200_000, 100);
        // notional=300_000 逐步推演（同上"rate 晚一档兑现"规则）：
        //   entry(50_000,50): seg=50_000-0=50_000, mm+=trunc(50_000*50/10_000)=250;
        //                      prevFloor=50_000, prevRate=50
        //   entry(200_000,100): seg=min(300_000,200_000)-50_000=150_000,
        //                      mm+=trunc(150_000*50/10_000)=750 -> mm=1000（仍用上一档 rate=50）；
        //                      300_000>200_000 继续；prevFloor=200_000, prevRate=100（本档 rate，
        //                      循环无更多 entry，留给收尾段用）
        //   收尾：mm + trunc(300_000-200_000, 100, 10_000) = 1000 + 1000 = 2000
        assert_eq!(spec.calculate_maintenance_margin(300_000), 2000);
    }

    // ------------------------------------------------------------------
    // is_valid_leverage — Java `:118-130`
    // ------------------------------------------------------------------

    #[test]
    fn is_valid_leverage_negative_leverage_always_invalid() {
        let spec = futures_spec();
        assert!(!spec.is_valid_leverage(1_000, -1));
    }

    #[test]
    fn is_valid_leverage_unconfigured_table_allows_any_nonnegative_leverage() {
        let spec = futures_spec();
        assert!(spec.is_valid_leverage(1_000_000, 125));
    }

    #[test]
    fn is_valid_leverage_floor_lookup_strictly_less_than_key() {
        let mut spec = futures_spec();
        spec.max_leverage.insert(0, 125);
        spec.max_leverage.insert(50_000, 50);
        spec.max_leverage.insert(200_000, 20);

        // notional=10_000: headMap(10_000)={0} -> maxLeverage=125
        assert!(spec.is_valid_leverage(10_000, 125));
        assert!(!spec.is_valid_leverage(10_000, 126));

        // notional==50_000 恰好等于某 floor：走"严格小于" -> headMap(50_000)={0} -> 125（非
        // 50_000 档的 50，逐字对齐 Java 非直觉的边界行为）
        assert!(spec.is_valid_leverage(50_000, 125));

        // notional=50_001: headMap={0,50_000} -> lastKey=50_000 -> maxLeverage=50
        assert!(spec.is_valid_leverage(50_001, 50));
        assert!(!spec.is_valid_leverage(50_001, 51));
    }

    #[test]
    fn is_valid_leverage_key_below_min_floor_falls_back_to_min_floor_value() {
        let mut spec = futures_spec();
        spec.max_leverage.insert(10_000, 100);
        spec.max_leverage.insert(50_000, 50);
        // notional=0 < 所有 floor：headMap 为空 -> 退化取最小 floor(10_000) 的值 100
        assert!(spec.is_valid_leverage(0, 100));
        assert!(!spec.is_valid_leverage(0, 101));
    }
}
