#[cfg(test)]

mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::position_direction::PositionDirection;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{CancelOrderRequest, ExchangeApi, PlaceFuturesOrderRequest};
    use exchange_core_rs::core::utils::core_arithmetic_utils::{
        calculate_maker_fee, calculate_taker_fee, size_price_to_currency_scale,
    };

    const BASE_CUR: i32 = 1;
    const USD: i32 = 840;
    const SYM: i32 = 9341;
    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    const BASE_SCALE_K: i64 = 1;
    const QUOTE_SCALE_K: i64 = 1;
    const CURRENCY_SCALE_K: i64 = 1;
    const MAKER_FEE: i64 = 1;
    const TAKER_FEE: i64 = 2;
    const FEE_SCALE_K: i64 = 100;

    const PRICE: i64 = 10_000;
    const MAKER_DEPOSIT: i64 = 10_000_000;
    const TAKER_DEPOSIT: i64 = 4_000_000;

    fn spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_CUR,
            quote_currency: USD,
            base_scale_k: BASE_SCALE_K,
            quote_scale_k: QUOTE_SCALE_K,
            maker_fee: MAKER_FEE,
            taker_fee: TAKER_FEE,
            fee_scale_k: FEE_SCALE_K,
            init_margin: 1,
            init_margin_scale_k: 100,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(2000, 5), (100_000, 10)]),
            ..Default::default()
        }
    }

    fn seed() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CUR, CURRENCY_SCALE_K);
        api.add_currency(USD, CURRENCY_SCALE_K);
        assert_eq!(api.add_futures_symbol(spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM, PRICE), CommandResultCode::Success);
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, USD, MAKER_DEPOSIT, 1), CommandResultCode::Success);
        assert_eq!(api.add_user(UID_2), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_2, USD, TAKER_DEPOSIT, 2), CommandResultCode::Success);
        api
    }

    fn place(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        price: i64,
        size: i64,
        action: OrderAction,
        order_type: OrderType,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: SYM,
            price,
            size,
            action,
            order_type,
            leverage: 1,
            margin_mode: MarginMode::Cross,
            reduce_only: false,
        })
    }

    fn maker_fee(filled: i64) -> i64 {
        size_price_to_currency_scale(
            calculate_maker_fee(filled, PRICE, MAKER_FEE, FEE_SCALE_K),
            BASE_SCALE_K,
            QUOTE_SCALE_K,
            CURRENCY_SCALE_K,
        )
    }
    fn taker_fee(filled: i64) -> i64 {
        size_price_to_currency_scale(
            calculate_taker_fee(filled, PRICE, TAKER_FEE, FEE_SCALE_K),
            BASE_SCALE_K,
            QUOTE_SCALE_K,
            CURRENCY_SCALE_K,
        )
    }

    fn assert_conserved(api: &ExchangeApi) {
        let mut total: i64 = api.ups().users.values().map(|p| p.account(USD)).sum();
        total += api.adjustments(USD);
        total += api.fees(USD);
        for p in api.ups().users.values() {
            for pos in p.positions.values() {
                if pos.currency != USD {
                    continue;
                }
                let mark = api.risk().mark_price(pos.symbol).unwrap_or(PRICE);
                total += pos.estimate_pnl(mark);
                total += pos.extra_margin;
            }
        }
        assert_eq!(total, 0, "futures global conservation broken: USD total={total}");
    }

    fn run_scenario(
        maker_action: OrderAction,
        maker_size: i64,
        taker_type: OrderType,
        taker_size: i64,
    ) {
        let mut api = seed();

        assert_eq!(
            place(&mut api, 101, UID_1, PRICE, maker_size, maker_action, OrderType::Gtc),
            CommandResultCode::Success
        );
        assert_eq!(api.fees(USD), 0, "placing maker order charges no fee");

        let taker_action = match maker_action {
            OrderAction::Bid => OrderAction::Ask,
            OrderAction::Ask => OrderAction::Bid,
        };
        let taker_price = match taker_type {
            OrderType::FokBudget | OrderType::IocBudget => taker_size * PRICE,
            _ => PRICE,
        };
        assert_eq!(
            place(&mut api, 102, UID_2, taker_price, taker_size, taker_action, taker_type),
            CommandResultCode::Success
        );

        let filled = maker_size.min(taker_size);

        let maker_dir = if maker_action == OrderAction::Bid { PositionDirection::Long } else { PositionDirection::Short };
        let mp = api.user_position(UID_1, SYM).expect("maker position");
        assert_eq!(mp.direction, maker_dir);
        assert_eq!(mp.open_volume, filled);
        assert_eq!(mp.open_price_sum, PRICE * filled);
        if maker_action == OrderAction::Bid {
            assert_eq!(mp.pending_buy_size, maker_size - filled);
            assert_eq!(mp.pending_sell_size, 0);
        } else {
            assert_eq!(mp.pending_sell_size, maker_size - filled);
            assert_eq!(mp.pending_buy_size, 0);
        }
        assert_eq!(mp.profit, 0);

        let taker_dir = if taker_action == OrderAction::Bid { PositionDirection::Long } else { PositionDirection::Short };
        let taker_rests = matches!(taker_type, OrderType::Gtc);
        let taker_residual = if taker_rests { taker_size - filled } else { 0 };
        let tp = api.user_position(UID_2, SYM).expect("taker position");
        assert_eq!(tp.direction, taker_dir);
        assert_eq!(tp.open_volume, filled);
        assert_eq!(tp.open_price_sum, PRICE * filled);
        if taker_action == OrderAction::Bid {
            assert_eq!(tp.pending_buy_size, taker_residual);
            assert_eq!(tp.pending_sell_size, 0);
        } else {
            assert_eq!(tp.pending_sell_size, taker_residual);
            assert_eq!(tp.pending_buy_size, 0);
        }
        assert_eq!(tp.profit, 0);

        assert_eq!(api.user_account(UID_1, USD), MAKER_DEPOSIT - maker_fee(filled), "maker account");
        assert_eq!(api.user_account(UID_2, USD), TAKER_DEPOSIT - taker_fee(filled), "taker account");
        assert_eq!(api.user_account(UID_1, BASE_CUR), 0);
        assert_eq!(api.user_account(UID_2, BASE_CUR), 0);
        assert_eq!(api.fees(USD), maker_fee(filled) + taker_fee(filled), "USD fee pool");
        assert_conserved(&api);
    }

    #[test]
    fn bid_gtc_maker_ask_ioc_taker_partial() {
        run_scenario(OrderAction::Bid, 100, OrderType::Ioc, 500);
    }

    #[test]
    fn bid_gtc_maker_partial_ask_ioc_taker() {
        run_scenario(OrderAction::Bid, 500, OrderType::Ioc, 100);
    }

    #[test]
    fn bid_gtc_maker_ask_ioc_taker_fully_match() {
        run_scenario(OrderAction::Bid, 500, OrderType::Ioc, 500);
    }

    #[test]
    fn ask_gtc_maker_bid_ioc_taker_partial() {
        run_scenario(OrderAction::Ask, 100, OrderType::Ioc, 500);
    }

    #[test]
    fn ask_gtc_maker_partial_bid_ioc_taker() {
        run_scenario(OrderAction::Ask, 500, OrderType::Ioc, 100);
    }

    #[test]
    fn ask_gtc_maker_partial_bid_gtc_taker() {
        run_scenario(OrderAction::Ask, 500, OrderType::Gtc, 100);
    }

    #[test]
    fn ask_gtc_maker_bid_gtc_taker_partial() {
        run_scenario(OrderAction::Ask, 100, OrderType::Gtc, 500);
    }

    #[test]
    fn ask_gtc_maker_partial_bid_fok_budget_taker() {
        run_scenario(OrderAction::Ask, 500, OrderType::FokBudget, 1);
    }

    #[test]
    fn should_require_taker_fees_gtc_cancel1() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CUR, CURRENCY_SCALE_K);
        api.add_currency(USD, CURRENCY_SCALE_K);
        assert_eq!(api.add_futures_symbol(spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM, PRICE), CommandResultCode::Success);
        assert_eq!(api.add_user(UID_2), CommandResultCode::Success);

        let usdt = 100i64;
        let size = 1i64;
        let fee = taker_fee(size);
        let init_margin = PRICE * size / 100;

        let bid = |oid: i64| PlaceFuturesOrderRequest {
            order_id: oid, uid: UID_2, symbol: SYM, price: PRICE, size,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        };

        assert_eq!(api.balance_adjustment(UID_2, USD, usdt, 1), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(bid(203)), CommandResultCode::RiskNsf);
        assert_eq!(api.balance_adjustment(UID_2, USD, -usdt, 2), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_2, USD, fee - 1, 3), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(bid(203)), CommandResultCode::RiskNsf);
        assert_eq!(api.balance_adjustment(UID_2, USD, 1, 4), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(bid(203)), CommandResultCode::RiskNsf);
        assert_eq!(api.balance_adjustment(UID_2, USD, init_margin, 5), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(bid(203)), CommandResultCode::Success);

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 203, uid: UID_2, symbol: SYM }), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_2, USD), fee + init_margin, "after cancel = fee + initMargin");
        assert_eq!(api.fees(USD), 0);
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn fee_oracle_matches_java_independent_formula() {
        for filled in [1i64, 30, 100] {
            let java_maker = PRICE * filled * QUOTE_SCALE_K * MAKER_FEE / FEE_SCALE_K;
            let java_taker = PRICE * filled * QUOTE_SCALE_K * TAKER_FEE / FEE_SCALE_K;
            assert_eq!(maker_fee(filled), java_maker, "maker@{filled} = Java price*size*step*makerFee/scale");
            assert_eq!(taker_fee(filled), java_taker, "taker@{filled} = Java price*size*step*takerFee/scale");
        }
    }
}
