use crate::core::common::margin_mode::MarginMode;
use crate::core::common::position_direction::PositionDirection;

pub const SYSTEM_TRIGGERED_ORDER_ID: i64 = -1;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, serde::Serialize, serde::Deserialize)]
pub enum FundEventType {
    #[default]
    Deposit,
    Locked,
    Transfer,
    Unlocked,
    Withdraw,
    LockPending,
    UnlockPending,
    OpenPosition,
    ClosePosition,
    LiquidationClose,
    LiquidationFee,
    FundingfeeSettlement,
    PnlSettlement,
    MarginAdjust,
    MarginRefund,
    IfPositionClose,
    AdlOriginClose,
    AdlPositionClose,
    MarginAlert,
    LiquidationAlert,
    ResetFee,
    LoanMarginCall,
    LoanBorrow,
    LoanRepay,
    LoanCollateralChange,
    LoanLiquidated,
    InternalTransfer,
}

impl FundEventType {
    pub fn code(self) -> i32 {
        match self {
            FundEventType::Deposit => 1,
            FundEventType::Locked => 2,
            FundEventType::Transfer => 3,
            FundEventType::Unlocked => 4,
            FundEventType::Withdraw => 5,
            FundEventType::LockPending => 6,
            FundEventType::UnlockPending => 7,
            FundEventType::OpenPosition => 8,
            FundEventType::ClosePosition => 9,
            FundEventType::LiquidationClose => 10,
            FundEventType::LiquidationFee => 11,
            FundEventType::FundingfeeSettlement => 12,
            FundEventType::PnlSettlement => 13,
            FundEventType::MarginAdjust => 14,
            FundEventType::MarginRefund => 15,
            FundEventType::IfPositionClose => 16,
            FundEventType::AdlOriginClose => 17,
            FundEventType::AdlPositionClose => 18,
            FundEventType::MarginAlert => 20,
            FundEventType::LiquidationAlert => 21,
            FundEventType::ResetFee => 30,
            FundEventType::LoanMarginCall => 40,
            FundEventType::LoanBorrow => 41,
            FundEventType::LoanRepay => 42,
            FundEventType::LoanCollateralChange => 43,
            FundEventType::LoanLiquidated => 44,
            FundEventType::InternalTransfer => 50,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default, serde::Serialize)]
pub struct FundEvent {
    pub event_type: FundEventType,
    pub order_id: i64,
    pub uid: i64,
    pub currency: i32,
    pub currency_scale_k: i64,
    pub free: i64,
    pub locked: i64,

    pub symbol: i32,
    pub base_scale_k: i64,
    pub quote_scale_k: i64,
    pub direction: PositionDirection,
    pub open_volume: i64,
    pub open_init_margin_sum: i64,
    pub open_price_sum: i64,
    pub profit: i64,
    pub leverage: i32,
    pub margin_mode: MarginMode,
    pub extra_margin: i64,
    pub unrealized_profit: i64,
    pub liquidation_price: i64,
    pub margin_ratio_scale_k: i64,
    pub maintenance_margin_scale_k: i64,
    pub mark_price: i64,

    pub loan_mode: i8,
    pub loan_debt_principal: i64,
    pub loan_debt_interest: i64,
    pub loan_interest_paid_total: i64,
    pub loan_ltv_bps: i64,
    pub loan_threshold_bps: i64,
    pub loan_collateral_currency: i32,
    pub loan_collateral_currency_scale_k: i64,
    pub loan_collateral_pledged: i64,
    pub loan_collateral_free: i64,
    pub loan_collateral_locked: i64,
}

impl FundEvent {
    pub fn spot(event_type: FundEventType, order_id: i64, uid: i64, currency: i32, free: i64, locked: i64) -> Self {
        FundEvent { event_type, order_id, uid, currency, free, locked, ..Default::default() }
    }
}

