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

    const BASE_CURRENCY_ID: i32 = 1;
    const SYMBOL_ID: i32 = 2;
    const QUOTE_ID: i32 = 840;
    const DEPOSIT: i64 = 1_000_000;
    const PRICE: i64 = 10_000;

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    const MAKER_FEE_PER_CONTRACT: i64 = 10;
    const TAKER_FEE_PER_CONTRACT: i64 = 20;

    fn futures_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL_ID,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_CURRENCY_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: MAKER_FEE_PER_CONTRACT,
            taker_fee: TAKER_FEE_PER_CONTRACT,
            fee_scale_k: 0,
            maintenance_margin: BTreeMap::from([(1_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(2_000, 5), (100_000, 10)]),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    fn fresh_api() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CURRENCY_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(futures_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, PRICE), CommandResultCode::Success);
        api
    }

    fn seed_user(api: &mut ExchangeApi, uid: i64, money: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE_ID, money, txid), CommandResultCode::Success);
    }

    fn bid(api: &mut ExchangeApi, order_id: i64, uid: i64, size: i64) -> CommandResultCode {
        place(api, order_id, uid, size, OrderAction::Bid)
    }
    fn ask(api: &mut ExchangeApi, order_id: i64, uid: i64, size: i64) -> CommandResultCode {
        place(api, order_id, uid, size, OrderAction::Ask)
    }
    fn place(api: &mut ExchangeApi, order_id: i64, uid: i64, size: i64, action: OrderAction) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: SYMBOL_ID,
            price: PRICE,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage: 1,
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

    #[test]
    fn pure_open_charges_maker_and_taker_fee() {
        let mut api = fresh_api();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        let size = 5i64;
        assert_eq!(bid(&mut api, 101, UID_1, size), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 102, UID_2, size), CommandResultCode::Success);

        let expected_maker_fee = MAKER_FEE_PER_CONTRACT * size;
        let expected_taker_fee = TAKER_FEE_PER_CONTRACT * size;

        assert_eq!(api.user_account(UID_1, QUOTE_ID), DEPOSIT - expected_maker_fee, "maker fee deducted from opener after open");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), DEPOSIT - expected_taker_fee, "taker fee deducted from opener after open");
        assert_eq!(api.fees(QUOTE_ID), expected_maker_fee + expected_taker_fee, "fees bucket = makerFee + takerFee");
        assert_conserved(&api);
    }

    #[test]
    fn pure_close_charges_maker_and_taker_fee_at_same_rate_as_open() {
        let mut api = fresh_api();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        let size = 5i64;
        assert_eq!(bid(&mut api, 101, UID_1, size), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 102, UID_2, size), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 103, UID_1, size), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 104, UID_2, size), CommandResultCode::Success);

        let expected_maker_fee_total = 2 * MAKER_FEE_PER_CONTRACT * size;
        let expected_taker_fee_total = 2 * TAKER_FEE_PER_CONTRACT * size;

        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "UID_1 position should be fully closed");
        assert!(api.user_position(UID_2, SYMBOL_ID).is_none(), "UID_2 position should be fully closed");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), DEPOSIT - expected_maker_fee_total, "UID_1: open + close maker fee (zero spread)");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), DEPOSIT - expected_taker_fee_total, "UID_2: open + close taker fee (zero spread)");
        assert_eq!(api.fees(QUOTE_ID), expected_maker_fee_total + expected_taker_fee_total, "fees bucket = sum of 4 fee charges");
        assert_conserved(&api);
    }

    #[test]
    fn close_fee_swaps_side_on_role_switch() {
        let mut api = fresh_api();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        let size = 5i64;
        assert_eq!(bid(&mut api, 101, UID_1, size), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 102, UID_2, size), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 103, UID_2, size), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 104, UID_1, size), CommandResultCode::Success);

        let uid1_fee = MAKER_FEE_PER_CONTRACT * size + TAKER_FEE_PER_CONTRACT * size;
        let uid2_fee = TAKER_FEE_PER_CONTRACT * size + MAKER_FEE_PER_CONTRACT * size;

        assert_eq!(api.user_account(UID_1, QUOTE_ID), DEPOSIT - uid1_fee, "UID_1: open maker + close taker");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), DEPOSIT - uid2_fee, "UID_2: open taker + close maker");
        assert_eq!(api.fees(QUOTE_ID), uid1_fee + uid2_fee, "fees bucket = sum of 4 fee charges");
        assert_conserved(&api);
    }

    #[test]
    fn reverse_fill_charges_both_close_and_open_fee() {
        let mut api = fresh_api();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        assert_eq!(bid(&mut api, 101, UID_1, 5), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 102, UID_2, 5), CommandResultCode::Success);

        assert_eq!(bid(&mut api, 103, UID_2, 10), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 104, UID_1, 5), CommandResultCode::Success);

        let uid1_fee = MAKER_FEE_PER_CONTRACT * 5 + TAKER_FEE_PER_CONTRACT * 5;
        let uid2_fee = TAKER_FEE_PER_CONTRACT * 5 + MAKER_FEE_PER_CONTRACT * 5;

        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "UID_1 should be fully closed");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), DEPOSIT - uid1_fee, "UID_1 fee: open maker + close taker");
        assert_eq!(
            api.user_position(UID_2, SYMBOL_ID).expect("UID_2 should still have a position record (pending is non-zero)").pending_buy_size,
            5,
            "UID_2: after closing 5 SHORT, 5 contracts remain as an unmatched BID order"
        );
        assert_eq!(api.fees(QUOTE_ID), uid1_fee + uid2_fee, "fees bucket = UID_1 + UID_2 accumulated");
        assert_conserved(&api);
    }

    #[test]
    fn fees_bucket_aggregates_across_multiple_fills() {
        let mut api = fresh_api();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        let n = 4i64;
        let size_per_fill = 2i64;
        for i in 0..n {
            let oid = 1000 + i * 2;
            assert_eq!(bid(&mut api, oid, UID_1, size_per_fill), CommandResultCode::Success);
            assert_eq!(ask(&mut api, oid + 1, UID_2, size_per_fill), CommandResultCode::Success);
            assert_eq!(ask(&mut api, oid + 100, UID_1, size_per_fill), CommandResultCode::Success);
            assert_eq!(bid(&mut api, oid + 101, UID_2, size_per_fill), CommandResultCode::Success);
        }

        let expected_fees = n * size_per_fill * (2 * MAKER_FEE_PER_CONTRACT + 2 * TAKER_FEE_PER_CONTRACT);
        assert_eq!(api.fees(QUOTE_ID), expected_fees, "fees bucket = n x (open + close) x (maker + taker)");
        assert_conserved(&api);
    }
}
