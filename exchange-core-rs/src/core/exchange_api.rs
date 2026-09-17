use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::common::cmd::order_command::{OrderCommand, FLAG_REDUCE_ONLY};
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_type::OrderType;
use crate::core::common::l2_market_data::L2MarketData;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::symbol_type::SymbolType;
use crate::core::common::batch_add_loan_command::BatchAddLoanCommand;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::processors::risk_engine::RiskEngine;

use super::exchange_core::ExchangeCore;

#[derive(Debug, Clone)]
pub struct PlaceOrderRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
    pub price: i64,
    pub size: i64,
    pub reserve_bid_price: i64,
    pub action: OrderAction,
    pub order_type: OrderType,
}

#[derive(Debug, Clone, Copy)]
pub struct CancelOrderRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
}

#[derive(Debug, Clone, Copy)]
pub struct MoveOrderRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
    pub new_price: i64,
}

#[derive(Debug, Clone, Copy)]
pub struct ReduceOrderRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
    pub reduce_size: i64,
}

#[derive(Debug, Clone, Copy)]
pub struct PlaceFuturesOrderRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
    pub price: i64,
    pub size: i64,
    pub action: OrderAction,
    pub order_type: OrderType,
    pub leverage: i32,
    pub margin_mode: MarginMode,
    pub reduce_only: bool,
}

#[derive(Debug, Clone, Copy)]
pub struct ClosePositionRequest {
    pub order_id: i64,
    pub uid: i64,
    pub symbol: i32,
    pub action: OrderAction,
    pub price: i64,
    pub size: i64,
    pub order_type: OrderType,
}

#[derive(Debug, Clone, Copy)]
pub struct MarginAdjustmentRequest {
    pub uid: i64,
    pub symbol: i32,
    pub action: OrderAction,
    pub amount: i64,
    pub margin_mode: MarginMode,
    pub order_id: i64,
}

#[derive(Default)]
pub struct ExchangeApi {
    core: ExchangeCore,
    last_cmd: Option<OrderCommand>,
}

impl ExchangeApi {
    pub fn new() -> Self {
        ExchangeApi { core: ExchangeCore::new(), last_cmd: None }
    }

    fn run(&mut self, mut cmd: OrderCommand) -> CommandResultCode {
        self.core.process_command(&mut cmd);
        let rc = cmd.result_code.expect("process_command always sets result_code");
        self.last_cmd = Some(cmd);
        rc
    }

    pub fn add_currency(&mut self, currency: i32, scale_k: i64) {
        self.core.ssp.add_currency(CoreCurrencySpecification { currency, currency_scale_k: scale_k, ..Default::default() });
    }

    pub fn add_symbol(&mut self, spec: CoreSymbolSpecification) -> CommandResultCode {
        if self.core.ssp.get_currency(spec.base_currency).is_none()
            || self.core.ssp.get_currency(spec.quote_currency).is_none()
        {
            return CommandResultCode::InvalidSymbol;
        }
        let rc = self.core.ssp.add_symbol(spec.clone());
        if rc == CommandResultCode::Success {
            self.core.matching.add_symbol(&spec);
        }
        rc
    }

    pub fn add_futures_symbol(&mut self, spec: CoreSymbolSpecification) -> CommandResultCode {
        if !spec.symbol_type.is_futures_contract() {
            return CommandResultCode::UnsupportedSymbolType;
        }
        self.add_symbol(spec)
    }

    pub fn add_user(&mut self, uid: i64) -> CommandResultCode {
        let cmd = OrderCommand { command: OrderCommandType::AddUser, uid, ..Default::default() };
        self.run(cmd)
    }

    pub fn enable_liquidation(&mut self) {
        self.core.risk.liquidation_engine.is_running = true;
    }

    pub fn add_currencies(&mut self, currencies: impl IntoIterator<Item = CoreCurrencySpecification>) {
        for spec in currencies {
            self.core.ssp.add_currency(spec);
        }
    }

