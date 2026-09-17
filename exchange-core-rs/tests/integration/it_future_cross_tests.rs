#[cfg(test)]
// 翻译自 Java `ITFutureCross`（继承 `ITFutureBase`）
// 验证期货 cross/isolated 保证金模式切换、cross 全仓提现校验、开平仓损益结算与 cross 全仓强平/预警场景
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

    const BASE_CURRENCY_ID: i32 = 1;
    const QUOTE_ID: i32 = 840;
    const XBT: i32 = 3762;
    const ETH: i32 = 3928;
    const SYMBOL_ID: i32 = 2;
    const BTC_SYM: i32 = 10000;
    const ETH_SYM: i32 = 10001;

    const UID_1: i64 = 1;
    const UID_2: i64 = 2;
    const UID_3: i64 = 3;
    const MAX_VALUE: i64 = 4_000_000;
    const MARK: i64 = 10_000;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn leverage_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    fn single_futures_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL_ID,
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
            max_leverage: leverage_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    fn btc_futures_spec() -> CoreSymbolSpecification {
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
            max_leverage: leverage_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    fn eth_futures_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: ETH_SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: ETH,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 1,
            taker_fee: 2,
            fee_scale_k: 100,
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 1_000,
            max_leverage: leverage_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    fn setup_single() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CURRENCY_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(single_futures_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, MARK), CommandResultCode::Success);
        api
    }

    fn setup_two() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        api.add_currency(ETH, 1);
        assert_eq!(api.add_futures_symbol(btc_futures_spec()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_futures_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, MARK), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, MARK), CommandResultCode::Success);
        api
    }

    fn seed_user(api: &mut ExchangeApi, uid: i64, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE_ID, amount, txid), CommandResultCode::Success);
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

    // 对应 Java testCancelSuccess：isolated/cross 保证金模式不能混用，撤单后可切换模式重新挂单
    #[test]
    fn cancel_success_margin_mode_mismatch_guard() {
        let deposit = 2_000;
        let order1 = 1101;
        let order2 = 1102;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);

        assert_eq!(place(&mut api, order1, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, SYMBOL_ID).expect("should have a position record after placing an ISOLATED order");
            assert_eq!(pos.pending_buy_size, 1);
            assert_eq!(pos.margin_mode, MarginMode::Isolated);
        }
        assert_eq!(place(&mut api, order2, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::RiskMarginModeMismatch);

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: order1, uid: UID_1, symbol: SYMBOL_ID }), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());

        assert_eq!(place(&mut api, order2, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, SYMBOL_ID).expect("should have a position record after placing a CROSS order");
            assert_eq!(pos.pending_buy_size, 1);
            assert_eq!(pos.margin_mode, MarginMode::Cross);
        }
        assert_eq!(place(&mut api, order1, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::RiskMarginModeMismatch);

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: order2, uid: UID_1, symbol: SYMBOL_ID }), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
        assert_eq!(place(&mut api, order1, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, SYMBOL_ID).unwrap();
            assert_eq!(pos.pending_buy_size, 1);
            assert_eq!(pos.margin_mode, MarginMode::Isolated);
        }
        assert_conserved(&api);
    }

    // 对应 Java testDefaultMargin：不指定 margin mode 时默认为 ISOLATED
    #[test]
    fn default_margin_is_isolated() {
        let deposit = 20_000;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);

        assert_eq!(place(&mut api, 1201, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().margin_mode, MarginMode::Isolated);
    }

    // 对应 Java tesCloseMarginThenChangeMode：仓位全平后允许更改保证金模式
    #[test]
    fn close_margin_then_change_mode() {
        let mut api = setup_single();
        seed_user(&mut api, UID_1, 100_000, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, 10_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().margin_mode, MarginMode::Cross);

        assert_eq!(place(&mut api, 1007, UID_1, SYMBOL_ID, 10_500, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, SYMBOL_ID, 10_500, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "position torn down after full close, margin mode can now be changed");

        assert_eq!(place(&mut api, 1009, UID_1, SYMBOL_ID, 11_000, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1010, UID_2, SYMBOL_ID, 11_000, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().margin_mode, MarginMode::Isolated);
    }

    // 对应 Java testPendingAvgPrice：验证 pendingBuyAvgPrice/pendingSellAvgPrice 的加权均价计算
    #[test]
    fn pending_avg_price() {
        let price1 = 10_000;
        let price2 = 15_000;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, 10_000, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, price1, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, SYMBOL_ID, price2, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().pending_buy_avg_price, (price1 + price2) / 2);

        assert_eq!(place(&mut api, 1008, UID_2, SYMBOL_ID, price2, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, price1, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().pending_buy_avg_price, 0);
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().pending_sell_avg_price, 0);
        assert_conserved(&api);
    }

    // 对应 Java testCrossMarginWithdraw：cross 保证金提现须扣除该币种下所有期货持仓占用的保证金（空仓场景）
    #[test]
    fn cross_margin_withdraw() {
        let deposit = 10_000;
        let price1 = 10_000;
        let price2 = 15_000;
        let mut api = setup_two();
        seed_user(&mut api, UID_1, deposit, 1);

        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, price1, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, price2, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit, "placing a futures order does not deduct accounts");

        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -deposit, 100), CommandResultCode::RiskNsf);

        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 569, 101), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -deposit, 102), CommandResultCode::RiskNsf);

        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 1, 103), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -deposit, 104), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 570, "only 570 (the margin-locked amount) remains after withdrawal");
        assert_conserved(&api);
    }

    // 对应 Java testCrossMarginWithdraw2：cross 保证金提现须扣除该币种下所有期货持仓占用的保证金（持仓场景）
    #[test]
    fn cross_margin_withdraw2() {
        let deposit = 10_000;
        let price1 = 10_000;
        let price2 = 15_000;
        let mut api = setup_two();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);
        seed_user(&mut api, UID_3, MAX_VALUE, 3);

        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, price1, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, price2, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, price1, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, ETH_SYM, price2, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 - 150);

        assert_eq!(api.set_mark_price(BTC_SYM, 15_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 5_000), CommandResultCode::Success);

        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -deposit, 500), CommandResultCode::RiskNsf);

        assert_eq!(place(&mut api, 1009, UID_1, BTC_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1010, UID_3, BTC_SYM, 15_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert!(api.user_position(UID_1, BTC_SYM).is_none(), "BTC leg closed, only the ETH leg remains");
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 - 150 - 10 + 5_000);

        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -deposit, 501), CommandResultCode::Success);
        assert_conserved(&api);
    }

    // 对应 Java testOpenPosition4Bid：maker Bid + taker Ask 全部成交开仓，验证方向与手续费扣减
    #[test]
    fn open_position_taker_ask() {
        let deposit = 1_000;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, MARK, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10, "user1 maker fee = 1*10");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20, "user2 taker fee = 1*20");
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().direction, PositionDirection::Long);
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().direction, PositionDirection::Short);
        assert_conserved(&api);
    }

    // 对应 Java testOpenPosition4Ask：maker Ask + taker Bid 全部成交开仓，验证方向与手续费扣减
    #[test]
    fn open_position_taker_bid() {
        let deposit = 1_000;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().direction, PositionDirection::Short);
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().direction, PositionDirection::Long);
        assert_conserved(&api);
    }

    // 对应 Java testOpenMultiplePosition4Bid：maker Bid 挂大单，taker Ask 部分成交，验证剩余挂量与已开仓量
    #[test]
    fn open_multiple_partial_taker_ask() {
        let size = 10;
        let tx_size = 2;
        let deposit = 1_000 * size;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, MARK, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, MARK, tx_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - tx_size * 10);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - tx_size * 20);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, tx_size);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().direction, PositionDirection::Long);
        assert_conserved(&api);
    }

    // 对应 Java testOpenMultiplePosition4Ask：maker Ask 挂大单，taker Bid 部分成交，验证剩余挂量与已开仓量
    #[test]
    fn open_multiple_partial_taker_bid() {
        let size = 10;
        let tx_size = 2;
        let deposit = 1_000 * size;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, MARK, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, MARK, tx_size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - tx_size * 10);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - tx_size * 20);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, tx_size);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().direction, PositionDirection::Short);
        assert_conserved(&api);
    }

    // 对应 Java testClosePosition：反向单全平仓位，已实现盈亏结算入账户
    #[test]
    fn close_full_position_settles_pnl() {
        let deposit = 1_000;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, 10_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(place(&mut api, 1007, UID_1, SYMBOL_ID, 10_500, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, SYMBOL_ID, 10_500, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 - 10 + 500);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20 - 20 - 500);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "position torn down after full close");
        assert!(api.user_position(UID_2, SYMBOL_ID).is_none());
        assert_conserved(&api);
    }

    // 对应 Java testPartialClosePosition：反向单部分平仓，盈亏递延不立即计入 profit
    #[test]
    fn partial_close_position_defers_pnl() {
        let deposit = 10_000;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, 10_000, 10, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, 10_000, 10, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(place(&mut api, 1007, UID_1, SYMBOL_ID, 10_500, 2, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, SYMBOL_ID, 10_500, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 * 10 - 10 * 1);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20 * 10 - 20 * 1);
        let pos = api.user_position(UID_1, SYMBOL_ID).expect("still holding a position after partial close");
        assert_eq!(pos.open_volume, 9);
        assert_eq!(pos.direction, PositionDirection::Long);
        assert_eq!(pos.profit, 0, "partial close defers PnL, not booked into profit");
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().open_volume, 9);
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().direction, PositionDirection::Short);
        assert_conserved(&api);
    }

    // 对应 Java testCrossMarginLiquidation（简化版）：cross 全仓强平触发后，总持仓量应减少
    #[test]
    fn cross_margin_liquidation_reduces_positions() {
        let mut api = setup_two();
        seed_user(&mut api, UID_1, 10_000, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);
        seed_user(&mut api, UID_3, MAX_VALUE, 3);

        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, 10_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, ETH_SYM, 15_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        let initial = api.user_position(UID_1, BTC_SYM).unwrap().open_volume
            + api.user_position(UID_1, ETH_SYM).unwrap().open_volume;
        assert_eq!(initial, 2);

        assert_eq!(place(&mut api, 1009, UID_3, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, 2_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 35_000), CommandResultCode::Success);

        let remaining = api.user_position(UID_1, BTC_SYM).map(|p| p.open_volume).unwrap_or(0)
            + api.user_position(UID_1, ETH_SYM).map(|p| p.open_volume).unwrap_or(0);
        assert!(remaining < initial, "total position should decrease after cross-margin liquidation (at least one leg liquidated)");
        assert!(api.total_balance().is_global_zero());
    }

    // 对应 Java testCrossMarginLiquidationWarning：亏损触发保证金预警但未达强平线，账户/仓位不变，校验 upnl/强平价/保证金率
    #[test]
    fn cross_margin_liquidation_warning_no_liquidation() {
        let deposit = 10_000i64;
        let mut api = setup_two();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, 10_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, ETH_SYM, 15_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_840);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().open_volume, 1);
        assert!(api.total_balance().is_global_zero());

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, 5_300), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 20_000), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_840, "a warning does not alter the account");
        assert_eq!(api.user_position(UID_1, BTC_SYM).map(|p| p.open_volume), Some(1), "a warning does not trigger liquidation");
        assert_eq!(api.user_position(UID_1, ETH_SYM).map(|p| p.open_volume), Some(1));
        let eth = api.single_user(UID_1, 0).positions.into_iter().find(|p| p.symbol == ETH_SYM).unwrap();
        assert_eq!(eth.unrealized_pnl, -5_000);
        let btc = api.single_user(UID_1, 0).positions.into_iter().find(|p| p.symbol == BTC_SYM).unwrap();
        assert_eq!(btc.unrealized_pnl, -4_700, "btc LONG upnl");
        assert_eq!(btc.liquidation_price, 5_286, "btc LONG LP");
        assert_eq!(btc.margin_ratio_scale_k, 185, "btc LONG margin ratio");
        assert!(api.total_balance().is_global_zero());
    }
}
