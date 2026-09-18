#[cfg(test)]

mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest};

    const USD: i32 = 840;
    const UID_1: i64 = 1;
    const UID_2: i64 = 2;
    const UID_3: i64 = 3;

    fn assert_conserved(api: &ExchangeApi) {
        let tcb = api.total_balance();
        assert!(tcb.is_global_zero(), "Global conservation broken: {:?}", tcb.global_balances_sum());
    }

    fn seed_user(api: &mut ExchangeApi, uid: i64, currency: i32, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, currency, amount, txid), CommandResultCode::Success);
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

    #[test]
    fn h1_liquidation_fee_dynamic_rate_not_amplified_by_taker_size() {
        const BASE_ID: i32 = 999;
        const SYM: i32 = 60001;
        let spec = CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_ID,
            quote_currency: USD,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 100,
            maker_fee: 50,
            liquidation_fee: 100,
            fee_scale_k: 10_000,
            init_margin: 1,
            init_margin_scale_k: 100,
            maintenance_margin: BTreeMap::from([(1_000, 5), (1_000_000, 10)]),
            maintenance_margin_scale_k: 10,
            max_leverage: BTreeMap::from([(2_000, 5), (1_000_000, 10)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(spec), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM, 10_000), CommandResultCode::Success);

        let user_size = 10i64;
        let open_price = 10_000i64;
        let liquidation_price = 9_900i64;
        let bp_fill_price = 10_103i64;

        seed_user(&mut api, UID_1, USD, 200_000, 1);
        seed_user(&mut api, UID_2, USD, 2_000_000, 2);
        seed_user(&mut api, UID_3, USD, 2_000_000, 3);

        assert_eq!(place_fut(&mut api, 60101, UID_1, SYM, open_price, user_size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 60102, UID_2, SYM, open_price, user_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(place_fut(&mut api, 60103, UID_3, SYM, bp_fill_price, user_size + 15, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(SYM, liquidation_price), CommandResultCode::Success);

        let expected_final = 200_000i64 - 500 + 1030 - 1011 - 1011;

        assert!(api.user_position(UID_1, SYM).is_none(), "Loser position should be fully closed");
        assert_eq!(api.user_account(UID_1, USD), expected_final, "Liquidation fee should not be amplified by takerSize");
        assert_conserved(&api);
    }

    #[test]
    fn h2_fok_budget_actual_matched_below_budget_refunds_fee_delta() {
        const BASE_ID: i32 = 998;
        const SYM: i32 = 60201;
        let spec = CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE_ID,
            quote_currency: USD,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 100,
            maker_fee: 50,
            fee_scale_k: 10_000,
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_symbol(spec), CommandResultCode::Success);

        let user_quote_deposit = 1_000_000i64;
        let maker_base_deposit = 100i64;
        seed_user(&mut api, UID_1, USD, user_quote_deposit, 1);
        seed_user(&mut api, UID_2, BASE_ID, maker_base_deposit, 2);

        let maker_size = 10i64;
        let maker_price = 120i64;
        assert_eq!(
            api.place_order(PlaceOrderRequest {
                order_id: 60301,
                uid: UID_2,
                symbol: SYM,
                price: maker_price,
                size: maker_size,
                reserve_bid_price: maker_price,
                action: OrderAction::Ask,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::Success
        );

        let taker_size = 10i64;
        let budget = 1_500i64;
        assert_eq!(
            api.place_order(PlaceOrderRequest {
                order_id: 60302,
                uid: UID_1,
                symbol: SYM,
                price: budget,
                size: taker_size,
                reserve_bid_price: budget,
                action: OrderAction::Bid,
                order_type: OrderType::FokBudget,
            }),
            CommandResultCode::Success
        );

        let expected_paid = 1_200i64 + 12;
        let expected_accounts = user_quote_deposit - expected_paid;

        assert_eq!(api.user_locked(UID_1, USD), 0, "exchangeLocked must be zero after FOK_BUDGET fully fills");
        assert_eq!(api.user_account(UID_1, USD), expected_accounts, "User's actual payment should be fee based on average fill price, not estimated from budget");
        assert_eq!(api.user_account(UID_1, BASE_ID), taker_size, "Base account should receive takerSize");
        assert_conserved(&api);
    }
}
