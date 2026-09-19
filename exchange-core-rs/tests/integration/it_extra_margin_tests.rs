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
    use exchange_core_rs::core::exchange_api::{
        CancelOrderRequest, ExchangeApi, MarginAdjustmentRequest, PlaceFuturesOrderRequest,
    };

    const BASE_CURRENCY_ID: i32 = 1;
    const QUOTE_ID: i32 = 840;
    const XBT: i32 = 3762;
    const ETH_CUR: i32 = 3928;
    const SYMBOL_ID: i32 = 2;
    const BTC_SYM: i32 = 10000;
    const ETH_SYM: i32 = 10001;

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;
    const UID_3: i64 = 1_440_003;
    const MAX_VALUE: i64 = 4_000_000;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn lev_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    fn init_future_symbol(symbol_id: i32) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_CURRENCY_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 1_000,
            max_leverage: lev_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    fn btc_symbol() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: BTC_SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: XBT,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 1_000,
            max_leverage: lev_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    fn eth_symbol() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: ETH_SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: ETH_CUR,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 1,
            taker_fee: 2,
            fee_scale_k: 100,
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 1_000,
            max_leverage: lev_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    fn seed_user(api: &mut ExchangeApi, uid: i64, money: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE_ID, money, txid), CommandResultCode::Success);
    }

    #[allow(clippy::too_many_arguments)]
    fn place(
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
    fn extra_margin_cross_credits_account() {
        let deposit1 = 2_000i64;
        let deposit2 = 1_000i64;

        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CURRENCY_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(init_future_symbol(SYMBOL_ID)), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit1, 1);

        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1,
                symbol: QUOTE_ID,
                action: OrderAction::Bid,
                amount: deposit2,
                margin_mode: MarginMode::Cross,
                order_id: 10_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit1 + deposit2);
        assert_conserved(&api);
    }

    #[test]
    fn extra_margin_isolated_requires_matching_position() {
        let deposit1 = 2_000i64;
        let deposit2 = 1_000i64;
        let size = 1i64;

        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CURRENCY_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(init_future_symbol(SYMBOL_ID)), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit1, 1);

        let adjust = MarginAdjustmentRequest {
            uid: UID_1,
            symbol: SYMBOL_ID,
            action: OrderAction::Bid,
            amount: deposit2,
            margin_mode: MarginMode::Isolated,
            order_id: 10_001,
        };
        assert_eq!(api.margin_adjustment(adjust), CommandResultCode::RiskMarginPositionNotExists);

        assert_eq!(place(&mut api, 10_101, UID_1, SYMBOL_ID, 10_000, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).map(|p| p.margin_mode), Some(MarginMode::Cross));
        assert_conserved(&api);

        assert_eq!(api.margin_adjustment(adjust), CommandResultCode::RiskMarginModeMismatch);

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 10_101, uid: UID_1, symbol: SYMBOL_ID }),
            CommandResultCode::Success
        );
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
        assert_conserved(&api);

        assert_eq!(place(&mut api, 10_102, UID_1, SYMBOL_ID, 10_000, size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).map(|p| p.margin_mode), Some(MarginMode::Isolated));

        assert_eq!(api.margin_adjustment(adjust), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().extra_margin, deposit2);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit1 - deposit2, "after topping up, account should be reduced by deposit2");
        assert_conserved(&api);
    }

    #[test]
    fn isolated_close_position_refunds_extra_margin() {
        let deposit = 1_000i64;
        let deposit2 = 500i64;
        let fee = 10i64;
        let price1 = 10_000i64;
        let price2 = 10_500i64;

        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CURRENCY_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(init_future_symbol(SYMBOL_ID)), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, price1, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, price1, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().extra_margin, 0);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee);
        assert_conserved(&api);

        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1,
                symbol: SYMBOL_ID,
                action: OrderAction::Bid,
                amount: deposit2,
                margin_mode: MarginMode::Isolated,
                order_id: 10_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().extra_margin, deposit2);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee - deposit2);
        assert_conserved(&api);

        assert_eq!(place(&mut api, 1007, UID_1, SYMBOL_ID, price2, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, SYMBOL_ID, price2, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);

        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "position should be closed out and extra_margin refunded");
        let expected = deposit + deposit2 + price2 - price1 - 2 * fee - deposit2;
        assert_eq!(api.user_account(UID_1, QUOTE_ID), expected);
        assert_eq!(expected, 1_480);
        assert_conserved(&api);
    }

    #[test]
    fn multiple_extra_margin_are_independent_per_position() {
        let deposit = 10_000i64;
        let price1 = 10_000i64;
        let price2 = 15_000i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        api.add_currency(ETH_CUR, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_symbol()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, 10_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);
        seed_user(&mut api, UID_3, MAX_VALUE, 3);

        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, price1, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, price2, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, price1, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, ETH_SYM, price2, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);

        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().direction, PositionDirection::Long);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, 0);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().direction, PositionDirection::Short);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().extra_margin, 0);
        assert_conserved(&api);

        let add = |api: &mut ExchangeApi, symbol: i32, amount: i64, order_id: i64| {
            assert_eq!(
                api.margin_adjustment(MarginAdjustmentRequest {
                    uid: UID_1,
                    symbol,
                    action: OrderAction::Bid,
                    amount,
                    margin_mode: MarginMode::Isolated,
                    order_id,
                }),
                CommandResultCode::Success
            );
        };

        add(&mut api, BTC_SYM, 200, 20_001);
        add(&mut api, ETH_SYM, 300, 20_002);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, 200);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().extra_margin, 300);
        assert_conserved(&api);

        add(&mut api, BTC_SYM, 300, 20_003);
        add(&mut api, ETH_SYM, 200, 20_004);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, 500);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().extra_margin, 500);
        assert_conserved(&api);
    }

    fn pos_view(api: &ExchangeApi, uid: i64, symbol: i32) -> exchange_core_rs::core::reports::PositionView {
        api.single_user(uid, 0)
            .positions
            .into_iter()
            .find(|p| p.symbol == symbol)
            .expect("position report entry should exist")
    }

    #[test]
    fn inactive_user_cannot_adjust_margin() {
        let deposit = 10_000i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        api.add_currency(ETH_CUR, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_symbol()), CommandResultCode::Success);

        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.suspend_user(UID_1), CommandResultCode::Success);
        assert!(api.ups().get(UID_1).is_none(), "user should be removed from the registry after suspension");

        let adjust = MarginAdjustmentRequest {
            uid: UID_1,
            symbol: QUOTE_ID,
            action: OrderAction::Bid,
            amount: deposit,
            margin_mode: MarginMode::Cross,
            order_id: 1_345,
        };
        assert_eq!(api.margin_adjustment(adjust), CommandResultCode::AuthInvalidUser);

        assert_eq!(api.resume_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.margin_adjustment(adjust), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn isolated_margin_liquidation_warning() {
        let deposit = 10_000i64;
        let fee = 10i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, 10_000, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee);
        assert!(api.total_balance().is_global_zero());

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, 9_950), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee, "warning must not change the account");
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1, "warning must not close the position");
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, 0);
        {
            let p = pos_view(&api, UID_1, BTC_SYM);
            assert_eq!(p.unrealized_pnl, -50);
            assert_eq!(p.liquidation_price, 9_949);
            assert_eq!(p.margin_ratio_scale_k, 980);
        }
        assert!(api.total_balance().is_global_zero());

        let extra = 10i64;
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1, symbol: BTC_SYM, action: OrderAction::Bid, amount: extra,
                margin_mode: MarginMode::Isolated, order_id: 10_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, extra);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee - extra);
        {
            let p = pos_view(&api, UID_1, BTC_SYM);
            assert_eq!(p.liquidation_price, 9_939);
            assert_eq!(p.margin_ratio_scale_k, 816);
        }

        assert_eq!(api.set_mark_price(BTC_SYM, 9_950), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee - extra);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, extra);
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn isolated_margin_liquidation_warning_insufficient_topup() {
        let deposit = 10_000i64;
        let fee = 10i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, 10_000, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, 9_950), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1);
        {
            let p = pos_view(&api, UID_1, BTC_SYM);
            assert_eq!(p.unrealized_pnl, -50);
            assert_eq!(p.liquidation_price, 9_949);
            assert_eq!(p.margin_ratio_scale_k, 980);
        }

        let extra = 7i64;
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1, symbol: BTC_SYM, action: OrderAction::Bid, amount: extra,
                margin_mode: MarginMode::Isolated, order_id: 10_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, extra);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee - extra);

        assert_eq!(api.set_mark_price(BTC_SYM, 9_950), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1, "topping up by 7 is still insufficient, must not liquidate");
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, extra);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee - extra);
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn cross_margin_liquidation_warning() {
        let deposit = 10_000i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        api.add_currency(ETH_CUR, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_symbol()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, 10_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);
        seed_user(&mut api, UID_3, MAX_VALUE, 3);

        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, 10_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, ETH_SYM, 15_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_840);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(place(&mut api, 1009, UID_3, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1010, UID_3, ETH_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, 9_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 23_660), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, BTC_SYM).map(|p| p.open_volume), Some(1), "warning must not liquidate");
        assert_eq!(api.user_position(UID_1, ETH_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_840);
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).unrealized_pnl, -8_660, "ETH SHORT@15000 mark23660");
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).liquidation_price, 23_677, "cross liquidation price");
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).margin_ratio_scale_k, 655, "cross margin ratio");
        assert!(api.total_balance().is_global_zero());

        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1, symbol: QUOTE_ID, action: OrderAction::Bid, amount: 20,
                margin_mode: MarginMode::Cross, order_id: 20_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_860);
        assert_eq!(api.set_mark_price(ETH_SYM, 23_660), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, BTC_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(api.user_position(UID_1, ETH_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_860);
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn cross_margin_liquidation_warning_insufficient_topup() {
        let deposit = 10_000i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        api.add_currency(ETH_CUR, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_symbol()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, 10_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);
        seed_user(&mut api, UID_3, MAX_VALUE, 3);

        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, 10_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, ETH_SYM, 15_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_840);

        assert_eq!(place(&mut api, 1009, UID_3, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1010, UID_3, ETH_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, 9_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 23_660), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, BTC_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(api.user_position(UID_1, ETH_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).unrealized_pnl, -8_660);
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).liquidation_price, 23_677, "cross liquidation price, first warning");
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).margin_ratio_scale_k, 655, "cross margin ratio, first warning");
        assert!(api.total_balance().is_global_zero());

        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1, symbol: QUOTE_ID, action: OrderAction::Bid, amount: 12,
                margin_mode: MarginMode::Cross, order_id: 20_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_852);
        assert_eq!(api.set_mark_price(ETH_SYM, 23_660), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, BTC_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(api.user_position(UID_1, ETH_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_852);
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).liquidation_price, 23_689, "cross liquidation price, second warning");
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).margin_ratio_scale_k, 614, "cross margin ratio, second warning");
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn isolated_margin_liquidation_both_legs() {
        let deposit = 10_000i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        api.add_currency(ETH_CUR, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_symbol()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, 10_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);
        seed_user(&mut api, UID_3, MAX_VALUE, 3);

        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, 10_000, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, ETH_SYM, 15_000, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);

        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1, symbol: BTC_SYM, action: OrderAction::Bid, amount: 900,
                margin_mode: MarginMode::Isolated, order_id: 20_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1, symbol: ETH_SYM, action: OrderAction::Bid, amount: 2_900,
                margin_mode: MarginMode::Isolated, order_id: 20_002,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, 900);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().extra_margin, 2_900);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 6_040);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(place(&mut api, 1011, UID_3, BTC_SYM, 9_000, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1012, UID_3, ETH_SYM, 18_000, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, 9_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 18_000), CommandResultCode::Success);
        assert!(api.user_position(UID_1, BTC_SYM).is_none(), "BTC isolated leg should be fully liquidated");
        assert!(api.user_position(UID_1, ETH_SYM).is_none(), "ETH isolated leg should be fully liquidated");
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn cross_margin_withdraw_respects_locked_margin() {
        let deposit = 10_000i64;
        let price1 = 10_000i64;
        let price2 = 15_000i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        api.add_currency(ETH_CUR, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_symbol()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, 10_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);

        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, price1, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, price2, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);

        let _ = api.margin_adjustment(MarginAdjustmentRequest {
            uid: UID_1,
            symbol: BTC_SYM,
            action: OrderAction::Bid,
            amount: deposit,
            margin_mode: MarginMode::Isolated,
            order_id: 10_001,
        });
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, 0);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().extra_margin, 0);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -deposit, 100), CommandResultCode::RiskNsf);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 569, 101), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -deposit, 102), CommandResultCode::RiskNsf);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 1, 103), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -deposit, 104), CommandResultCode::Success);
        assert!(api.total_balance().is_global_zero());
    }
}