    pub fn add_symbols(&mut self, symbols: impl IntoIterator<Item = CoreSymbolSpecification>) {
        for spec in symbols {
            if spec.symbol_type != SymbolType::CurrencyExchangePair && !self.core.risk.cfg_margin_trading_enabled {
                log::warn!("Margin symbols are not allowed: symbol={}", spec.symbol_id);
                continue;
            }
            self.add_symbol(spec);
        }
    }

    pub fn add_accounts(&mut self, accounts: impl IntoIterator<Item = (i64, Vec<(i32, i64)>)>) {
        for (uid, balances) in accounts {
            if self.core.ups.add_empty_user_profile(uid) != CommandResultCode::Success {
                continue;
            }
            if let Some(up) = self.core.ups.get_mut(uid) {
                for (currency, amount) in balances {
                    up.add_to_account(currency, amount);
                    *self.core.risk.adjustments.entry(currency).or_insert(0) -= amount;
                }
            }
        }
    }

    pub fn add_loans(&mut self, cmds: impl IntoIterator<Item = BatchAddLoanCommand>) {
        for cmd in cmds {
            self.core.risk.apply_add_loan(&cmd, &mut self.core.ssp);
        }
    }

    pub fn add_loan(&mut self, cmd: BatchAddLoanCommand) {
        self.core.risk.apply_add_loan(&cmd, &mut self.core.ssp);
    }

