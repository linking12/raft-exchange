#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{
        CancelOrderRequest, ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest,
    };

    const QUOTE_ID: i32 = 840;
    const BASE_ID: i32 = 3762;
    const SPOT_SYMBOL: i32 = 20001;
    const SPOT_TAKER_FEE: i64 = 2;

    const PERP_SYMBOL: i32 = 10000;
    const DELIVERY_SYMBOL: i32 = 10010;

    const UID_1: i64 = 1001;
    const UID_2: i64 = 1002;

    fn spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SPOT_SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 1,
            taker_fee: SPOT_TAKER_FEE,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

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

    fn delivery_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: DELIVERY_SYMBOL,
            symbol_type: SymbolType::FuturesContractDelivery,
            base_currency: BASE_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 5,
            taker_fee: 10,
            fee_scale_k: 0,
            maintenance_margin: BTreeMap::from([(1_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(2_000, 5), (100_000, 10)]),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    fn cross_futures(
        order_id: i64,
        uid: i64,
        symbol: i32,
        price: i64,
        size: i64,
        action: OrderAction,
    ) -> PlaceFuturesOrderRequest {
        PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol,
            price,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage: 1,
            margin_mode: MarginMode::Cross,
            reduce_only: false,
        }
    }

    fn settle_funding_fees(
        api: &mut ExchangeApi,
        symbol: i32,
        action: OrderAction,
        rate: i64,
        rate_scale_k: i64,
        txid: i64,
    ) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::SettleFundingfees,
            symbol,
            action: Some(action),
            price: rate,
            size: rate_scale_k,
            order_id: txid,
            ..Default::default()
        })
    }

    fn settle_pnl(api: &mut ExchangeApi, symbol: i32, settle_price: i64, txid: i64) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::SettlePnl,
            symbol,
            price: settle_price,
            order_id: txid,
            ..Default::default()
        })
    }

    fn setup_spot() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_symbol(spot_spec()), CommandResultCode::Success);
        api
    }

    fn fund(api: &mut ExchangeApi, uid: i64, currency: i32, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, currency, amount, txid), CommandResultCode::Success);
    }

    fn spot_bid(order_id: i64, uid: i64, price: i64, reserve: i64, size: i64) -> PlaceOrderRequest {
        PlaceOrderRequest {
            order_id,
            uid,
            symbol: SPOT_SYMBOL,
            price,
            size,
            reserve_bid_price: reserve,
            action: OrderAction::Bid,
            order_type: OrderType::Gtc,
        }
    }
    fn spot_ask(order_id: i64, uid: i64, price: i64, size: i64) -> PlaceOrderRequest {
        PlaceOrderRequest {
            order_id,
            uid,
            symbol: SPOT_SYMBOL,
            price,
            size,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        }
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
            assert_eq!(total, 0, "全局守恒被打破：currency={cur} total={total}");
        }
    }

    #[test]
    fn spot_cancel_releases_lock() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, QUOTE_ID, 1_000, 1);

        let lock = 5 * (100 + SPOT_TAKER_FEE);
        assert_eq!(api.place_order(spot_bid(40001, UID_1, 100, 100, 5)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), lock, "挂单后 exchangeLocked=510");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 1_000, "accounts 不变");

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 40001, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "取消后 exchangeLocked=0");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 1_000, "accounts 仍不变");
        assert_conserved(&api);
    }

    #[test]
    fn multiple_spot_orders_lock_accumulates() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, QUOTE_ID, 2_000, 1);

        let lock1 = 3 * (100 + SPOT_TAKER_FEE);
        let lock2 = 4 * (50 + SPOT_TAKER_FEE);
        let lock3 = 2 * (80 + SPOT_TAKER_FEE);

        assert_eq!(api.place_order(spot_bid(50001, UID_1, 100, 100, 3)), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_bid(50002, UID_1, 50, 50, 4)), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_bid(50003, UID_1, 80, 80, 2)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), lock1 + lock2 + lock3, "三笔累加 678");

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 50001, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), lock2 + lock3, "取消 o1 后 372");

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 50002, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 50003, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "全部取消后 0");
        assert_conserved(&api);
    }

    #[test]
    fn withdrawal_blocked_by_spot_lock() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, QUOTE_ID, 1_000, 1);

        let lock = 5 * (100 + SPOT_TAKER_FEE);
        let free = 1_000 - lock;
        assert_eq!(api.place_order(spot_bid(60001, UID_1, 100, 100, 5)), CommandResultCode::Success);

        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -(free + 1), 60002), CommandResultCode::RiskNsf);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -free, 60003), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), 1_000 - free, "提现后 accounts=510");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), lock, "exchangeLocked 不受提现影响");
        assert_conserved(&api);
    }

    #[test]
    fn spot_ask_lock_base_currency() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, BASE_ID, 10, 1);

        assert_eq!(api.place_order(spot_ask(70001, UID_1, 100, 5)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, BASE_ID), 5, "ASK 冻结 BASE=5");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "QUOTE lock 为 0");
        assert_eq!(api.user_account(UID_1, BASE_ID), 10, "BASE accounts 不变");

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 70001, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, BASE_ID), 0, "取消后 BASE lock=0");
        assert_eq!(api.user_account(UID_1, BASE_ID), 10, "BASE accounts 仍不变");
        assert_conserved(&api);
    }

    #[test]
    fn spot_fill_releases_lock() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, QUOTE_ID, 1_000, 1);
        fund(&mut api, UID_2, BASE_ID, 10, 2);

        let bid_lock = 5 * (100 + SPOT_TAKER_FEE);
        assert_eq!(api.place_order(spot_bid(80001, UID_1, 100, 100, 5)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), bid_lock, "成交前 QUOTE lock=510");

        assert_eq!(api.place_order(spot_ask(80002, UID_2, 100, 5)), CommandResultCode::Success);

        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "成交后 QUOTE lock=0");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 495, "UID_1 QUOTE accounts=1000-505");
        assert_eq!(api.user_account(UID_1, BASE_ID), 5, "UID_1 BASE accounts=5");
        assert_eq!(api.user_locked(UID_2, BASE_ID), 0, "成交后 BASE lock=0");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), 490, "UID_2 QUOTE accounts=500-10");
        assert_eq!(api.user_account(UID_2, BASE_ID), 5, "UID_2 BASE accounts=5");
        assert_eq!(api.fees(QUOTE_ID), 15, "fees=makerFee(5)+takerFee(10)");
        assert_conserved(&api);
    }

    #[test]
    fn spot_lock_and_futures_margin_both_constrain_withdrawal() {
        let mut api = setup_spot();
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 1_000), CommandResultCode::Success);

        fund(&mut api, UID_1, QUOTE_ID, 5_000, 1);
        fund(&mut api, UID_2, QUOTE_ID, 100_000, 2);

        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 90001, uid: UID_1, symbol: PERP_SYMBOL, price: 1_000, size: 10,
                action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );
        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 90002, uid: UID_2, symbol: PERP_SYMBOL, price: 1_000, size: 10,
                action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 4_900, "UID_1 accounts=5000-100(makerFee)");

        let spot_lock = 5 * (100 + SPOT_TAKER_FEE);
        assert_eq!(api.place_order(spot_bid(90003, UID_1, 100, 100, 5)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), spot_lock);

        let max_withdraw = 4_290;
        assert_eq!(
            api.balance_adjustment(UID_1, QUOTE_ID, -(max_withdraw + 1), 90004),
            CommandResultCode::RiskNsf
        );
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -max_withdraw, 90005), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), 4_900 - max_withdraw, "提现后 accounts=610");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), spot_lock, "现货 exchangeLocked 不受提现影响");
        assert_conserved(&api);
    }

    #[test]
    fn partial_fill_releases_partial_lock() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, QUOTE_ID, 1_500, 1);
        fund(&mut api, UID_2, BASE_ID, 10, 2);

        let full_lock = 10 * (100 + SPOT_TAKER_FEE);
        let remain_lock = 6 * (100 + SPOT_TAKER_FEE);

        assert_eq!(api.place_order(spot_bid(100001, UID_1, 100, 100, 10)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), full_lock, "挂单后 lock=1020");

        assert_eq!(api.place_order(spot_ask(100002, UID_2, 100, 4)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), remain_lock, "部分成交后 lock=612");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 1_500 - 404, "UID_1 QUOTE=1096");
        assert_eq!(api.user_account(UID_1, BASE_ID), 4, "UID_1 BASE=4");

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 100001, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "取消余量后 lock=0");
        assert_conserved(&api);
    }

    #[test]
    fn rejected_order_does_not_modify_lock() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, QUOTE_ID, 100, 1);

        assert_eq!(api.place_order(spot_bid(110001, UID_1, 100, 100, 5)), CommandResultCode::RiskNsf);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "被拒后 exchangeLocked 仍为 0");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 100, "accounts 不变");
        assert_conserved(&api);
    }

    #[test]
    fn bid_and_ask_locks_are_currency_independent() {
        let mut api = setup_spot();
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 1_000, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, BASE_ID, 10, 2), CommandResultCode::Success);

        let quote_lock = 3 * (90 + SPOT_TAKER_FEE);
        let base_lock = 2;

        assert_eq!(api.place_order(spot_bid(120001, UID_1, 90, 90, 3)), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_ask(120002, UID_1, 110, 2)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), quote_lock, "QUOTE lock=276");
        assert_eq!(api.user_locked(UID_1, BASE_ID), base_lock, "BASE lock=2");

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 120001, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "取消 BID 后 QUOTE lock=0");
        assert_eq!(api.user_locked(UID_1, BASE_ID), base_lock, "BASE lock 不变");

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 120002, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, BASE_ID), 0, "取消 ASK 后 BASE lock=0");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 1_000, "QUOTE accounts 不变");
        assert_eq!(api.user_account(UID_1, BASE_ID), 10, "BASE accounts 不变");
        assert_conserved(&api);
    }

    #[test]
    fn spot_lock_unchanged_after_funding_fee_settlement() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.add_symbol(spot_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 1_000), CommandResultCode::Success);

        fund(&mut api, UID_1, QUOTE_ID, 20_000, 1);
        fund(&mut api, UID_2, QUOTE_ID, 20_000, 2);

        assert_eq!(
            api.place_futures_order(cross_futures(20001, UID_1, PERP_SYMBOL, 1_000, 10, OrderAction::Bid)),
            CommandResultCode::Success
        );
        assert_eq!(
            api.place_futures_order(cross_futures(20002, UID_2, PERP_SYMBOL, 1_000, 10, OrderAction::Ask)),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 19_900, "UID_1 = 20000 - makerFee(100)");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), 19_800, "UID_2 = 20000 - takerFee(200)");

        let spot_lock = 5 * (1_000 + SPOT_TAKER_FEE);
        assert_eq!(api.place_order(spot_bid(20003, UID_1, 1_000, 1_000, 5)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), spot_lock, "下现货单后 exchangeLocked=5010");

        let expected_fee = 10 * 1_000 / 100;
        assert_eq!(
            settle_funding_fees(&mut api, PERP_SYMBOL, OrderAction::Bid, 1, 100, 20004),
            CommandResultCode::Success
        );

        assert_eq!(api.user_account(UID_1, QUOTE_ID), 19_900, "accounts 不变（funding 落进 position.profit）");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), spot_lock, "exchangeLocked 不受资金费影响");
        assert_eq!(api.user_position(UID_1, PERP_SYMBOL).unwrap().profit, -expected_fee, "多头 profit=-fee");

        assert_eq!(api.user_account(UID_2, QUOTE_ID), 19_800, "UID_2 accounts 不变");
        assert_eq!(api.user_locked(UID_2, QUOTE_ID), 0, "UID_2 无现货挂单");
        assert_eq!(api.user_position(UID_2, PERP_SYMBOL).unwrap().profit, expected_fee, "空头 profit=+fee");
        assert_conserved(&api);
    }

    #[test]
    fn spot_lock_survives_delivery() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(delivery_spec()), CommandResultCode::Success);
        assert_eq!(api.add_symbol(spot_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(DELIVERY_SYMBOL, 1_000), CommandResultCode::Success);

        fund(&mut api, UID_1, QUOTE_ID, 10_000, 1);
        fund(&mut api, UID_2, QUOTE_ID, 10_000, 2);

        assert_eq!(
            api.place_futures_order(cross_futures(30001, UID_1, DELIVERY_SYMBOL, 1_000, 10, OrderAction::Bid)),
            CommandResultCode::Success
        );
        assert_eq!(
            api.place_futures_order(cross_futures(30002, UID_2, DELIVERY_SYMBOL, 1_000, 10, OrderAction::Ask)),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_950, "UID_1 = 10000 - makerFee(50)");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), 9_900, "UID_2 = 10000 - takerFee(100)");

        let spot_lock = 3 * (500 + SPOT_TAKER_FEE);
        assert_eq!(api.place_order(spot_bid(30003, UID_1, 500, 500, 3)), CommandResultCode::Success);
        assert!(api.user_position(UID_1, DELIVERY_SYMBOL).is_some(), "交割仓位在");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), spot_lock, "下现货单后 exchangeLocked=1506");

        assert_eq!(settle_pnl(&mut api, DELIVERY_SYMBOL, 1_500, 30004), CommandResultCode::Success);

        assert!(api.user_position(UID_1, DELIVERY_SYMBOL).is_none(), "交割后 UID_1 仓位清空");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 14_950, "UID_1 = 9950 + pnl(5000)");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), spot_lock, "现货 exchangeLocked 不受交割影响");

        assert!(api.user_position(UID_2, DELIVERY_SYMBOL).is_none(), "交割后 UID_2 仓位清空");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), 4_900, "UID_2 = 9900 - pnl(5000)");
        assert_eq!(api.user_locked(UID_2, QUOTE_ID), 0, "UID_2 无现货挂单");
        assert_conserved(&api);
    }
}
