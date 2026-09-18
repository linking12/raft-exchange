use crate::core::common::fund_event::{FundEvent, FundEventType};
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::position_direction::PositionDirection;
use crate::core::utils::core_arithmetic_utils::calculate_amount_bid;

pub trait FundEventsHandler {
    fn fund_event_report(&mut self, report: FundEventReport);
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FundEventReport {
    pub uni_id: i64,
    pub account_id: i64,
    pub event_type: FundEventType,
    pub balances: BalanceSnapshot,
    pub positions: PositionSnapshot,
    pub loan: LoanSnapshot,
}

impl FundEventReport {

    pub fn from_fund_event(fund_event: &FundEvent, uni_id: i64) -> Self {
        FundEventReport {
            uni_id,
            account_id: fund_event.uid,
            event_type: fund_event.event_type,
            balances: BalanceSnapshot::fill(fund_event),
            positions: PositionSnapshot::fill(fund_event),
            loan: LoanSnapshot::fill(fund_event),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BalanceSnapshot {
    pub currency: i32,
    pub currency_scale_k: i64,
    pub free: i64,
    pub locked: i64,
}

impl BalanceSnapshot {
    fn fill(e: &FundEvent) -> Self {
        BalanceSnapshot { currency: e.currency, currency_scale_k: e.currency_scale_k, free: e.free, locked: e.locked }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PositionSnapshot {
    pub symbol_id: i32,
    pub base_scale_k: i64,
    pub quote_scale_k: i64,
    pub direction: PositionDirection,
    pub quantity: i64,
    pub open_price_sum: i64,
    pub cum_realized: i64,
    pub isolated: bool,
    pub isolated_wallet: i64,
    pub leverage: i32,
    pub open_init_margin_sum: i64,
    pub mark_price: i64,
    pub unrealized_profit: i64,
    pub liquidation_price: i64,
    pub margin_ratio_scale_k: i64,
    pub maintenance_margin_scale_k: i64,
    pub bids_notional: i64,
    pub asks_notional: i64,
    pub bids_qty: i64,
    pub asks_qty: i64,
}

impl PositionSnapshot {
    fn fill(e: &FundEvent) -> Self {
        PositionSnapshot {
            symbol_id: e.symbol,
            base_scale_k: e.base_scale_k,
            quote_scale_k: e.quote_scale_k,
            direction: e.direction,
            quantity: e.open_volume,
            open_price_sum: e.open_price_sum,
            cum_realized: e.profit,
            isolated: e.margin_mode == MarginMode::Isolated,
            isolated_wallet: e.extra_margin,
            leverage: e.leverage,
            open_init_margin_sum: e.open_init_margin_sum,
            mark_price: e.mark_price,
            unrealized_profit: e.unrealized_profit,
            liquidation_price: e.liquidation_price,
            margin_ratio_scale_k: e.margin_ratio_scale_k,
            maintenance_margin_scale_k: e.maintenance_margin_scale_k,
            bids_notional: calculate_amount_bid(e.pending_buy_size, e.pending_buy_avg_price),
            asks_notional: calculate_amount_bid(e.pending_sell_size, e.pending_sell_avg_price),
            bids_qty: e.pending_buy_size,
            asks_qty: e.pending_sell_size,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LoanSnapshot {
    pub mode: i8,
    pub debt_principal: i64,
    pub debt_interest: i64,
    pub interest_paid_total: i64,
    pub ltv_bps: i64,
    pub threshold_bps: i64,
    pub collateral_currency: i32,
    pub collateral_currency_scale_k: i64,
    pub collateral_pledged: i64,
    pub collateral_free: i64,
    pub collateral_locked: i64,
}

impl LoanSnapshot {
    fn fill(e: &FundEvent) -> Self {
        LoanSnapshot {
            mode: e.loan_mode,
            debt_principal: e.loan_debt_principal,
            debt_interest: e.loan_debt_interest,
            interest_paid_total: e.loan_interest_paid_total,
            ltv_bps: e.loan_ltv_bps,
            threshold_bps: e.loan_threshold_bps,
            collateral_currency: e.loan_collateral_currency,
            collateral_currency_scale_k: e.loan_collateral_currency_scale_k,
            collateral_pledged: e.loan_collateral_pledged,
            collateral_free: e.loan_collateral_free,
            collateral_locked: e.loan_collateral_locked,
        }
    }
}