    pub fn balance_adjustment(
        &mut self,
        uid: i64,
        currency: i32,
        amount: i64,
        txid: i64,
    ) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid,
            symbol: currency,
            price: amount,
            order_id: txid,
            ..Default::default()
        };
        self.run(cmd)
    }

    pub fn place_order(&mut self, req: PlaceOrderRequest) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            price: req.price,
            size: req.size,
            reserve_bid_price: req.reserve_bid_price,
            action: Some(req.action),
            order_type: Some(req.order_type),
            ..Default::default()
        };
        self.run(cmd)
    }

    pub fn cancel_order(&mut self, req: CancelOrderRequest) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::CancelOrder,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            ..Default::default()
        };
        self.run(cmd)
    }

    pub fn move_order(&mut self, req: MoveOrderRequest) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::MoveOrder,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            price: req.new_price,
            ..Default::default()
        };
        self.run(cmd)
    }

    pub fn reduce_order(&mut self, req: ReduceOrderRequest) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::ReduceOrder,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            size: req.reduce_size,
            ..Default::default()
        };
        self.run(cmd)
    }

    pub fn place_futures_order(&mut self, req: PlaceFuturesOrderRequest) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            price: req.price,
            size: req.size,
            action: Some(req.action),
            order_type: Some(req.order_type),
            leverage: req.leverage,
            margin_mode: req.margin_mode,
            order_flags: if req.reduce_only { FLAG_REDUCE_ONLY } else { 0 },
            ..Default::default()
        };
        self.run(cmd)
    }

    pub fn close_position(&mut self, req: ClosePositionRequest) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::ClosePosition,
            order_id: req.order_id,
            uid: req.uid,
            symbol: req.symbol,
            action: Some(req.action),
            price: req.price,
            size: req.size,
            order_type: Some(req.order_type),
            ..Default::default()
        };
        self.run(cmd)
    }

    pub fn margin_adjustment(&mut self, req: MarginAdjustmentRequest) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::MarginAdjustment,
            uid: req.uid,
            symbol: req.symbol,
            action: Some(req.action),
            price: req.amount,
            margin_mode: req.margin_mode,
            order_id: req.order_id,
            ..Default::default()
        };
        self.run(cmd)
    }

    pub fn leverage_adjustment(&mut self, uid: i64, symbol: i32, leverage: i32) -> CommandResultCode {
        let cmd = OrderCommand {
            command: OrderCommandType::LeverageAdjustment,
            uid,
            symbol,
            leverage,
            ..Default::default()
        };
        self.run(cmd)
    }

    pub fn adjust_position_mode(&mut self, uid: i64, hedge: bool) -> CommandResultCode {
        let action = if hedge { OrderAction::Bid } else { OrderAction::Ask };
        self.run(OrderCommand { command: OrderCommandType::PositionModeAdjustment, uid, action: Some(action), ..Default::default() })
    }

    pub fn set_mark_price(&mut self, symbol: i32, price: i64) -> CommandResultCode {
        self.run(OrderCommand {
            command: OrderCommandType::MarkpriceAdjustment,
            symbol,
            price,
            ..Default::default()
        })
    }

    pub fn suspend_user(&mut self, uid: i64) -> CommandResultCode {
        self.run(OrderCommand { command: OrderCommandType::SuspendUser, uid, ..Default::default() })
    }

    pub fn resume_user(&mut self, uid: i64) -> CommandResultCode {
        self.run(OrderCommand { command: OrderCommandType::ResumeUser, uid, ..Default::default() })
    }

    pub fn submit(&mut self, cmd: OrderCommand) -> CommandResultCode {
        self.run(cmd)
    }

    pub fn user_account(&self, uid: i64, currency: i32) -> i64 {
        self.core.ups.get(uid).map(|p| p.account(currency)).unwrap_or(0)
    }

    pub fn user_locked(&self, uid: i64, currency: i32) -> i64 {
        self.core.ups.get(uid).map(|p| p.locked(currency)).unwrap_or(0)
    }

    pub fn user_position(&self, uid: i64, symbol: i32) -> Option<&SymbolPositionRecord> {
        self.core.ups.get(uid).and_then(|p| p.positions.get(&symbol))
    }

    pub fn fees(&self, currency: i32) -> i64 {
        *self.core.risk.fees.get(&currency).unwrap_or(&0)
    }

    pub fn adjustments(&self, currency: i32) -> i64 {
        *self.core.risk.adjustments.get(&currency).unwrap_or(&0)
    }

    pub fn request_l2(&mut self, symbol: i32, depth: i32) -> L2MarketData {
        let mut cmd = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol,
            size: depth as i64,
            ..Default::default()
        };
        self.core.process_command(&mut cmd);
        cmd.market_data.take().unwrap_or_default()
    }

    pub fn last_cmd(&self) -> &OrderCommand {
        self.last_cmd.as_ref().expect("no command submitted yet")
    }

    pub fn last_matcher_event(&self) -> Option<&crate::core::common::matcher_trade_event::MatcherTradeEvent> {
        self.last_cmd().matcher_event.as_deref()
    }

    pub fn last_fund_events(&self) -> &[crate::core::common::fund_event::FundEvent] {
        &self.last_cmd().fund_events
    }

    pub fn cascade_fund_events(&self) -> &[crate::core::common::fund_event::FundEvent] {
        &self.core.last_cascade_events
    }

    pub fn cascade_matcher_events(&self) -> &[crate::core::common::matcher_trade_event::MatcherTradeEvent] {
        &self.core.last_cascade_matcher_events
    }

    pub fn ups(&self) -> &UserProfileService {
        &self.core.ups
    }

    pub fn ssp(&self) -> &SymbolSpecificationProvider {
        &self.core.ssp
    }

    pub fn risk(&self) -> &RiskEngine {
        &self.core.risk
    }

    pub fn total_balance(&self) -> crate::core::reports::TotalCurrencyBalanceReport {
        self.core.query_total_balance()
    }

    pub fn single_user(&self, uid: i64, now_ms: i64) -> crate::core::reports::SingleUserReport {
        self.core.query_single_user(uid, now_ms)
    }

    pub fn insurance_fund(&self) -> crate::core::reports::InsuranceFundReport {
        self.core.query_insurance_fund()
    }

    pub fn symbol_currency(&self) -> crate::core::reports::SymbolCurrencyReport {
        self.core.query_symbol_currency()
    }

    pub fn fee_report(&self) -> crate::core::reports::FeeReport {
        self.core.query_fee_report()
    }

    pub fn loan_platform(&self) -> crate::core::reports::LoanPlatformReport {
        self.core.query_loan_platform()
    }

    pub fn state_hash(&self) -> crate::core::reports::StateHashReport {
        self.core.query_state_hash()
    }

}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::common::position_direction::PositionDirection;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const SELLER: i64 = 1;
    const BUYER: i64 = 2;

    fn spot_spec_fixed_fee(taker_fee: i64, maker_fee: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee,
            maker_fee,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    #[test]
    fn add_symbol_before_currency_is_rejected_and_does_not_register() {
        let mut api = ExchangeApi::new();
        let rc = api.add_symbol(spot_spec_fixed_fee(0, 0));
        assert_eq!(rc, CommandResultCode::InvalidSymbol);
        assert!(api.ssp().get_symbol(SYMBOL).is_none());

        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec_fixed_fee(0, 0)), CommandResultCode::Success);
    }

    #[test]
    fn spot_ask_bid_full_match_settles_balances_and_conserves_globally() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec_fixed_fee(10, 5)), CommandResultCode::Success);

        assert_eq!(api.add_user(SELLER), CommandResultCode::Success);
        assert_eq!(api.add_user(BUYER), CommandResultCode::Success);

        assert_eq!(
            api.balance_adjustment(SELLER, BASE, 1_000, 1),
            CommandResultCode::Success
        );
        assert_eq!(
            api.balance_adjustment(BUYER, QUOTE, 100_000, 2),
            CommandResultCode::Success
        );

        let ask_rc = api.place_order(PlaceOrderRequest {
            order_id: 1,
            uid: SELLER,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        });
        assert_eq!(ask_rc, CommandResultCode::Success);

        let bid_rc = api.place_order(PlaceOrderRequest {
            order_id: 2,
            uid: BUYER,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 50,
            action: OrderAction::Bid,
            order_type: OrderType::Gtc,
        });
        assert_eq!(bid_rc, CommandResultCode::Success);

        assert_eq!(api.user_account(SELLER, BASE), 0);
        assert_eq!(api.user_locked(SELLER, BASE), 0);
        assert_eq!(api.user_account(SELLER, QUOTE), 45_000);

        assert_eq!(api.user_account(BUYER, BASE), 1_000);
        assert_eq!(api.user_account(BUYER, QUOTE), 40_000);
        assert_eq!(api.user_locked(BUYER, QUOTE), 0);

        assert_eq!(api.fees(QUOTE), 15_000);
        assert_eq!(api.fees(BASE), 0);

        assert_eq!(api.adjustments(BASE), -1_000);
        assert_eq!(api.adjustments(QUOTE), -100_000);

        let base_sum = api.user_account(SELLER, BASE) + api.user_account(BUYER, BASE)
            + api.adjustments(BASE)
            + api.fees(BASE);
        assert_eq!(base_sum, 0, "base 守恒");

        let quote_sum = api.user_account(SELLER, QUOTE) + api.user_account(BUYER, QUOTE)
            + api.adjustments(QUOTE)
            + api.fees(QUOTE);
        assert_eq!(quote_sum, 0, "quote 守恒");

        let l2 = api.request_l2(SYMBOL, 10);
        assert!(l2.bid_prices.is_empty(), "买方完全成交，无残量挂单");
        assert!(l2.ask_prices.is_empty(), "卖方 ASK 已被完全吃掉");
    }

    #[test]
    fn request_l2_reflects_resting_order_before_match() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec_fixed_fee(0, 0)), CommandResultCode::Success);
        api.add_user(SELLER);
        api.balance_adjustment(SELLER, BASE, 1_000, 1);

        api.place_order(PlaceOrderRequest {
            order_id: 1,
            uid: SELLER,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        });

        let l2 = api.request_l2(SYMBOL, 10);
        assert_eq!(l2.ask_prices, vec![50]);
        assert_eq!(l2.ask_volumes, vec![1000]);
        assert!(l2.bid_prices.is_empty());
    }

    #[test]
    fn cancel_order_via_api_releases_lock() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec_fixed_fee(0, 0)), CommandResultCode::Success);
        api.add_user(SELLER);
        api.balance_adjustment(SELLER, BASE, 1_000, 1);

        api.place_order(PlaceOrderRequest {
            order_id: 1,
            uid: SELLER,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        });
        assert_eq!(api.user_locked(SELLER, BASE), 1_000);

        let rc = api.cancel_order(CancelOrderRequest { order_id: 1, uid: SELLER, symbol: SYMBOL });
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(api.user_locked(SELLER, BASE), 0);

        let l2 = api.request_l2(SYMBOL, 10);
        assert!(l2.ask_prices.is_empty());
    }

    const FUT_SYMBOL: i32 = 300;
    const LONG_USER: i64 = 10;
    const SHORT_USER: i64 = 20;

    fn futures_spec_fixed_fee(taker_fee: i64, maker_fee: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: FUT_SYMBOL,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee,
            maker_fee,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    #[test]
    fn add_futures_symbol_rejects_non_futures_symbol_type() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        let rc = api.add_futures_symbol(spot_spec_fixed_fee(0, 0));
        assert_eq!(rc, CommandResultCode::UnsupportedSymbolType);
        assert!(api.ssp().get_symbol(SYMBOL).is_none(), "拒绝的 symbol 不得注册");
    }

    #[test]
    fn futures_long_short_full_match_then_close_settles_pnl_and_conserves_globally() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(
            api.add_futures_symbol(futures_spec_fixed_fee(10, 5)),
            CommandResultCode::Success
        );

        assert_eq!(api.add_user(LONG_USER), CommandResultCode::Success);
        assert_eq!(api.add_user(SHORT_USER), CommandResultCode::Success);

        assert_eq!(api.balance_adjustment(LONG_USER, QUOTE, 10_000, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(SHORT_USER, QUOTE, 10_000, 2), CommandResultCode::Success);

        assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);

        let ask_rc = api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1,
            uid: SHORT_USER,
            symbol: FUT_SYMBOL,
            price: 100,
            size: 10,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
            leverage: 1,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        });
        assert_eq!(ask_rc, CommandResultCode::Success);

        let bid_rc = api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2,
            uid: LONG_USER,
            symbol: FUT_SYMBOL,
            price: 100,
            size: 10,
            action: OrderAction::Bid,
            order_type: OrderType::Gtc,
            leverage: 1,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        });
        assert_eq!(bid_rc, CommandResultCode::Success);

        let long_pos = api.user_position(LONG_USER, FUT_SYMBOL).expect("多头开仓后必有仓位记录");
        assert_eq!(long_pos.direction, PositionDirection::Long);
        assert_eq!(long_pos.open_volume, 10);
        assert_eq!(long_pos.open_init_margin_sum, 1_000);
        assert_eq!(long_pos.open_price_sum, 1_000);
        assert_eq!(long_pos.profit, 0);

        let short_pos = api.user_position(SHORT_USER, FUT_SYMBOL).expect("空头开仓后必有仓位记录");
        assert_eq!(short_pos.direction, PositionDirection::Short);
        assert_eq!(short_pos.open_volume, 10);
        assert_eq!(short_pos.open_init_margin_sum, 1_000);
        assert_eq!(short_pos.open_price_sum, 1_000);
        assert_eq!(short_pos.profit, 0);

        assert_eq!(api.user_account(LONG_USER, QUOTE), 10_000 - 100, "taker fee = size(10)*taker_fee(10)");
        assert_eq!(api.user_account(SHORT_USER, QUOTE), 10_000 - 50, "maker fee = size(10)*maker_fee(5)");
        assert_eq!(api.user_locked(LONG_USER, QUOTE), 0, "期货保证金不占用 locked（纯虚拟仓位字段）");
        assert_eq!(api.user_locked(SHORT_USER, QUOTE), 0);
        assert_eq!(api.fees(QUOTE), 150);

        let conserved = |api: &ExchangeApi| {
            api.user_account(LONG_USER, QUOTE) + api.user_account(SHORT_USER, QUOTE)
                + api.adjustments(QUOTE)
                + api.fees(QUOTE)
        };
        assert_eq!(conserved(&api), 0, "开仓后 quote 守恒");

        assert_eq!(api.set_mark_price(FUT_SYMBOL, 150), CommandResultCode::Success);

        let close_short_rc = api.close_position(ClosePositionRequest {
            order_id: 3,
            uid: SHORT_USER,
            symbol: FUT_SYMBOL,
            action: OrderAction::Bid,
            price: 150,
            size: 10,
            order_type: OrderType::Gtc,
        });
        assert_eq!(close_short_rc, CommandResultCode::Success);

        let close_long_rc = api.close_position(ClosePositionRequest {
            order_id: 4,
            uid: LONG_USER,
            symbol: FUT_SYMBOL,
            action: OrderAction::Ask,
            price: 150,
            size: 10,
            order_type: OrderType::Gtc,
        });
        assert_eq!(close_long_rc, CommandResultCode::Success);

        assert!(api.user_position(LONG_USER, FUT_SYMBOL).is_none(), "多头完全平仓后 position 记录应被拆除");
        assert!(api.user_position(SHORT_USER, FUT_SYMBOL).is_none(), "空头完全平仓后 position 记录应被拆除");

        assert_eq!(
            api.user_account(LONG_USER, QUOTE),
            10_000 - 100  - 100  + 500,
        );
        assert_eq!(
            api.user_account(SHORT_USER, QUOTE),
            10_000 - 50  - 50  - 500,
        );
        assert_eq!(api.fees(QUOTE), 150 + 100 + 50, "累计四笔手续费：150(开仓)+100+50(平仓)");

        assert_eq!(conserved(&api), 0, "平仓结算 PnL 后 quote 依旧守恒");

        let l2 = api.request_l2(FUT_SYMBOL, 10);
        assert!(l2.bid_prices.is_empty());
        assert!(l2.ask_prices.is_empty());
    }

    #[test]
    fn add_currencies_and_symbols_batch() {
        use crate::core::common::core_currency_specification::CoreCurrencySpecification;
        let mut api = ExchangeApi::new();
        api.add_currencies([
            CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() },
            CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() },
        ]);
        assert!(api.ssp().get_currency(BASE).is_some());
        assert!(api.ssp().get_currency(QUOTE).is_some());
        let s1 = CoreSymbolSpecification { symbol_id: 100, symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE, quote_currency: QUOTE, base_scale_k: 1, quote_scale_k: 1, ..Default::default() };
        let mut s2 = s1.clone(); s2.symbol_id = 101; s2.base_currency = QUOTE; s2.quote_currency = BASE;
        api.add_symbols([s1, s2]);
        assert!(api.ssp().get_symbol(100).is_some());
        assert!(api.ssp().get_symbol(101).is_some());
    }

    #[test]
    fn add_symbols_margin_gate_blocks_futures_when_disabled() {
        use crate::core::common::core_currency_specification::CoreCurrencySpecification;
        let mut api = ExchangeApi::new();
        api.add_currencies([
            CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() },
            CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() },
        ]);
        api.core.risk.cfg_margin_trading_enabled = false;
        let fut = CoreSymbolSpecification { symbol_id: 200, symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE, quote_currency: QUOTE, base_scale_k: 1, quote_scale_k: 1, ..Default::default() };
        let spot = CoreSymbolSpecification { symbol_id: 201, symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE, quote_currency: QUOTE, base_scale_k: 1, quote_scale_k: 1, ..Default::default() };
        api.add_symbols([fut, spot]);
        assert!(api.ssp().get_symbol(200).is_none(), "margin 关闭时期货 symbol 被门控跳过(对齐 Java)");
        assert!(api.ssp().get_symbol(201).is_some(), "现货 symbol 恒放行");
    }

    #[test]
    fn add_accounts_batch_seeds_balance_and_conserves() {
        use crate::core::common::core_currency_specification::CoreCurrencySpecification;
        let mut api = ExchangeApi::new();
        api.add_currencies([CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() }]);
        api.add_accounts([(10i64, vec![(QUOTE, 1000i64)]), (11i64, vec![(QUOTE, 500i64)])]);
        assert_eq!(api.ups().get(10).unwrap().accounts.get(&QUOTE).copied().unwrap_or(0), 1000);
        assert_eq!(api.ups().get(11).unwrap().accounts.get(&QUOTE).copied().unwrap_or(0), 500);
        assert!(api.total_balance().is_global_zero(), "seed 后全局守恒(账户 +1500 / 调整桶 -1500)");
        api.add_accounts([(10i64, vec![(QUOTE, 9999i64)])]);
        assert_eq!(api.ups().get(10).unwrap().accounts.get(&QUOTE).copied().unwrap_or(0), 1000, "已存在 uid 跳过,不覆盖");
    }
}
