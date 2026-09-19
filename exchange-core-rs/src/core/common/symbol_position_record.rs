use std::collections::BTreeMap;

use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::position_direction::PositionDirection;
use crate::core::processors::liquidation::liquidation_flow::LiquidationFlow;
use crate::core::utils::core_arithmetic_utils::{add_exact, calculate_taker_fee, ceil_divide, ceil_mul_div, mul_exact, sub_exact, trunc_mul_div};

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SymbolPositionRecord {
    pub uid: i64,
    pub symbol: i32,
    pub currency: i32,
    pub direction: PositionDirection,
    pub open_volume: i64,
    pub open_init_margin_sum: i64,
    pub open_price_sum: i64,
    pub profit: i64,
    pub pending_sell_size: i64,
    pub pending_buy_size: i64,
    pub pending_sell_avg_price: i64,
    pub pending_buy_avg_price: i64,
    pub leverage: i32,
    pub margin_mode: MarginMode,
    pub extra_margin: i64,
    pub pending_adl_size: i64,
    pub adl_eligibility: i64,
    pub liquidation_flow: Option<LiquidationFlow>,
}

impl SymbolPositionRecord {

    pub fn new(uid: i64, symbol: i32, currency: i32, margin_mode: MarginMode, leverage: i32) -> Self {
        let mut r = SymbolPositionRecord { uid, symbol, currency, margin_mode, ..Default::default() };
        r.update_leverage(leverage);
        r.adl_eligibility = if margin_mode == MarginMode::Isolated { 100 } else { 0 };
        r
    }

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
        self.adl_eligibility = if margin_mode == MarginMode::Isolated { 100 } else { 0 };
        self.pending_adl_size = 0;
        self.liquidation_flow = None;
    }

    pub fn update_leverage(&mut self, leverage: i32) {
        self.leverage = if leverage == 0 { 1 } else { leverage };
    }

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
        self.adl_eligibility = 100;
        self.pending_adl_size = 0;
        self.liquidation_flow = None;
    }

    pub fn pending_hold(&mut self, order_action: OrderAction, size: i64, price: i64) {
        match order_action {
            OrderAction::Ask => {
                self.pending_sell_avg_price =
                    Self::calculate_avg_price(self.pending_sell_avg_price, self.pending_sell_size, price, size);
                self.pending_sell_size = add_exact(self.pending_sell_size, size);
            }
            OrderAction::Bid => {
                self.pending_buy_avg_price =
                    Self::calculate_avg_price(self.pending_buy_avg_price, self.pending_buy_size, price, size);
                self.pending_buy_size = add_exact(self.pending_buy_size, size);
            }
        }
    }

    pub fn pending_hold_budget(&mut self, order_action: OrderAction, size: i64, budget_notional: i64) {
        match order_action {
            OrderAction::Ask => {
                let new_size = add_exact(self.pending_sell_size, size);
                if new_size <= 0 {
                    return;
                }
                let pending_notional = add_exact(
                    mul_exact(self.pending_sell_avg_price, self.pending_sell_size),
                    budget_notional,
                );
                self.pending_sell_avg_price = ceil_divide(pending_notional, new_size);
                self.pending_sell_size = new_size;
            }
            OrderAction::Bid => {
                let new_size = add_exact(self.pending_buy_size, size);
                if new_size <= 0 {
                    return;
                }
                let pending_notional = add_exact(
                    mul_exact(self.pending_buy_avg_price, self.pending_buy_size),
                    budget_notional,
                );
                self.pending_buy_avg_price = ceil_divide(pending_notional, new_size);
                self.pending_buy_size = new_size;
            }
        }
    }

    pub fn pending_release(&mut self, order_action: OrderAction, size: i64) -> i64 {
        match order_action {
            OrderAction::Ask => {
                let released = self.pending_sell_size.min(size);
                self.pending_sell_size -= released;
                if self.pending_sell_size == 0 {
                    self.pending_sell_avg_price = 0;
                }
                released
            }
            OrderAction::Bid => {
                let released = self.pending_buy_size.min(size);
                self.pending_buy_size -= released;
                if self.pending_buy_size == 0 {
                    self.pending_buy_avg_price = 0;
                }
                released
            }
        }
    }

    pub fn close_current_position_futures(&mut self, action: OrderAction, trade_size: i64, trade_price: i64) -> i64 {
        if self.open_volume == 0 || self.direction == PositionDirection::of_action(action) {
            return trade_size;
        }

        if self.open_volume > trade_size {

            let margin_release = trunc_mul_div(self.open_init_margin_sum, trade_size, self.open_volume);
            self.open_init_margin_sum = sub_exact(self.open_init_margin_sum, margin_release);
            self.open_volume -= trade_size;
            self.open_price_sum = sub_exact(self.open_price_sum, mul_exact(trade_size, trade_price));
            return 0;
        }

        let close_notional = mul_exact(self.open_volume, trade_price);
        let pnl_raw = sub_exact(close_notional, self.open_price_sum);
        let pnl_signed = mul_exact(pnl_raw, self.direction.multiplier() as i64);
        self.profit = add_exact(self.profit, pnl_signed);
        self.open_init_margin_sum = 0;
        self.open_price_sum = 0;
        let size_to_open = sub_exact(trade_size, self.open_volume);
        self.open_volume = 0;

        size_to_open
    }

    pub fn open_position_margin(
        &mut self,
        action: OrderAction,
        size_to_open: i64,
        trade_price: i64,
        spec: &CoreSymbolSpecification,
        mark_price: i64,
    ) {
        let open_notional = mul_exact(mark_price, size_to_open);
        let init_margin_delta = spec.calculate_init_margin(open_notional, self.leverage as i64);
        let price_notional = mul_exact(trade_price, size_to_open);
        self.open_volume = add_exact(self.open_volume, size_to_open);
        self.open_init_margin_sum = add_exact(self.open_init_margin_sum, init_margin_delta);
        self.open_price_sum = add_exact(self.open_price_sum, price_notional);
        self.direction = PositionDirection::of_action(action);
    }

    pub fn is_same_leverage(&self, leverage: i32) -> bool {
        self.leverage == if leverage == 0 { 1 } else { leverage }
    }

    pub fn is_empty(&self) -> bool {
        self.open_volume == 0 && self.pending_sell_size == 0 && self.pending_buy_size == 0
    }

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

    pub fn calculate_bankruptcy_price(
        &self,
        spec: &CoreSymbolSpecification,
        margin_base_fn: impl Fn(&SymbolPositionRecord) -> i64,
    ) -> i64 {
        let margin_base = match self.margin_mode {
            MarginMode::Isolated => add_exact(self.open_init_margin_sum, self.extra_margin),
            MarginMode::Cross => margin_base_fn(self),
        };
        let sign = self.direction.multiplier() as i64;
        let total_fee = add_exact(spec.taker_fee, spec.liquidation_fee);
        if spec.is_fixed_fee() {
            let max_loss = sub_exact(margin_base, mul_exact(total_fee, self.open_volume));
            let numer = sub_exact(self.open_price_sum, mul_exact(sign, max_loss));
            ceil_divide(numer, self.open_volume)
        } else {
            let numer = sub_exact(self.open_price_sum, mul_exact(sign, margin_base));
            let denom = mul_exact(self.open_volume, sub_exact(spec.fee_scale_k, mul_exact(sign, total_fee)));
            ceil_mul_div(numer, spec.fee_scale_k, denom)
        }
    }

    pub fn estimate_pnl(&self, mark_price: i64) -> i64 {
        add_exact(self.profit, self.estimate_unrealized_profit(mark_price))
    }

    pub fn estimate_unrealized_profit(&self, mark_price: i64) -> i64 {
        let notional = mul_exact(self.open_volume, mark_price);
        let delta = sub_exact(notional, self.open_price_sum);
        mul_exact(self.direction.multiplier() as i64, delta)
    }

    pub fn calculate_maintenance_margin(&self, spec: &CoreSymbolSpecification, mark_price: i64) -> i64 {
        if self.open_volume == 0 {
            return 0;
        }
        let notional = mul_exact(self.open_volume, mark_price);
        spec.calculate_maintenance_margin(notional)
    }

    pub fn estimate_liquidation_price(
        &self,
        spec: &CoreSymbolSpecification,
        mark_price: i64,
        total_balance: i64,
        total_pnl: i64,
        total_mm: i64,
    ) -> i64 {
        if self.open_volume == 0 {
            return 0;
        }
        let sign = self.direction.multiplier() as i64;
        let mark_notional = mul_exact(self.open_volume, mark_price);
        let mm_at_mark = spec.calculate_maintenance_margin(mark_notional);

        let refine = |external_margin: i64, mut liquidation_price: i64| -> i64 {
            for _ in 1..3 {
                let lp_notional = mul_exact(liquidation_price, self.open_volume).abs();
                let mm_at_lp = spec.calculate_maintenance_margin(lp_notional);
                let next_lp =
                    add_exact(mul_exact(sign, sub_exact(mm_at_lp, external_margin)), self.open_price_sum)
                        / self.open_volume;
                if next_lp == liquidation_price {
                    break;
                }

                let crossed_bracket = if self.direction == PositionDirection::Long {
                    next_lp <= 0 || next_lp >= mark_price
                } else {
                    next_lp <= mark_price
                };
                if crossed_bracket {
                    break;
                }
                liquidation_price = next_lp;
            }
            liquidation_price
        };

        if self.margin_mode == MarginMode::Isolated {
            let total_isolated_margin = add_exact(self.open_init_margin_sum, self.extra_margin);

            let lp0 = add_exact(mul_exact(sign, sub_exact(mm_at_mark, total_isolated_margin)), self.open_price_sum)
                / self.open_volume;
            return refine(total_isolated_margin, lp0);
        }

        let pnl_other = sub_exact(total_pnl, self.estimate_unrealized_profit(mark_price));
        let mm_other = sub_exact(total_mm, mm_at_mark);
        let cross_external_margin = sub_exact(add_exact(total_balance, pnl_other), mm_other);

        let numerator =
            add_exact(sub_exact(sub_exact(mul_exact(sign, self.open_price_sum), total_balance), pnl_other), mm_other);
        let diff = sub_exact(mul_exact(sign, mark_notional), mm_at_mark);
        if diff == 0 {
            return -1;
        }
        let denom = mul_exact(self.open_volume, diff);

        let lp0 = trunc_mul_div(numerator, mark_notional, denom);

        if lp0 < 0
            || (self.direction == PositionDirection::Long && lp0 > mark_price)
            || (self.direction == PositionDirection::Short && lp0 < mark_price)
        {
            return -1;
        }
        refine(cross_external_margin, lp0)
    }

    pub fn estimate_margin_ratio_scale_k(&self, spec: &CoreSymbolSpecification, mark_price: i64, total_margin: i64) -> i64 {
        if self.open_volume == 0 {
            return 0;
        }
        if total_margin <= 0 {
            return mul_exact(spec.maintenance_margin_scale_k, -1);
        }
        let maintenance_margin = self.calculate_maintenance_margin(spec, mark_price);
        mul_exact(spec.maintenance_margin_scale_k, maintenance_margin) / total_margin
    }

    pub fn calculate_required_margin_for_futures(&self, spec: &CoreSymbolSpecification) -> i64 {
        self.calculate_required_margin_for_futures_with_leverage(spec, self.leverage)
    }

    pub fn calculate_required_margin_for_futures_with_leverage(
        &self,
        spec: &CoreSymbolSpecification,
        leverage: i32,
    ) -> i64 {
        let open_notional = if self.open_volume == 0 {
            0
        } else {
            mul_exact(self.direction.multiplier() as i64, self.open_price_sum)
        };
        let bid_notional = mul_exact(self.pending_buy_size, self.pending_buy_avg_price);
        let ask_notional = mul_exact(self.pending_sell_size, self.pending_sell_avg_price);

        let worst_case_notional = add_exact(open_notional, bid_notional)
            .abs()
            .max(sub_exact(open_notional, ask_notional).abs());
        let new_exposure_notional = 0i64.max(sub_exact(worst_case_notional, open_notional.abs()));

        let bid_fee = calculate_taker_fee(self.pending_buy_size, self.pending_buy_avg_price, spec.taker_fee, spec.fee_scale_k);
        let ask_fee =
            calculate_taker_fee(self.pending_sell_size, self.pending_sell_avg_price, spec.taker_fee, spec.fee_scale_k);
        add_exact(
            add_exact(self.open_init_margin_sum, spec.calculate_init_margin(new_exposure_notional, leverage as i64)),
            bid_fee.max(ask_fee),
        )
    }

    pub fn calculate_required_margin_for_order(
        &self,
        spec: &CoreSymbolSpecification,
        action: OrderAction,
        order_notional: i64,
    ) -> i64 {
        let open_notional = if self.open_volume == 0 {
            0
        } else {
            mul_exact(self.direction.multiplier() as i64, self.open_price_sum)
        };
        let abs_open_notional = open_notional.abs();
        let bid_notional = mul_exact(self.pending_buy_size, self.pending_buy_avg_price);
        let ask_notional = mul_exact(self.pending_sell_size, self.pending_sell_avg_price);
        let new_bid_notional = if action == OrderAction::Bid {
            add_exact(bid_notional, order_notional)
        } else {
            bid_notional
        };
        let new_ask_notional = if action == OrderAction::Ask {
            add_exact(ask_notional, order_notional)
        } else {
            ask_notional
        };

        let current_exposure_notional = 0i64.max(sub_exact(
            add_exact(open_notional, bid_notional)
                .abs()
                .max(sub_exact(open_notional, ask_notional).abs()),
            abs_open_notional,
        ));
        let new_exposure_notional = 0i64.max(sub_exact(
            add_exact(open_notional, new_bid_notional)
                .abs()
                .max(sub_exact(open_notional, new_ask_notional).abs()),
            abs_open_notional,
        ));

        let leverage = self.leverage as i64;
        let new_total_margin =
            add_exact(self.open_init_margin_sum, spec.calculate_init_margin(new_exposure_notional, leverage));
        let current_total_margin =
            add_exact(self.open_init_margin_sum, spec.calculate_init_margin(current_exposure_notional, leverage));
        if new_total_margin <= current_total_margin {
            -1
        } else {
            new_total_margin
        }
    }

    pub fn estimate_notional_for_order(&self, action: OrderAction, size: i64, price: i64) -> i64 {
        let new_pending_buy_size =
            if action == OrderAction::Bid { add_exact(self.pending_buy_size, size) } else { self.pending_buy_size };
        let new_pending_sell_size =
            if action == OrderAction::Ask { add_exact(self.pending_sell_size, size) } else { self.pending_sell_size };
        let estimated_size = add_exact(self.open_volume, new_pending_buy_size.max(new_pending_sell_size));
        mul_exact(estimated_size, price)
    }

    pub fn calculate_pending_fee_for_order(
        &self,
        spec: &CoreSymbolSpecification,
        action: OrderAction,
        size: i64,
        price: i64,
    ) -> i64 {
        let new_pending_buy_size =
            if action == OrderAction::Bid { add_exact(self.pending_buy_size, size) } else { self.pending_buy_size };
        let new_pending_sell_size =
            if action == OrderAction::Ask { add_exact(self.pending_sell_size, size) } else { self.pending_sell_size };
        let new_pending_buy_avg_price = if action == OrderAction::Bid {
            Self::calculate_avg_price(self.pending_buy_avg_price, self.pending_buy_size, price, size)
        } else {
            self.pending_buy_avg_price
        };
        let new_pending_sell_avg_price = if action == OrderAction::Ask {
            Self::calculate_avg_price(self.pending_sell_avg_price, self.pending_sell_size, price, size)
        } else {
            self.pending_sell_avg_price
        };

        let fee_pending_buy =
            calculate_taker_fee(new_pending_buy_size, new_pending_buy_avg_price, spec.taker_fee, spec.fee_scale_k);
        let fee_pending_sell =
            calculate_taker_fee(new_pending_sell_size, new_pending_sell_avg_price, spec.taker_fee, spec.fee_scale_k);
        fee_pending_buy.max(fee_pending_sell)
    }

    pub fn calculate_pending_fee_for_order_budget(
        &self,
        spec: &CoreSymbolSpecification,
        action: OrderAction,
        size: i64,
        budget_notional: i64,
    ) -> i64 {
        let new_pending_buy_size =
            if action == OrderAction::Bid { add_exact(self.pending_buy_size, size) } else { self.pending_buy_size };
        let new_pending_sell_size =
            if action == OrderAction::Ask { add_exact(self.pending_sell_size, size) } else { self.pending_sell_size };
        let new_pending_buy_avg_price = if action == OrderAction::Bid && new_pending_buy_size > 0 {
            ceil_divide(
                add_exact(mul_exact(self.pending_buy_avg_price, self.pending_buy_size), budget_notional),
                new_pending_buy_size,
            )
        } else {
            self.pending_buy_avg_price
        };
        let new_pending_sell_avg_price = if action == OrderAction::Ask && new_pending_sell_size > 0 {
            ceil_divide(
                add_exact(mul_exact(self.pending_sell_avg_price, self.pending_sell_size), budget_notional),
                new_pending_sell_size,
            )
        } else {
            self.pending_sell_avg_price
        };

        let fee_pending_buy =
            calculate_taker_fee(new_pending_buy_size, new_pending_buy_avg_price, spec.taker_fee, spec.fee_scale_k);
        let fee_pending_sell =
            calculate_taker_fee(new_pending_sell_size, new_pending_sell_avg_price, spec.taker_fee, spec.fee_scale_k);
        fee_pending_buy.max(fee_pending_sell)
    }

    fn calculate_avg_price(current_avg: i64, current_size: i64, new_price: i64, new_size: i64) -> i64 {
        let total_size = add_exact(current_size, new_size);
        if total_size <= 0 {
            return 0;
        }
        let total_notional = add_exact(mul_exact(current_avg, current_size), mul_exact(new_price, new_size));
        ceil_divide(total_notional, total_size)
    }
}

