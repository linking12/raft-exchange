#[cfg(test)]

mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest};

    const SYMBOL_MARGIN: i32 = 5991;
    const CURRENCY_USD: i32 = 840;
    const CURRENCY_JPY: i32 = 392;
    const MARK: i64 = 10_000;

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;
    const UID_3: i64 = 1_440_003;
    const UID_4: i64 = 1_440_004;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn leverage_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    fn margin_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL_MARGIN,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: CURRENCY_USD,
            quote_currency: CURRENCY_JPY,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 2,
            taker_fee: 3,
            fee_scale_k: 0,
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 0,
            max_leverage: leverage_table(),
            init_margin: 1,
            init_margin_scale_k: 21,
            ..Default::default()
        }
    }

    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(CURRENCY_USD, 1);
        api.add_currency(CURRENCY_JPY, 1);
        assert_eq!(api.add_futures_symbol(margin_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_MARGIN, MARK), CommandResultCode::Success);
        for uid in [UID_1, UID_2, UID_3, UID_4] {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, CURRENCY_JPY, 10_000_000, 1), CommandResultCode::Success);
        }
        api
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
            symbol: SYMBOL_MARGIN,
            price,
            size,
            action,
            order_type,
            leverage: 0,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        })
    }

    fn assert_conserved(api: &ExchangeApi) {
        for &cur in api.ssp().currencies.keys() {
            let mut total: i64 = api.ups().users.values().map(|p| p.account(cur)).sum();
            total += api.adjustments(cur);
            total += api.fees(cur);
            for p in api.ups().users.values() {
                for pos in p.positions.values() {
                    if pos.currency != cur {
                        continue;
                    }
                    let mark = api
                        .risk()
                        .mark_price(pos.symbol)
                        .unwrap_or_else(|| panic!("open position on symbol {} missing mark price", pos.symbol));
                    total += pos.estimate_pnl(mark);
                    total += pos.extra_margin;
                }
            }
            assert_eq!(total, 0, "futures global conservation broken: currency={cur} total={total}");
        }
    }

    fn run_multi_buy(order_type: OrderType, sweep_size: i64) {
        let mut api = setup();

        assert_eq!(place(&mut api, 101, UID_1, 160_000, 7, OrderAction::Ask, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(place(&mut api, 202, UID_2, 159_900, 10, OrderAction::Ask, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(place(&mut api, 303, UID_3, 160_000, 3, OrderAction::Ask, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(place(&mut api, 304, UID_3, 160_500, 20, OrderAction::Ask, OrderType::Gtc), CommandResultCode::Success);

        assert_eq!(place(&mut api, 405, UID_4, 160_500, sweep_size, OrderAction::Bid, order_type), CommandResultCode::Success);

        assert_conserved(&api);
    }

    fn run_multi_sell(order_type: OrderType, sweep_size: i64) {
        let mut api = setup();

        assert_eq!(place(&mut api, 101, UID_1, 160_000, 12, OrderAction::Bid, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(place(&mut api, 202, UID_2, 159_900, 1, OrderAction::Bid, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(place(&mut api, 303, UID_3, 160_000, 8, OrderAction::Bid, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(place(&mut api, 304, UID_3, 160_500, 1, OrderAction::Bid, OrderType::Gtc), CommandResultCode::Success);

        assert_eq!(place(&mut api, 405, UID_4, 159_900, sweep_size, OrderAction::Ask, order_type), CommandResultCode::Success);

        assert_conserved(&api);
    }

    #[test]
    fn multi_buy_no_rejection_margin_gtc() {
        run_multi_buy(OrderType::Gtc, 40);
    }

    #[test]
    fn multi_buy_no_rejection_margin_ioc() {
        run_multi_buy(OrderType::Ioc, 40);
    }

    #[test]
    fn multi_buy_with_size_rejection_margin_gtc() {
        run_multi_buy(OrderType::Gtc, 41);
    }

    #[test]
    fn multi_buy_with_size_rejection_margin_ioc() {
        run_multi_buy(OrderType::Ioc, 41);
    }

    #[test]
    fn multi_sell_no_rejection_margin_gtc() {
        run_multi_sell(OrderType::Gtc, 22);
    }

    #[test]
    fn multi_sell_no_rejection_margin_ioc() {
        run_multi_sell(OrderType::Ioc, 22);
    }

    #[test]
    fn multi_sell_with_size_rejection_margin_gtc() {
        run_multi_sell(OrderType::Gtc, 23);
    }

    #[test]
    fn multi_sell_with_size_rejection_margin_ioc() {
        run_multi_sell(OrderType::Ioc, 23);
    }
}
