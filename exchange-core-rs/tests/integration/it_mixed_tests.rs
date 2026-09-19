#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use std::cell::RefCell;
    use std::rc::Rc;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::fund_event::{FundEvent, FundEventType};
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::position_direction::PositionDirection;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest};
    use exchange_core_rs::core::reports::PositionView;

    const QUOTE_ID: i32 = 840;
    const BASE_ID: i32 = 3762;
    const LTC_ID: i32 = 4141;
    const PERP_SYMBOL: i32 = 10000;
    const LTC_SYMBOL: i32 = 10002;
    const EXCHANGE_SYMBOL: i32 = 10003;

    const UID_1: i64 = 1001;
    const UID_2: i64 = 1002;
    const UID_3: i64 = 1003;
    const UID_4: i64 = 1004;
    const UPDATE_PRICE_USER1: i64 = 19000;
    const UPDATE_PRICE_USER2: i64 = 19001;
    const MAX_VALUE: i64 = 4_000_000;

    fn perp_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: PERP_SYMBOL,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            maintenance_margin: BTreeMap::from([(1_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(2_000, 5), (100_000, 10)]),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    fn exchange_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: EXCHANGE_SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn assert_conserved(api: &ExchangeApi) {

        let tcb = api.total_balance();
        assert!(tcb.is_global_zero(), "global conservation broken: {:?}", tcb.global_balances_sum());
    }

    #[test]
    fn mixed_exchange_and_margin() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 10_000), CommandResultCode::Success);
        assert_eq!(api.add_symbol(exchange_spec()), CommandResultCode::Success);

        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 10_000, 1), CommandResultCode::Success);

        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 1005, uid: UID_1, symbol: PERP_SYMBOL, price: 10_000, size: 1,
                action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 10_000, "futures pending order must not touch accounts");
        assert!(api.user_position(UID_1, PERP_SYMBOL).is_some(), "pending position should have been created");

        let spot_order = PlaceOrderRequest {
            order_id: 112233, uid: UID_1, symbol: EXCHANGE_SYMBOL, price: 10_000, size: 1,
            reserve_bid_price: 10_000, action: OrderAction::Bid, order_type: OrderType::Gtc,
        };

        assert_eq!(api.place_order(spot_order.clone()), CommandResultCode::RiskNsf);

        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 139, 2), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_order.clone()), CommandResultCode::RiskNsf);
        assert!(api.user_position(UID_1, PERP_SYMBOL).is_some());

        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 1, 3), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_order), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), 10_140, "accounts = actual holdings = 10140");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 10_020, "spot order lock = 10020");
        assert!(api.user_position(UID_1, PERP_SYMBOL).is_some(), "futures pending position should still be present");
        assert_conserved(&api);
    }

    fn if_deposit(api: &mut ExchangeApi, symbol: i32, amount: i64, txid: i64) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::IfDeposit,
            symbol,
            price: amount,
            order_id: txid,
            ..Default::default()
        })
    }

    fn if_withdraw(api: &mut ExchangeApi, symbol: i32, amount: i64, txid: i64) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::IfWithdraw,
            symbol,
            price: amount,
            order_id: txid,
            ..Default::default()
        })
    }

    fn if_balance(api: &ExchangeApi, symbol: i32) -> i64 {
        api.insurance_fund().futures.get(&symbol).map(|e| e.available).unwrap_or(0)
    }

    fn setup_futures_if() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 10_000), CommandResultCode::Success);
        api
    }

    #[test]
    fn insurance_fund_deposit_keeps_global_reconciliation() {
        let first_deposit = 1_000_000i64;
        let second_deposit = 500i64;

        let mut api = setup_futures_if();

        assert_eq!(if_balance(&api, PERP_SYMBOL), 0);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, first_deposit, 1), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), first_deposit);
        assert_eq!(api.adjustments(QUOTE_ID), -first_deposit, "deposit -> adjustments records a negative offset");
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, -1, 2), CommandResultCode::RiskInvalidAmount);
        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, 0, 3), CommandResultCode::RiskInvalidAmount);
        assert_eq!(if_deposit(&mut api, 99_999, 1_000, 4), CommandResultCode::InvalidSymbol);

        assert_eq!(if_balance(&api, PERP_SYMBOL), first_deposit);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, second_deposit, 5), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), first_deposit + second_deposit);
        assert_eq!(api.adjustments(QUOTE_ID), -(first_deposit + second_deposit));
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn insurance_fund_withdraw_keeps_global_reconciliation() {
        let deposit = 1_000_000i64;
        let first_withdraw = 300_000i64;
        let second_withdraw = 200_000i64;

        let mut api = setup_futures_if();

        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, 100, 1), CommandResultCode::RiskIfInsufficient);
        assert_eq!(if_balance(&api, PERP_SYMBOL), 0);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, deposit, 2), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, first_withdraw, 3), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit - first_withdraw);
        assert_eq!(api.adjustments(QUOTE_ID), -(deposit - first_withdraw));
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, 800_000, 4), CommandResultCode::RiskIfInsufficient);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit - first_withdraw);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, -1, 5), CommandResultCode::RiskInvalidAmount);
        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, 0, 6), CommandResultCode::RiskInvalidAmount);
        assert_eq!(if_withdraw(&mut api, 99_999, 1_000, 7), CommandResultCode::InvalidSymbol);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit - first_withdraw);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, second_withdraw, 8), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit - first_withdraw - second_withdraw);
        assert_eq!(api.adjustments(QUOTE_ID), -(deposit - first_withdraw - second_withdraw));
        assert!(api.total_balance().is_global_zero());
    }

    type Collector = Rc<RefCell<Vec<FundEvent>>>;

    fn new_api_with_collector() -> (ExchangeApi, Collector) {
        let collector: Collector = Rc::new(RefCell::new(Vec::new()));
        let mut api = ExchangeApi::new();
        api.core().with_results_consumer(Box::new(crate::common::FundEventCollector(collector.clone())));
        api
            .add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        (api, collector)
    }

    fn seed(api: &mut ExchangeApi, uid: i64, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE_ID, amount, txid), CommandResultCode::Success);
    }

    #[allow(clippy::too_many_arguments)]
    fn place_fut(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        symbol: i32,
        price: i64,
        size: i64,
        action: OrderAction,
        margin_mode: MarginMode,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol,
            price,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage: 1,
            margin_mode,
            reduce_only: false,
        })
    }

    fn perp_liq_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: PERP_SYMBOL,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            liquidation_fee: 50,
            fee_scale_k: 0,
            maintenance_margin: BTreeMap::from([(1_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 10,
            max_leverage: BTreeMap::from([(2_000, 5), (100_000, 10)]),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    fn ltc_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: LTC_SYMBOL,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: LTC_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 20,
            taker_fee: 30,
            fee_scale_k: 0,
            maintenance_margin: BTreeMap::from([(1_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(2_000, 5), (100_000, 10)]),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    fn open_volume(api: &ExchangeApi, uid: i64, sym: i32) -> i64 {
        api.user_position(uid, sym).map(|p| p.open_volume).unwrap_or(0)
    }

    fn pos_view(api: &ExchangeApi, uid: i64, symbol: i32) -> PositionView {
        api.single_user(uid, 0)
            .positions
            .into_iter()
            .find(|p| p.symbol == symbol)
            .unwrap_or_else(|| panic!("no position view for uid={uid} symbol={symbol}"))
    }

    fn find_event(events: &[FundEvent], ty: FundEventType, uid: i64) -> FundEvent {
        events
            .iter()
            .find(|e| e.event_type == ty && e.uid == uid)
            .cloned()
            .unwrap_or_else(|| panic!("no {ty:?} event for uid={uid}"))
    }

    fn if_balance_ccy(api: &ExchangeApi, currency: i32) -> i64 {
        api.total_balance().if_balances.get(&currency).copied().unwrap_or(0)
    }

    #[test]
    fn mixed_exchange_and_margin2() {
        let deposit = 10_000i64;
        let (mut api, ev) = new_api_with_collector();
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 10_000), CommandResultCode::Success);
        assert_eq!(api.add_symbol(exchange_spec()), CommandResultCode::Success);

        seed(&mut api, UID_1, deposit, 1);
        seed(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place_fut(&mut api, 1005, UID_1, PERP_SYMBOL, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 1006, UID_2, PERP_SYMBOL, 10_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.set_mark_price(PERP_SYMBOL, 15_000), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10);
        assert_eq!(open_volume(&api, UID_1, PERP_SYMBOL), 1);

        let spot_order = PlaceOrderRequest {
            order_id: 112233, uid: UID_1, symbol: EXCHANGE_SYMBOL, price: 10_000, size: 1,
            reserve_bid_price: 10_000, action: OrderAction::Bid, order_type: OrderType::Gtc,
        };
        assert_eq!(api.place_order(spot_order.clone()), CommandResultCode::RiskNsf);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 104, 3), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_order.clone()), CommandResultCode::RiskNsf);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 1, 4), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_order), CommandResultCode::Success);

        assert_eq!(open_volume(&api, UID_1, PERP_SYMBOL), 1);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 10_095, "actual holdings = 10000 - 10 + 104 + 1");

        let locked_evt = ev
            .borrow()
            .iter()
            .filter(|e| e.event_type == FundEventType::Locked && e.uid == UID_1 && e.symbol == EXCHANGE_SYMBOL)
            .last()
            .cloned()
            .expect("exchange LOCKED event for uid1");
        assert_eq!(locked_evt.free, -25, "LOCKED event free");
        assert_eq!(locked_evt.locked, 10_120, "LOCKED event locked = futures margin(100) + spot lock(10020)");
        assert_conserved(&api);
    }

    #[test]
    fn close_position_with_profit() {
        let deposit = 10_000i64;
        let size = 10i64;
        let (price1, price2) = (10_000i64, 15_000i64);

        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 10_000), CommandResultCode::Success);

        seed(&mut api, UID_1, deposit, 1);
        seed(&mut api, UID_2, MAX_VALUE, 2);
        seed(&mut api, UID_3, MAX_VALUE, 3);

        assert_eq!(place_fut(&mut api, 1, UID_1, PERP_SYMBOL, price1, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 2, UID_2, PERP_SYMBOL, price1, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.set_mark_price(PERP_SYMBOL, price2), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 * size, "maker fee 10 per contract");
        assert_eq!(open_volume(&api, UID_1, PERP_SYMBOL), size);

        assert_eq!(place_fut(&mut api, 3, UID_1, PERP_SYMBOL, price2, 12, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 4, UID_3, PERP_SYMBOL, price2, 11, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        let p = pos_view(&api, UID_1, PERP_SYMBOL);
        assert_eq!(p.profit, (price2 - price1) * size, "realized profit = 50000");
        assert_eq!(p.open_volume, 1);
        assert_eq!(p.pending_sell_size, 1);
        assert_eq!(p.open_init_margin_sum, 150);
        assert_eq!(p.unrealized_pnl, 0);
        assert_eq!(p.liquidation_price, 74_418);
        assert_eq!(p.margin_ratio_scale_k, 1);
    }

    #[test]
    fn close_position_with_profit2() {
        let deposit = 20_000i64;
        let size = 10i64;
        let (price1, price2) = (10_000i64, 9_000i64);

        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 10_000), CommandResultCode::Success);

        seed(&mut api, UID_1, deposit, 1);
        seed(&mut api, UID_2, MAX_VALUE, 2);
        seed(&mut api, UID_3, MAX_VALUE, 3);
        seed(&mut api, UID_4, MAX_VALUE, 4);

        assert_eq!(place_fut(&mut api, 1, UID_1, PERP_SYMBOL, price1, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 2, UID_2, PERP_SYMBOL, price1, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.set_mark_price(PERP_SYMBOL, price2), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 * size);
        assert_eq!(open_volume(&api, UID_1, PERP_SYMBOL), size);

        assert_eq!(place_fut(&mut api, 3, UID_1, PERP_SYMBOL, price2, 12, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 4, UID_3, PERP_SYMBOL, price2, 11, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        let p = pos_view(&api, UID_1, PERP_SYMBOL);
        assert_eq!(p.profit, (price2 - price1) * size, "realized profit = -10000");
        assert_eq!(p.open_volume, 1);
        assert_eq!(p.pending_sell_size, 1);
        assert_eq!(p.open_init_margin_sum, 90);
        assert_eq!(p.unrealized_pnl, 0);
        assert_eq!(p.liquidation_price, 18_697);
        assert_eq!(p.margin_ratio_scale_k, 4);

        assert_eq!(place_fut(&mut api, 5, UID_4, PERP_SYMBOL, price2, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        let p = pos_view(&api, UID_1, PERP_SYMBOL);
        assert_eq!(p.profit, (price2 - price1) * size);
        assert_eq!(p.open_volume, 2);
        assert_eq!(p.pending_sell_size, 0);
        assert_eq!(p.open_init_margin_sum, 90 * 2);
        assert_eq!(p.unrealized_pnl, 0);
        assert_eq!(p.liquidation_price, 13_821);
        assert_eq!(p.margin_ratio_scale_k, 9);
    }

    #[test]
    fn isolated_liquidation_fully_matched_with_fee() {
        let user_deposit = 2_000i64;
        let maker_deposit = 100_000i64;
        let user_size = 10i64;
        let open_price = 10_000i64;
        let trigger_price = 9_900i64;
        let bp_fill = 9_970i64;

        let (mut api, ev) = new_api_with_collector();
        assert_eq!(api.add_futures_symbol(perp_liq_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, open_price), CommandResultCode::Success);

        seed(&mut api, UID_1, user_deposit, 1);
        seed(&mut api, UID_2, maker_deposit, 2);
        seed(&mut api, UID_3, maker_deposit, 3);

        assert_eq!(place_fut(&mut api, 10001, UID_1, PERP_SYMBOL, open_price, user_size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10002, UID_2, PERP_SYMBOL, open_price, user_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, UID_1, PERP_SYMBOL), user_size);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), user_deposit - 10 * user_size);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), maker_deposit - 20 * user_size);

        assert_eq!(place_fut(&mut api, 10003, UID_3, PERP_SYMBOL, bp_fill, user_size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(PERP_SYMBOL, trigger_price), CommandResultCode::Success);

        let profit = (open_price - bp_fill) * user_size;
        assert!(api.user_position(UID_1, PERP_SYMBOL).is_none(), "loser fully closed");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), user_deposit - 10 * user_size - profit - 50 * user_size - 20 * user_size, "= 900");

        let events = ev.borrow().clone();
        let alert = find_event(&events, FundEventType::LiquidationAlert, UID_1);
        assert_eq!(alert.currency, QUOTE_ID);
        assert_eq!(alert.symbol, PERP_SYMBOL);
        assert_eq!(alert.direction, PositionDirection::Long);
        assert_eq!(alert.free, 0);
        assert_eq!(alert.profit, 0);
        assert_eq!(alert.locked, 0);
        assert_eq!(alert.open_price_sum, 100_000);
        assert_eq!(alert.open_volume, 10);
        assert_eq!(alert.unrealized_profit, -1_000);
        assert_eq!(alert.liquidation_price, 14_850);
        assert_eq!(alert.margin_ratio_scale_k, -10);

        let close = find_event(&events, FundEventType::LiquidationClose, UID_1);
        assert_eq!(close.direction, PositionDirection::Long);
        assert_eq!(close.locked, 0);
        assert_eq!(close.profit, -300);
        assert_eq!(close.open_price_sum, 0);
        assert_eq!(close.open_volume, 0);

        let fee = find_event(&events, FundEventType::LiquidationFee, UID_1);
        assert_eq!(fee.free, 900);
        assert_eq!(fee.locked, 0);

        assert_eq!(fee.profit, 0);
        assert_eq!(fee.open_price_sum, 0);
        assert_eq!(fee.open_volume, 0);
        assert_conserved(&api);
    }

    #[test]
    fn cross_liquidation_fully_matched_with_fee() {
        let user_deposit = 2_000i64;
        let maker_deposit = 100_000i64;
        let user_size = 10i64;
        let open_price = 10_000i64;
        let trigger_price = 9_900i64;

        let (mut api, ev) = new_api_with_collector();
        assert_eq!(api.add_futures_symbol(perp_liq_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, open_price), CommandResultCode::Success);

        seed(&mut api, UID_1, user_deposit, 1);
        seed(&mut api, UID_2, maker_deposit, 2);
        seed(&mut api, UID_3, maker_deposit, 3);

        assert_eq!(place_fut(&mut api, 10001, UID_1, PERP_SYMBOL, open_price, user_size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10002, UID_2, PERP_SYMBOL, open_price, user_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, UID_1, PERP_SYMBOL), user_size);

        assert_eq!(place_fut(&mut api, 10003, UID_3, PERP_SYMBOL, trigger_price, user_size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(PERP_SYMBOL, trigger_price), CommandResultCode::Success);

        let profit = (open_price - trigger_price) * user_size;
        assert!(api.user_position(UID_1, PERP_SYMBOL).is_none());
        assert_eq!(api.user_account(UID_1, QUOTE_ID), user_deposit - 10 * user_size - profit - 50 * user_size - 20 * user_size, "= 200");

        let events = ev.borrow().clone();
        let alert = find_event(&events, FundEventType::LiquidationAlert, UID_1);
        assert_eq!(alert.direction, PositionDirection::Long);
        assert_eq!(alert.free, 0);
        assert_eq!(alert.profit, 0);
        assert_eq!(alert.locked, 0);
        assert_eq!(alert.open_price_sum, 100_000);
        assert_eq!(alert.open_volume, 10);
        assert_eq!(alert.unrealized_profit, -1_000);
        assert_eq!(alert.liquidation_price, -1);
        assert_eq!(alert.margin_ratio_scale_k, 550);

        let close = find_event(&events, FundEventType::LiquidationClose, UID_1);
        assert_eq!(close.free, 1_700);
        assert_eq!(close.locked, 0);
        assert_eq!(close.profit, -1_000);
        assert_eq!(close.open_price_sum, 0);
        assert_eq!(close.open_volume, 0);

        let fee = find_event(&events, FundEventType::LiquidationFee, UID_1);
        assert_eq!(fee.free, 200);
        assert_eq!(fee.locked, 0);

        assert_eq!(fee.profit, 0);
        assert_eq!(fee.open_price_sum, 0);
        assert_eq!(fee.open_volume, 0);
        assert_conserved(&api);
    }

    #[test]
    fn isolated_liquidation_partial_matched_with_if_takeover() {
        let user_deposit = 2_000i64;
        let maker_deposit = 100_000i64;
        let user_size = 10i64;
        let open_price = 10_000i64;
        let trigger_price = 9_900i64;
        let bp_fill = 9_970i64;
        let if_amount = 500_000i64;

        let (mut api, ev) = new_api_with_collector();
        assert_eq!(api.add_futures_symbol(perp_liq_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, open_price), CommandResultCode::Success);

        seed(&mut api, UID_1, user_deposit, 1);
        seed(&mut api, UID_2, maker_deposit, 2);
        seed(&mut api, UID_3, maker_deposit, 3);

        assert_eq!(api.insurance_fund_deposit(PERP_SYMBOL, if_amount, 10_000), CommandResultCode::Success);

        assert_eq!(place_fut(&mut api, 30001, UID_1, PERP_SYMBOL, open_price, user_size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 30002, UID_2, PERP_SYMBOL, open_price, user_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, UID_1, PERP_SYMBOL), user_size);

        assert_eq!(if_balance_ccy(&api, QUOTE_ID), if_amount);
        assert!(api.total_balance().is_global_zero());

        let market_can_take = 5i64;
        assert_eq!(place_fut(&mut api, 30003, UID_3, PERP_SYMBOL, bp_fill, market_can_take, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(PERP_SYMBOL, trigger_price), CommandResultCode::Success);

        assert!(api.user_position(UID_1, PERP_SYMBOL).is_none());
        let liq_fee = 50 * user_size / 2;
        let close_fee = 5 * 20;
        let profit = -300i64;
        assert_eq!(api.user_account(UID_1, QUOTE_ID), user_deposit - 10 * user_size - liq_fee - close_fee + profit, "= 1250");

        assert_eq!(if_balance_ccy(&api, QUOTE_ID), if_amount + 250 - 350);

        let events = ev.borrow().clone();
        let if_close = find_event(&events, FundEventType::IfPositionClose, UID_1);
        assert_eq!(if_close.direction, PositionDirection::Long);
        assert_eq!(if_close.locked, 0);
        assert_eq!(if_close.profit, -300);
        assert_eq!(if_close.open_price_sum, 0);
        assert_eq!(if_close.open_volume, 0);
        assert_conserved(&api);
    }

    #[test]
    fn isolated_liquidation_partial_matched_with_adl_takeover() {
        let user_deposit = 2_000i64;
        let maker_deposit = 100_000i64;
        let user_size = 10i64;
        let open_price = 10_000i64;
        let trigger_price = 9_900i64;
        let bp_fill = 9_970i64;
        let if_amount = 45_000i64;

        let (mut api, ev) = new_api_with_collector();
        assert_eq!(api.add_futures_symbol(perp_liq_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, open_price), CommandResultCode::Success);

        seed(&mut api, UID_1, user_deposit, 1);
        seed(&mut api, UID_2, maker_deposit, 2);
        seed(&mut api, UID_3, maker_deposit, 3);

        assert_eq!(api.insurance_fund_deposit(PERP_SYMBOL, if_amount, 10_000), CommandResultCode::Success);

        assert_eq!(place_fut(&mut api, 30001, UID_1, PERP_SYMBOL, open_price, user_size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 30002, UID_2, PERP_SYMBOL, open_price, user_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, UID_1, PERP_SYMBOL), user_size);

        assert_eq!(if_balance_ccy(&api, QUOTE_ID), if_amount);
        assert!(api.total_balance().is_global_zero());

        let market_can_take = 4i64;
        assert_eq!(place_fut(&mut api, 30003, UID_3, PERP_SYMBOL, bp_fill, market_can_take, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(PERP_SYMBOL, trigger_price), CommandResultCode::Success);

        assert!(api.user_position(UID_1, PERP_SYMBOL).is_none());
        let liq_fee = 50 * user_size * 4 / 10;
        let close_fee = 4 * 20;
        let profit = -300i64;
        assert_eq!(api.user_account(UID_1, QUOTE_ID), user_deposit - 10 * user_size - liq_fee - close_fee + profit, "= 1320");

        let maker = api.user_position(UID_2, PERP_SYMBOL).expect("maker keeps a reduced position");
        assert_eq!(maker.open_volume, user_size - 6);
        assert_eq!(maker.open_init_margin_sum, 400);
        assert_eq!(maker.open_price_sum, 40_180);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), maker_deposit - 20 * user_size);

        assert_eq!(if_balance_ccy(&api, QUOTE_ID), if_amount + 4 * 50);

        let events = ev.borrow().clone();
        let adl_close = find_event(&events, FundEventType::AdlPositionClose, UID_2);
        assert_eq!(adl_close.direction, PositionDirection::Short);
        assert_eq!(adl_close.free, 99_400);
        assert_eq!(adl_close.locked, 400);
        assert_eq!(adl_close.profit, 0);
        assert_eq!(adl_close.open_price_sum, 40_180);
        assert_eq!(adl_close.open_volume, 4);
        assert_eq!(adl_close.unrealized_profit, 580);
        assert_eq!(adl_close.liquidation_price, 23_330);
        assert_eq!(adl_close.margin_ratio_scale_k, 1);

        let adl_origin = find_event(&events, FundEventType::AdlOriginClose, UID_1);
        assert_eq!(adl_origin.direction, PositionDirection::Long);
        assert_eq!(adl_origin.free, 1_620);
        assert_eq!(adl_origin.locked, 0);
        assert_eq!(adl_origin.profit, -300);
        assert_eq!(adl_origin.open_price_sum, 0);
        assert_eq!(adl_origin.open_volume, 0);
        assert_conserved(&api);
    }

    #[test]
    fn isolated_liquidation_partial_matched_with_if_takeover_funding_fee() {
        let user_deposit = 2_000i64;
        let maker_deposit = 100_000i64;
        let user_size = 10i64;
        let open_price = 10_000i64;
        let trigger_price = 9_900i64;
        let bp_fill = 9_970i64;
        let if_amount = 1_000_000i64;

        let (mut api, _ev) = new_api_with_collector();
        assert_eq!(api.add_futures_symbol(perp_liq_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, open_price), CommandResultCode::Success);

        seed(&mut api, UID_1, user_deposit, 1);
        seed(&mut api, UID_2, maker_deposit, 2);
        seed(&mut api, UID_3, maker_deposit, 3);

        assert_eq!(api.insurance_fund_deposit(PERP_SYMBOL, if_amount, 10_000), CommandResultCode::Success);

        assert_eq!(place_fut(&mut api, 30001, UID_1, PERP_SYMBOL, open_price, user_size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 30002, UID_2, PERP_SYMBOL, open_price, user_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, UID_1, PERP_SYMBOL), user_size);
        assert_eq!(if_balance_ccy(&api, QUOTE_ID), if_amount);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(api.set_mark_price(PERP_SYMBOL, trigger_price), CommandResultCode::Success);
        seed(&mut api, UPDATE_PRICE_USER1, MAX_VALUE, 4);
        seed(&mut api, UPDATE_PRICE_USER2, MAX_VALUE, 5);
        assert_eq!(place_fut(&mut api, 40001, UPDATE_PRICE_USER1, PERP_SYMBOL, trigger_price, 10, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 40002, UPDATE_PRICE_USER2, PERP_SYMBOL, trigger_price, 10, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        let market_can_take = 5i64;
        assert_eq!(place_fut(&mut api, 30003, UID_3, PERP_SYMBOL, bp_fill, market_can_take, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(PERP_SYMBOL, trigger_price), CommandResultCode::Success);

        assert!(api.user_position(UID_1, PERP_SYMBOL).is_none());
        let liq_fee = 50 * user_size / 2;
        let close_fee = 5 * 20;
        let profit = -300i64;
        assert_eq!(api.user_account(UID_1, QUOTE_ID), user_deposit - 10 * user_size - liq_fee - close_fee + profit, "= 1250");
        assert_eq!(if_balance_ccy(&api, QUOTE_ID), if_amount + 250 - 350);

        assert_eq!(api.user_position(UID_2, PERP_SYMBOL).unwrap().profit, 0);
        assert_eq!(api.user_position(UID_3, PERP_SYMBOL).unwrap().profit, 0);
        assert_eq!(api.user_position(UPDATE_PRICE_USER1, PERP_SYMBOL).unwrap().profit, 0);
        assert_eq!(api.user_position(UPDATE_PRICE_USER2, PERP_SYMBOL).unwrap().profit, 0);

        assert_eq!(api.settle_funding_fees(PERP_SYMBOL, OrderAction::Bid, 1, 10_000, 9999), CommandResultCode::Success);

        assert!(api.user_position(UID_1, PERP_SYMBOL).is_none());
        assert_eq!(api.user_position(UID_2, PERP_SYMBOL).unwrap().profit, 7);
        assert_eq!(api.user_position(UID_3, PERP_SYMBOL).unwrap().profit, -4);
        assert_eq!(api.user_position(UPDATE_PRICE_USER1, PERP_SYMBOL).unwrap().profit, -9);
        assert_eq!(api.user_position(UPDATE_PRICE_USER2, PERP_SYMBOL).unwrap().profit, 6);
        assert_conserved(&api);
    }

    #[test]
    fn cross_margin_alert_isolated_margin_excluded() {
        let deposit = 300i64;
        let size = 1i64;
        let entry_price = 10_000i64;
        let cross_mark_after_drop = 9_885i64;

        let (mut api, ev) = new_api_with_collector();
        api.add_currency(LTC_ID, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(ltc_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, entry_price), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(LTC_SYMBOL, entry_price), CommandResultCode::Success);

        seed(&mut api, UID_1, deposit, 1);
        seed(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place_fut(&mut api, 1, UID_1, PERP_SYMBOL, entry_price, size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 2, UID_2, PERP_SYMBOL, entry_price, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(place_fut(&mut api, 3, UID_1, LTC_SYMBOL, entry_price, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 4, UID_2, LTC_SYMBOL, entry_price, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.single_user(UID_1, 0).positions.len(), 2);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(LTC_SYMBOL, cross_mark_after_drop), CommandResultCode::Success);

        let events = ev.borrow().clone();
        let cross_alert_fired = events.iter().any(|e| {
            e.event_type == FundEventType::MarginAlert && e.uid == UID_1 && e.symbol == LTC_SYMBOL
        });
        assert!(
            cross_alert_fired,
            "LTC CROSS position must fire MARGIN_ALERT (calculateCrossAvailableCurrency must strip the ISO virtual lock)"
        );
    }

    #[test]
    fn cross_bp_multi_position_allocation_regression_lock() {
        let deposit = 500i64;
        let size = 1i64;
        let entry_price = 10_000i64;
        let btc_mark_after_drop = 9_600i64;

        let (mut api, ev) = new_api_with_collector();
        api.add_currency(LTC_ID, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(ltc_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, entry_price), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(LTC_SYMBOL, entry_price), CommandResultCode::Success);

        seed(&mut api, UID_1, deposit, 1);
        seed(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place_fut(&mut api, 1, UID_1, PERP_SYMBOL, entry_price, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 2, UID_2, PERP_SYMBOL, entry_price, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(place_fut(&mut api, 3, UID_1, LTC_SYMBOL, entry_price, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 4, UID_2, LTC_SYMBOL, entry_price, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.single_user(UID_1, 0).positions.len(), 2);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(PERP_SYMBOL, btc_mark_after_drop), CommandResultCode::Success);

        assert!(api.total_balance().is_global_zero(), "multi-position BP allocation must conserve the global ledger");

        let events = ev.borrow().clone();
        let btc_liquidation_alert = events.iter().any(|e| {
            e.event_type == FundEventType::LiquidationAlert && e.uid == UID_1 && e.symbol == PERP_SYMBOL
        });
        assert!(btc_liquidation_alert, "BTC CROSS must fire LIQUIDATION_ALERT (multi-position BP allocation path)");

        assert!(api.user_position(UID_1, LTC_SYMBOL).is_some(), "LTC CROSS sibling position must not be collaterally liquidated");
    }
}