pub type PositionsMapKey = i32;
pub type PositionsMap = BTreeMap<PositionsMapKey, SymbolPositionRecord>;

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for SymbolPositionRecord {
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i32(self.symbol);
        w.write_i32(self.currency);
        w.write_u8(self.direction.code() as u8);
        w.write_i64(self.open_volume);
        w.write_i64(self.open_init_margin_sum);
        w.write_i64(self.open_price_sum);
        w.write_i64(self.profit);
        w.write_i64(self.pending_sell_size);
        w.write_i64(self.pending_buy_size);
        w.write_i64(self.pending_sell_avg_price);
        w.write_i64(self.pending_buy_avg_price);
        w.write_i32(self.leverage);
        w.write_u8(self.margin_mode.code() as u8);
        w.write_i64(self.extra_margin);
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let symbol = r.read_i32()?;
        let currency = r.read_i32()?;
        let direction = PositionDirection::of_code(r.read_u8()? as i8);
        let open_volume = r.read_i64()?;
        let open_init_margin_sum = r.read_i64()?;
        let open_price_sum = r.read_i64()?;
        let profit = r.read_i64()?;
        let pending_sell_size = r.read_i64()?;
        let pending_buy_size = r.read_i64()?;
        let pending_sell_avg_price = r.read_i64()?;
        let pending_buy_avg_price = r.read_i64()?;
        let leverage = r.read_i32()?;
        let margin_mode = MarginMode::of_code(r.read_u8()? as i8);
        let extra_margin = r.read_i64()?;
        Ok(SymbolPositionRecord {
            symbol, currency, direction, open_volume, open_init_margin_sum, open_price_sum, profit,
            pending_sell_size, pending_buy_size, pending_sell_avg_price, pending_buy_avg_price,
            leverage, margin_mode, extra_margin,
            ..Default::default()
        })
    }
}

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
        assert_eq!(r.leverage, 1);
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

        assert_eq!(r.uid, 9);
        assert_eq!(r.symbol, 100);
        assert_eq!(r.currency, 2);

        assert_eq!(r.open_volume, 0);
        assert_eq!(r.open_init_margin_sum, 0);
        assert_eq!(r.open_price_sum, 0);
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
        let b = SymbolPositionRecord::new(999, 100, 2, MarginMode::Isolated, 5);
        assert_eq!(a.state_hash(), b.state_hash(), "state_hash matches Java field-for-field: excludes uid");
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
    fn state_hash_excludes_non_replicated_adl_fields() {
        let base = SymbolPositionRecord::new(1, 100, 2, MarginMode::Isolated, 5);
        let h0 = base.state_hash();

        let mut diff_pending_adl = base.clone();
        diff_pending_adl.pending_adl_size = 999;
        assert_eq!(h0, diff_pending_adl.state_hash(), "pending_adl_size is non-replicated, excluded from state_hash");

        let mut diff_adl_elig = base.clone();
        diff_adl_elig.adl_eligibility = 100;
        assert_eq!(h0, diff_adl_elig.state_hash(), "adl_eligibility is non-replicated, excluded from state_hash");

        let mut diff_flow = base.clone();
        diff_flow.liquidation_flow =
            Some(crate::core::processors::liquidation::liquidation_flow::LiquidationFlow::new(123, 45, 6));
        assert_eq!(h0, diff_flow.state_hash(), "liquidation_flow is non-replicated, in-memory only, excluded from state_hash (Ruling P6-E)");
    }

    fn long_position(open_volume: i64, open_init_margin_sum: i64, open_price_sum: i64, extra_margin: i64) -> SymbolPositionRecord {
        let mut p = SymbolPositionRecord::new(1, 100, 2, MarginMode::Isolated, 1);
        p.direction = PositionDirection::Long;
        p.open_volume = open_volume;
        p.open_init_margin_sum = open_init_margin_sum;
        p.open_price_sum = open_price_sum;
        p.extra_margin = extra_margin;
        p
    }

    #[test]
    fn calculate_bankruptcy_price_isolated_fixed_fee() {
        let pos = long_position(10, 100, 1000, 20);
        let spec = CoreSymbolSpecification { taker_fee: 2, liquidation_fee: 3, fee_scale_k: 0, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 0), 93);
    }

    #[test]
    fn calculate_bankruptcy_price_isolated_proportional_fee() {
        let pos = long_position(10, 100, 1000, 0);
        let spec = CoreSymbolSpecification { taker_fee: 100, liquidation_fee: 100, fee_scale_k: 1_000_000, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 0), 91);
    }

    fn short_position(open_volume: i64, open_init_margin_sum: i64, open_price_sum: i64, extra_margin: i64) -> SymbolPositionRecord {
        let mut p = long_position(open_volume, open_init_margin_sum, open_price_sum, extra_margin);
        p.direction = PositionDirection::Short;
        p
    }

    #[test]
    fn calculate_bankruptcy_price_isolated_short_fixed_fee() {
        let pos = short_position(10, 100, 1000, 20);
        let spec = CoreSymbolSpecification { taker_fee: 2, liquidation_fee: 3, fee_scale_k: 0, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 0), 107);
    }

    #[test]
    fn calculate_bankruptcy_price_isolated_short_proportional_fee() {
        let pos = short_position(10, 100, 1000, 0);
        let spec = CoreSymbolSpecification { taker_fee: 100, liquidation_fee: 100, fee_scale_k: 1_000_000, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 0), 110);
    }

    #[test]
    fn calculate_bankruptcy_price_cross_uses_margin_base_fn() {
        let mut pos = long_position(10, 100, 1000, 0);
        pos.margin_mode = MarginMode::Cross;
        let spec = CoreSymbolSpecification { taker_fee: 2, liquidation_fee: 3, fee_scale_k: 0, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 150), 90);
    }

    #[test]
    fn java_bp_zero_margin() {
        let pos = long_position(1, 0, 1000, 0);
        let spec = CoreSymbolSpecification { taker_fee: 1, liquidation_fee: 0, fee_scale_k: 0, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 0), 1001);
    }

    #[test]
    fn java_bp_negative_margin_fixed_fee() {
        let pos = long_position(10, -200, 10_000, 100);
        let spec = CoreSymbolSpecification { taker_fee: 2, liquidation_fee: 0, fee_scale_k: 0, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 0), 1012);
    }

    #[test]
    fn java_bp_negative_open_price_sum() {
        let pos = short_position(5, 250, -4_000, 50);
        let spec = CoreSymbolSpecification { taker_fee: 3, liquidation_fee: 0, fee_scale_k: 0, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 0), -743);
    }

    #[test]
    fn java_bp_zero_fee() {
        let pos = long_position(10, 400, 10_000, 100);
        let spec = CoreSymbolSpecification { taker_fee: 0, liquidation_fee: 0, fee_scale_k: 0, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 0), 950);
    }

    #[test]
    fn java_bp_cross_fixed_fee_long_uses_allocated_margin_base() {
        let mut pos = long_position(10, 999_999, 10_000, 999_999);
        pos.margin_mode = MarginMode::Cross;
        let spec = CoreSymbolSpecification { taker_fee: 2, liquidation_fee: 0, fee_scale_k: 0, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 480), 954);
    }

    #[test]
    fn java_bp_cross_fixed_fee_short_uses_allocated_margin_base() {
        let mut pos = short_position(5, 999_999, 4_000, 999_999);
        pos.margin_mode = MarginMode::Cross;
        let spec = CoreSymbolSpecification { taker_fee: 3, liquidation_fee: 0, fee_scale_k: 0, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 300), 857);
    }

    #[test]
    fn java_bp_cross_ratio_fee_long_uses_allocated_margin_base() {
        let mut pos = long_position(8, 999_999, 9_600, 999_999);
        pos.margin_mode = MarginMode::Cross;
        let spec = CoreSymbolSpecification { taker_fee: 1_000, liquidation_fee: 0, fee_scale_k: 1_000_000, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 600), 1127);
    }

    #[test]
    fn java_bp_cross_varies_with_allocation_monotonic() {
        let mut pos = long_position(10, 999_999, 10_000, 999_999);
        pos.margin_mode = MarginMode::Cross;
        let spec = CoreSymbolSpecification { taker_fee: 2, liquidation_fee: 0, fee_scale_k: 0, ..Default::default() };
        let low_margin = pos.calculate_bankruptcy_price(&spec, |_| 300);
        let high_margin = pos.calculate_bankruptcy_price(&spec, |_| 800);
        assert_eq!(low_margin, 972);
        assert_eq!(high_margin, 922);
        assert!(high_margin < low_margin, "the larger the marginBase, the lower the long bankruptcy price");
    }

    #[test]
    fn default_is_all_zero_empty() {
        let r = SymbolPositionRecord::default();
        assert_eq!(r.uid, 0);
        assert_eq!(r.leverage, 0);
        assert_eq!(r.margin_mode, MarginMode::Isolated);
        assert_eq!(r.direction, PositionDirection::Empty);
        assert!(r.is_empty());
    }

    #[test]
    fn pending_hold_ask_accumulates_size_and_weighted_avg_ceil() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_hold(OrderAction::Ask, 10, 100);
        assert_eq!(r.pending_sell_size, 10);
        assert_eq!(r.pending_sell_avg_price, 100);

        r.pending_hold(OrderAction::Ask, 5, 130);
        assert_eq!(r.pending_sell_size, 15);
        assert_eq!(r.pending_sell_avg_price, 110);
        assert_eq!(r.pending_buy_size, 0);
        assert_eq!(r.pending_buy_avg_price, 0);
    }

    #[test]
    fn pending_hold_bid_ceils_non_exact_average() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_hold(OrderAction::Bid, 3, 10);
        r.pending_hold(OrderAction::Bid, 2, 11);
        assert_eq!(r.pending_buy_size, 5);
        assert_eq!(r.pending_buy_avg_price, 11);
    }

    #[test]
    fn pending_hold_budget_tracks_notional_directly_and_ceils() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_hold_budget(OrderAction::Ask, 10, 1005);
        assert_eq!(r.pending_sell_size, 10);
        assert_eq!(r.pending_sell_avg_price, 101);

        r.pending_hold_budget(OrderAction::Ask, 5, 500);
        assert_eq!(r.pending_sell_size, 15);
        assert_eq!(r.pending_sell_avg_price, 101);
    }

    #[test]
    fn pending_hold_budget_new_size_non_positive_is_noop() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_hold_budget(OrderAction::Bid, -5, -500);
        assert_eq!(r.pending_buy_size, 0);
        assert_eq!(r.pending_buy_avg_price, 0);
    }

    #[test]
    fn pending_release_partial_keeps_avg_full_resets_avg() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_sell_size = 10;
        r.pending_sell_avg_price = 100;

        let released = r.pending_release(OrderAction::Ask, 4);
        assert_eq!(released, 4);
        assert_eq!(r.pending_sell_size, 6);
        assert_eq!(r.pending_sell_avg_price, 100);

        let released2 = r.pending_release(OrderAction::Ask, 6);
        assert_eq!(released2, 6);
        assert_eq!(r.pending_sell_size, 0);
        assert_eq!(r.pending_sell_avg_price, 0);
    }

    #[test]
    fn pending_release_over_release_clamps_to_available_and_resets_avg() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_buy_size = 5;
        r.pending_buy_avg_price = 200;

        let released = r.pending_release(OrderAction::Bid, 8);
        assert_eq!(released, 5);
        assert_eq!(r.pending_buy_size, 0);
        assert_eq!(r.pending_buy_avg_price, 0);
    }

    #[test]
    fn estimate_unrealized_profit_long_gains_when_mark_above_cost() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000;
        assert_eq!(r.estimate_unrealized_profit(120), 200);
    }

    #[test]
    fn estimate_unrealized_profit_short_gains_when_mark_below_cost() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Short;
        r.open_volume = 10;
        r.open_price_sum = 1000;
        assert_eq!(r.estimate_unrealized_profit(80), 200);
    }

    #[test]
    fn estimate_pnl_adds_realized_profit_to_unrealized() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000;
        r.profit = 50;
        assert_eq!(r.estimate_pnl(120), 250);
    }

    #[test]
    fn calculate_maintenance_margin_zero_when_flat() {
        let r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        let spec = CoreSymbolSpecification::default();
        assert_eq!(r.calculate_maintenance_margin(&spec, 100), 0);
    }

    #[test]
    fn calculate_maintenance_margin_ignores_pending_uses_open_volume_at_mark() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.open_volume = 10;
        r.pending_buy_size = 999;
        let spec = CoreSymbolSpecification::default();
        assert_eq!(r.calculate_maintenance_margin(&spec, 100), 1000);
    }

    fn fee_spec(taker_fee: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification { taker_fee, fee_scale_k: 0, ..Default::default() }
    }

    #[test]
    fn required_margin_for_futures_flat_no_pending_is_zero() {
        let r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        let spec = fee_spec(2);
        assert_eq!(r.calculate_required_margin_for_futures(&spec), 0);
    }

    #[test]
    fn required_margin_for_futures_bid_pending_expands_long_exposure() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000;
        r.open_init_margin_sum = 100;
        r.pending_buy_size = 5;
        r.pending_buy_avg_price = 100;

        let spec = fee_spec(2);
        assert_eq!(r.calculate_required_margin_for_futures(&spec), 610);
    }

    #[test]
    fn required_margin_for_futures_pure_reducing_ask_pending_adds_no_exposure_margin() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000;
        r.open_init_margin_sum = 100;
        r.pending_sell_size = 5;
        r.pending_sell_avg_price = 100;

        let spec = fee_spec(2);
        assert_eq!(r.calculate_required_margin_for_futures(&spec), 110);
    }

    #[test]
    fn required_margin_for_order_pure_reduce_returns_sentinel_minus_one() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000;
        r.open_init_margin_sum = 100;

        let spec = CoreSymbolSpecification::default();
        assert_eq!(r.calculate_required_margin_for_order(&spec, OrderAction::Ask, 300), -1);
    }

    #[test]
    fn required_margin_for_order_expanding_bid_returns_positive_total() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000;
        r.open_init_margin_sum = 100;

        let spec = CoreSymbolSpecification::default();
        assert_eq!(r.calculate_required_margin_for_order(&spec, OrderAction::Bid, 500), 600);
    }

    #[test]
    fn estimate_notional_for_order_uses_max_pending_side_plus_open() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.open_volume = 10;
        r.pending_buy_size = 5;
        r.pending_sell_size = 2;
        assert_eq!(r.estimate_notional_for_order(OrderAction::Bid, 3, 50), 900);
    }

    #[test]
    fn calculate_pending_fee_for_order_picks_worse_side() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_sell_size = 3;
        r.pending_sell_avg_price = 200;

        let spec = fee_spec(10);
        assert_eq!(r.calculate_pending_fee_for_order(&spec, OrderAction::Bid, 5, 100), 50);
    }

    #[test]
    fn calculate_pending_fee_for_order_budget_uses_notional_directly() {
        let r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        let spec = fee_spec(10);
        assert_eq!(r.calculate_pending_fee_for_order_budget(&spec, OrderAction::Ask, 4, 1000), 40);
    }

    #[test]
    fn open_position_margin_margin_off_mark_cost_off_trade() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 2);
        let spec = CoreSymbolSpecification::default();

        r.open_position_margin(OrderAction::Bid, 10, 100, &spec, 120);
        assert_eq!(r.open_volume, 10);
        assert_eq!(r.open_init_margin_sum, 600);
        assert_eq!(r.open_price_sum, 1000);
        assert_eq!(r.direction, PositionDirection::Long);
    }

    #[test]
    fn close_current_position_no_open_position_returns_full_trade_size_untouched() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        let size_to_open = r.close_current_position_futures(OrderAction::Ask, 7, 100);
        assert_eq!(size_to_open, 7);
        assert_eq!(r.open_volume, 0);
        assert_eq!(r.profit, 0);
    }

    #[test]
    fn close_current_position_same_direction_is_noop_returns_full_trade_size() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 5;
        r.open_price_sum = 500;
        r.open_init_margin_sum = 50;

        let size_to_open = r.close_current_position_futures(OrderAction::Bid, 3, 999);
        assert_eq!(size_to_open, 3);
        assert_eq!(r.open_volume, 5);
        assert_eq!(r.open_price_sum, 500);
        assert_eq!(r.open_init_margin_sum, 50);
        assert_eq!(r.profit, 0);
    }

    #[test]
    fn close_current_position_partial_close_defers_pnl_into_cost_basis() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000;
        r.open_init_margin_sum = 300;

        let size_to_open = r.close_current_position_futures(OrderAction::Ask, 4, 120);
        assert_eq!(size_to_open, 0);
        assert_eq!(r.open_volume, 6);
        assert_eq!(r.open_init_margin_sum, 180);
        assert_eq!(r.open_price_sum, 520);
        assert_eq!(r.profit, 0);
        assert_eq!(r.direction, PositionDirection::Long);
    }

    #[test]
    fn close_current_position_full_close_exact_realizes_pnl_and_zeroes_position() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 6;
        r.open_price_sum = 520;
        r.open_init_margin_sum = 180;
        r.profit = 0;

        let size_to_open = r.close_current_position_futures(OrderAction::Ask, 6, 150);
        assert_eq!(size_to_open, 0);
        assert_eq!(r.open_volume, 0);
        assert_eq!(r.open_init_margin_sum, 0);
        assert_eq!(r.open_price_sum, 0);
        assert_eq!(r.profit, 380);
    }

    #[test]
    fn close_current_position_flip_realizes_pnl_on_old_volume_and_returns_remainder() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Short;
        r.open_volume = 5;
        r.open_price_sum = 500;
        r.open_init_margin_sum = 50;
        r.profit = 0;

        let size_to_open = r.close_current_position_futures(OrderAction::Bid, 8, 80);
        assert_eq!(size_to_open, 3);
        assert_eq!(r.open_volume, 0);
        assert_eq!(r.open_init_margin_sum, 0);
        assert_eq!(r.open_price_sum, 0);
        assert_eq!(r.profit, 100);
    }

    #[test]
    fn close_then_open_round_trip_flip_matches_manual_open() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Short;
        r.open_volume = 5;
        r.open_price_sum = 500;
        r.open_init_margin_sum = 50;

        let spec = CoreSymbolSpecification::default();
        let size_to_open = r.close_current_position_futures(OrderAction::Bid, 8, 80);
        assert_eq!(size_to_open, 3);
        r.open_position_margin(OrderAction::Bid, size_to_open, 80, &spec, 80);

        assert_eq!(r.open_volume, 3);
        assert_eq!(r.direction, PositionDirection::Long);
        assert_eq!(r.open_price_sum, 240);
        assert_eq!(r.open_init_margin_sum, 240);
        assert_eq!(r.profit, 100);
    }

    mod java_parity {
        use super::*;
        use std::collections::BTreeMap;

        fn liq_spec() -> CoreSymbolSpecification {
            CoreSymbolSpecification {
                init_margin: 10,
                init_margin_scale_k: 100,
                maintenance_margin: BTreeMap::from([(1000i64, 8i64)]),
                maintenance_margin_scale_k: 100,
                ..Default::default()
            }
        }

        fn pos(margin_mode: MarginMode, direction: PositionDirection, open_volume: i64) -> SymbolPositionRecord {
            let mut p = SymbolPositionRecord::new(1, 1001, 2, margin_mode, 1);
            p.direction = direction;
            p.open_volume = open_volume;
            p
        }

        #[test]
        fn pending_hold_budget_empty_state_avg_price_eq_budget_over_size() {
            let mut p = pos(MarginMode::Isolated, PositionDirection::Empty, 0);
            p.pending_hold_budget(OrderAction::Bid, 10, 1000);
            assert_eq!(p.pending_buy_size, 10);
            assert_eq!(p.pending_buy_avg_price, 100);
            assert_eq!(p.pending_buy_size * p.pending_buy_avg_price, 1000);
        }

        #[test]
        fn pending_hold_budget_over_existing_limit_maintains_total_notional() {
            let mut p = pos(MarginMode::Isolated, PositionDirection::Empty, 0);
            p.pending_hold(OrderAction::Bid, 100, 10);
            assert_eq!(p.pending_buy_size * p.pending_buy_avg_price, 1000, "limit notional baseline");
            p.pending_hold_budget(OrderAction::Bid, 50, 600);
            assert_eq!(p.pending_buy_size, 150);
            assert_eq!(p.pending_buy_avg_price, 11);
            assert_eq!(p.pending_buy_size * p.pending_buy_avg_price, 1650);
        }

        #[test]
        fn pending_hold_budget_ask_side_independent_from_bid() {
            let mut p = pos(MarginMode::Isolated, PositionDirection::Empty, 0);
            p.pending_hold_budget(OrderAction::Ask, 20, 400);
            assert_eq!(p.pending_sell_size, 20);
            assert_eq!(p.pending_sell_avg_price, 20);
            assert_eq!(p.pending_buy_size, 0);
            assert_eq!(p.pending_buy_avg_price, 0);
        }

        #[test]
        fn pending_hold_budget_zero_size_noop() {
            let mut p = pos(MarginMode::Isolated, PositionDirection::Empty, 0);
            p.pending_hold_budget(OrderAction::Bid, 0, 100);
            assert_eq!(p.pending_buy_size, 0);
            assert_eq!(p.pending_buy_avg_price, 0);
        }

        #[test]
        fn pending_hold_budget_then_release_clears_state() {
            let mut p = pos(MarginMode::Isolated, PositionDirection::Empty, 0);
            p.pending_hold_budget(OrderAction::Bid, 10, 1000);
            let released = p.pending_release(OrderAction::Bid, 10);
            assert_eq!(released, 10);
            assert_eq!(p.pending_buy_size, 0);
            assert_eq!(p.pending_buy_avg_price, 0);
        }

        #[test]
        fn estimate_liq_price_no_position_returns_zero() {
            let spec = liq_spec();
            let p = pos(MarginMode::Isolated, PositionDirection::Empty, 0);
            assert_eq!(p.estimate_liquidation_price(&spec, 50000, 0, 0, 0), 0);
        }

        #[test]
        fn estimate_liq_price_cross_long_normal() {
            let spec = liq_spec();
            let mut p = pos(MarginMode::Cross, PositionDirection::Long, 10);
            p.open_price_sum = 500000;
            let mm = spec.calculate_maintenance_margin(10 * 50000);
            assert_eq!(p.estimate_liquidation_price(&spec, 50000, 100000, -5000, mm + 40000), 48369);
        }

        #[test]
        fn estimate_liq_price_cross_short() {
            let spec = liq_spec();
            let mut p = pos(MarginMode::Cross, PositionDirection::Short, 10);
            p.open_price_sum = 500000;
            p.open_init_margin_sum = 50000;
            let mm = spec.calculate_maintenance_margin(10 * 50000);
            assert_eq!(p.estimate_liquidation_price(&spec, 50000, 100000, 0, mm), 55555);
        }

        #[test]
        fn estimate_liq_price_isolated_short() {
            let spec = liq_spec();
            let mut p = pos(MarginMode::Isolated, PositionDirection::Short, 10);
            p.open_price_sum = 500000;
            p.open_init_margin_sum = 50000;
            let mm = spec.calculate_maintenance_margin(10 * 50000);
            assert_eq!(p.estimate_liquidation_price(&spec, 50000, 100000, 0, mm), 50926);
        }

        #[test]
        fn estimate_liq_price_cross_long_profit() {
            let spec = liq_spec();
            let mut p = pos(MarginMode::Cross, PositionDirection::Long, 10);
            p.open_price_sum = 500000;
            let mm = spec.calculate_maintenance_margin(10 * 50000);
            assert_eq!(p.estimate_liquidation_price(&spec, 50000, 100000, 5000, mm + 40000), 47282);
        }

        #[test]
        fn estimate_liq_price_cross_long_no_liq() {
            let spec = liq_spec();
            let mut p = pos(MarginMode::Cross, PositionDirection::Long, 10);
            p.open_price_sum = 500000;
            let pnl = p.estimate_unrealized_profit(50000);
            let mm = spec.calculate_maintenance_margin(10 * 50000);
            assert_eq!(p.estimate_liquidation_price(&spec, 50000, 60000, pnl, mm + 40000), -1);
        }

        #[test]
        fn estimate_liq_price_isolated_long() {
            let spec = liq_spec();
            let mut p = pos(MarginMode::Isolated, PositionDirection::Long, 10);
            p.open_price_sum = 500000;
            p.open_init_margin_sum = 50000;
            let mm = spec.calculate_maintenance_margin(10 * 50000);
            assert_eq!(p.estimate_liquidation_price(&spec, 50000, 60000, 0, mm), 48913);
        }

        fn fixed_fee_spec(taker_fee: i64, liquidation_fee: i64) -> CoreSymbolSpecification {
            CoreSymbolSpecification { taker_fee, liquidation_fee, fee_scale_k: 0, ..Default::default() }
        }
        fn dynamic_fee_spec(taker_fee: i64, liquidation_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
            CoreSymbolSpecification { taker_fee, liquidation_fee, fee_scale_k, ..Default::default() }
        }
        fn bp_pos(direction: PositionDirection) -> SymbolPositionRecord {
            let mut p = pos(MarginMode::Isolated, direction, 10);
            p.open_price_sum = 1000;
            p.open_init_margin_sum = 100;
            p
        }

        #[test]
        fn bp_isolated_long_fixed_fee_with_liquidation_fee() {
            assert_eq!(bp_pos(PositionDirection::Long).calculate_bankruptcy_price(&fixed_fee_spec(1, 5), |_| 0), 96);
        }

        #[test]
        fn bp_isolated_short_fixed_fee_with_liquidation_fee() {
            assert_eq!(bp_pos(PositionDirection::Short).calculate_bankruptcy_price(&fixed_fee_spec(1, 5), |_| 0), 104);
        }

        #[test]
        fn bp_isolated_long_dynamic_fee_with_liquidation_fee() {
            assert_eq!(bp_pos(PositionDirection::Long).calculate_bankruptcy_price(&dynamic_fee_spec(20, 30, 1000), |_| 0), 95);
        }

        #[test]
        fn bp_isolated_short_dynamic_fee_with_liquidation_fee() {
            assert_eq!(bp_pos(PositionDirection::Short).calculate_bankruptcy_price(&dynamic_fee_spec(20, 30, 1000), |_| 0), 105);
        }
    }
}
