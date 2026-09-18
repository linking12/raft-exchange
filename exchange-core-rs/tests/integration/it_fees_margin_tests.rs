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

    const USD: i32 = 840;
    const JPY: i32 = 392;
    const SYM: i32 = 5991;
    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    const BASE_SCALE_K: i64 = 100_000;
    const QUOTE_SCALE_K: i64 = 10;
    const CURRENCY_SCALE_K: i64 = 1_000_000;
    const MAKER_FEE: i64 = 2;
    const TAKER_FEE: i64 = 3;
    const FEE_SCALE_K: i64 = 0;

    const PRICE: i64 = 10_770;
    const DEPOSIT: i64 = 1_000_000_000;

    fn spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: USD,
            quote_currency: JPY,
            base_scale_k: BASE_SCALE_K,
            quote_scale_k: QUOTE_SCALE_K,
            maker_fee: MAKER_FEE,
            taker_fee: TAKER_FEE,
            fee_scale_k: FEE_SCALE_K,
            init_margin: 1,
            init_margin_scale_k: 21,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(2000, 5), (100_000, 10)]),
            ..Default::default()
        }
    }

    fn seed() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(USD, CURRENCY_SCALE_K);
        api.add_currency(JPY, CURRENCY_SCALE_K);
        assert_eq!(api.add_futures_symbol(spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM, PRICE), CommandResultCode::Success);
        api
    }

    fn seed_user(api: &mut ExchangeApi, uid: i64, jpy: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, JPY, jpy, txid), CommandResultCode::Success);
    }

    #[allow(clippy::too_many_arguments)]
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
        let mut total: i64 = api.ups().users.values().map(|p| p.account(JPY)).sum();
        total += api.adjustments(JPY);
        total += api.fees(JPY);
        for p in api.ups().users.values() {
            for pos in p.positions.values() {
                if pos.currency != JPY {
                    continue;
                }
                let mark = api.risk().mark_price(pos.symbol).unwrap_or(PRICE);
                total += pos.estimate_pnl(mark);
                total += pos.extra_margin;
            }
        }
        assert_eq!(total, 0, "futures global conservation broken: JPY total={total}");
    }

    #[test]
    fn ask_gtc_maker_partial_bid_ioc_taker() {
        let mut api = seed();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        assert_eq!(place(&mut api, 101, UID_1, PRICE, 40, OrderAction::Ask, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(api.fees(JPY), 0);

        assert_eq!(place(&mut api, 102, UID_2, PRICE, 30, OrderAction::Bid, OrderType::Ioc), CommandResultCode::Success);

        let filled = 30i64;
        let mp = api.user_position(UID_1, SYM).expect("maker position");
        assert_eq!(mp.direction, PositionDirection::Short);
        assert_eq!(mp.open_volume, filled);
        assert_eq!(mp.pending_sell_size, 10);
        assert_eq!(mp.pending_buy_size, 0);
        assert_eq!(mp.open_price_sum, PRICE * filled);
        let tp = api.user_position(UID_2, SYM).expect("taker position");
        assert_eq!(tp.direction, PositionDirection::Long);
        assert_eq!(tp.open_volume, filled);
        assert_eq!(tp.pending_sell_size, 0);
        assert_eq!(tp.pending_buy_size, 0);
        assert_eq!(tp.open_price_sum, PRICE * filled);

        assert_eq!(api.user_account(UID_1, JPY), DEPOSIT - maker_fee(filled));
        assert_eq!(api.user_account(UID_2, JPY), DEPOSIT - taker_fee(filled));
        assert_eq!(api.user_account(UID_1, USD), 0);
        assert_eq!(api.user_account(UID_2, USD), 0);
        assert_eq!(api.fees(JPY), maker_fee(filled) + taker_fee(filled));
        assert_eq!(api.fees(USD), 0);
        assert_conserved(&api);
    }

    #[test]
    fn bid_gtc_maker_partial_ask_ioc_taker() {
        let mut api = seed();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        assert_eq!(place(&mut api, 101, UID_1, PRICE, 50, OrderAction::Bid, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(api.fees(JPY), 0);

        assert_eq!(place(&mut api, 102, UID_2, PRICE, 30, OrderAction::Ask, OrderType::Ioc), CommandResultCode::Success);

        let filled = 30i64;
        let mp = api.user_position(UID_1, SYM).expect("maker position");
        assert_eq!(mp.direction, PositionDirection::Long);
        assert_eq!(mp.open_volume, filled);
        assert_eq!(mp.pending_buy_size, 20);
        assert_eq!(mp.pending_sell_size, 0);
        assert_eq!(mp.open_price_sum, PRICE * filled);
        let tp = api.user_position(UID_2, SYM).expect("taker position");
        assert_eq!(tp.direction, PositionDirection::Short);
        assert_eq!(tp.open_volume, filled);
        assert_eq!(tp.pending_buy_size, 0);
        assert_eq!(tp.pending_sell_size, 0);
        assert_eq!(tp.open_price_sum, PRICE * filled);

        assert_eq!(api.user_account(UID_1, JPY), DEPOSIT - maker_fee(filled));
        assert_eq!(api.user_account(UID_2, JPY), DEPOSIT - taker_fee(filled));
        assert_eq!(api.fees(JPY), maker_fee(filled) + taker_fee(filled));
        assert_eq!(api.fees(USD), 0);
        assert_conserved(&api);
    }

    #[test]
    fn should_not_take_fees_for_cancel_ask() {
        let mut api = seed();
        seed_user(&mut api, UID_1, DEPOSIT, 1);

        assert_eq!(place(&mut api, 101, UID_1, PRICE, 40, OrderAction::Ask, OrderType::Gtc), CommandResultCode::Success);
        let p = api.user_position(UID_1, SYM).expect("resting position");
        assert_eq!(p.direction, PositionDirection::Short);
        assert_eq!(p.open_volume, 0);
        assert_eq!(p.pending_sell_size, 40);
        assert_eq!(api.user_account(UID_1, JPY), DEPOSIT, "unfilled order does not touch the account");
        assert_eq!(api.fees(JPY), 0);
        assert_conserved(&api);

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 101, uid: UID_1, symbol: SYM }),
            CommandResultCode::Success
        );

        assert!(
            api.user_position(UID_1, SYM).map_or(true, |p| p.open_volume == 0 && p.pending_sell_size == 0 && p.pending_buy_size == 0),
            "no resting size should remain after cancel"
        );
        assert_eq!(api.user_account(UID_1, JPY), DEPOSIT);
        assert_eq!(api.fees(JPY), 0);
        assert_eq!(api.fees(USD), 0);
        assert_conserved(&api);
    }

    #[test]
    fn fee_oracle_matches_java_independent_formula() {
        assert_eq!(maker_fee(30), MAKER_FEE * 30, "Java makerFee×30 = 60");
        assert_eq!(taker_fee(30), TAKER_FEE * 30, "Java takerFee×30 = 90");
    }
}
