#[cfg(test)]
mod tests {
    // 翻译自 Java `ITFutureBasic`（继承 ITFutureBase，测试类为 exchange.core2.tests.integration.ITFutureBasic）
    // 验证期货下单/开平仓/reduce-only/杠杆与NSF拒绝/强平等场景下的账户余额、仓位状态与全局资金守恒
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::position_direction::PositionDirection;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{
        CancelOrderRequest, ExchangeApi, PlaceFuturesOrderRequest,
    };

    const BASE_CURRENCY_ID: i32 = 1;
    const QUOTE_ID: i32 = 840;
    const SYMBOL_ID: i32 = 2;
    const SYMBOL_MARGIN: i32 = 5991;
    const MAX_VALUE: i64 = 4_000_000;

    const UID_1: i64 = 1;
    const UID_2: i64 = 2;
    const UID_3: i64 = 3;

    const MARK: i64 = 10_000;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn leverage_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    fn futures_spec() -> CoreSymbolSpecification {
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

    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CURRENCY_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(futures_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, MARK), CommandResultCode::Success);
        api
    }

    fn add_user_money(api: &mut ExchangeApi, uid: i64, amount: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE_ID, amount, 1), CommandResultCode::Success);
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
        leverage: i32,
        reduce_only: bool,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: SYMBOL_ID,
            price,
            size,
            action,
            order_type,
            leverage,
            margin_mode: MarginMode::Isolated,
            reduce_only,
        })
    }

    // 校验全局资金守恒：账户余额 + 调整 + 手续费 + 持仓未实现盈亏/保证金之和应为 0
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

    // 对应 Java testBalanceIncrease：余额增加应正确入账，且不产生仓位
    #[test]
    fn balance_increase_credits_account() {
        let delta = 100;
        let mut api = ExchangeApi::new();
        api.add_currency(SYMBOL_MARGIN, 1);
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, SYMBOL_MARGIN, delta, 1), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, SYMBOL_MARGIN), delta);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
    }

    // 对应 Java testBalanceDecrease：先充值再扣减，验证余额正确扣除
    #[test]
    fn balance_decrease_debits_account() {
        let deposit = 100;
        let withdraw = -40;
        let mut api = ExchangeApi::new();
        api.add_currency(SYMBOL_MARGIN, 1);
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, SYMBOL_MARGIN, deposit, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, SYMBOL_MARGIN, withdraw, 2), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, SYMBOL_MARGIN), deposit + withdraw);
    }

    // 对应 Java testCancelSuccess：挂单后撤单，余额应原封不动地返还，无仓位残留
    #[test]
    fn cancel_returns_balance_intact() {
        let deposit = 1_000;
        let order_id = 1005;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);

        assert_eq!(place(&mut api, order_id, UID_1, MARK, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0);

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id, uid: UID_1, symbol: SYMBOL_ID }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
        assert_conserved(&api);
    }

    // 对应 Java testOpenPosition4Bid：maker BID + taker ASK 完全成交开仓，验证手续费扣减与仓位方向
    #[test]
    fn open_position_maker_bid_taker_ask() {
        let deposit = 1_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, MAX_VALUE);

        assert_eq!(place(&mut api, 1005, UID_1, MARK, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, MARK, 1, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10, "user1 maker fee = 1*10");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20, "user2 taker fee = 1*20");
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().direction, PositionDirection::Long);
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().direction, PositionDirection::Short);
        assert_conserved(&api);
    }

    // 对应 Java testOpenPosition4Ask：maker ASK + taker BID 完全成交开仓，验证手续费扣减与仓位方向
    #[test]
    fn open_position_maker_ask_taker_bid() {
        let deposit = 1_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, MAX_VALUE);

        assert_eq!(place(&mut api, 1005, UID_1, MARK, 1, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, MARK, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().direction, PositionDirection::Short);
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().direction, PositionDirection::Long);
        assert_conserved(&api);
    }

    // 对应 Java testOpenMultiplePosition4Bid：maker BID 挂大单，taker ASK 部分成交，验证部分开仓数量
    #[test]
    fn open_multiple_partial_maker_bid() {
        let size = 10;
        let tx_size = 2;
        let deposit = 1_000 * size;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, MAX_VALUE);

        assert_eq!(place(&mut api, 1005, UID_1, MARK, size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, MARK, tx_size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - tx_size * 10);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - tx_size * 20);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, tx_size);
        assert_conserved(&api);
    }

    // 对应 Java testOpenMultiplePosition4Ask：maker ASK 挂大单，taker BID 部分成交，验证部分开仓数量
    #[test]
    fn open_multiple_partial_maker_ask() {
        let size = 10;
        let tx_size = 2;
        let deposit = 1_000 * size;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, MAX_VALUE);

        assert_eq!(place(&mut api, 1005, UID_1, MARK, size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, MARK, tx_size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - tx_size * 10);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - tx_size * 20);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, tx_size);
        assert_conserved(&api);
    }

    // 对应 Java testClosePosition：开仓后完全平仓，盈亏应结算入账，仓位被拆除
    #[test]
    fn close_full_position_settles_pnl() {
        let deposit = 1_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, MAX_VALUE);

        assert_eq!(place(&mut api, 1005, UID_1, 10_000, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, 10_000, 1, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(place(&mut api, 1007, UID_1, 10_500, 1, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, 10_500, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 - 10 + 500);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20 - 20 - 500);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "position removed after full close");
        assert!(api.user_position(UID_2, SYMBOL_ID).is_none());
        assert_conserved(&api);
    }

    // 对应 Java testPartialClosePosition：部分平仓时盈亏递延，不立即计入 profit
    #[test]
    fn partial_close_position_defers_pnl() {
        let deposit = 10_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, MAX_VALUE);

        assert_eq!(place(&mut api, 1005, UID_1, 10_000, 10, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, 10_000, 10, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(place(&mut api, 1007, UID_1, 10_500, 2, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, 10_500, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 * 10 - 10 * 1);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20 * 10 - 20 * 1);
        let pos = api.user_position(UID_1, SYMBOL_ID).expect("position should still be held after partial close");
        assert_eq!(pos.open_volume, 9);
        assert_eq!(pos.direction, PositionDirection::Long);
        assert_eq!(pos.profit, 0, "partial close defers PnL, not recorded into profit");
        assert_conserved(&api);
    }

    // 对应 Java testReduceOnlyWithoutPosition：无仓位时提交 reduce-only 订单应被忽略（不产生仓位）
    #[test]
    fn reduce_only_without_position_is_noop() {
        let deposit = 10_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);

        assert_eq!(place(&mut api, 2001, UID_1, MARK, 1, OrderAction::Ask, OrderType::Gtc, 1, true), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
    }

    // 对应 Java testReduceOnlyPartialCloseLongPosition：多头仓位先 reduce-only 部分平仓，再平剩余部分
    #[test]
    fn reduce_only_partial_then_full_close_long() {
        let deposit = 100_000;
        let position_size = 10;
        let close_size = 6;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, deposit);
        add_user_money(&mut api, UID_3, deposit);

        assert_eq!(place(&mut api, 1001, UID_1, 10_000, position_size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1002, UID_2, 10_000, position_size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 3001, UID_3, 10_100, 2 * position_size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(place(&mut api, 2002, UID_1, 10_100, close_size, OrderAction::Ask, OrderType::Ioc, 1, true), CommandResultCode::Success);
        let pos = api.user_position(UID_1, SYMBOL_ID).expect("position should still be held after partial close");
        assert_eq!(pos.open_volume, (position_size - close_size));
        assert_eq!(pos.direction, PositionDirection::Long);

        assert_eq!(place(&mut api, 2003, UID_1, 10_100, close_size, OrderAction::Ask, OrderType::Ioc, 1, true), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
    }

    // 对应 Java testReduceOnlyFullCloseShortPosition：空头仓位先 reduce-only 部分平仓，再平剩余部分
    #[test]
    fn reduce_only_partial_then_full_close_short() {
        let deposit = 100_000;
        let position_size = 10;
        let close_size = 6;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, deposit);
        add_user_money(&mut api, UID_3, deposit);

        assert_eq!(place(&mut api, 1001, UID_1, 10_000, position_size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1002, UID_2, 10_000, position_size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2001, UID_3, 9_900, 2 * position_size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(place(&mut api, 2002, UID_1, 9_900, close_size, OrderAction::Bid, OrderType::Ioc, 1, true), CommandResultCode::Success);
        let pos = api.user_position(UID_1, SYMBOL_ID).expect("position should still be held after partial close");
        assert_eq!(pos.open_volume, (position_size - close_size));
        assert_eq!(pos.direction, PositionDirection::Short);

        assert_eq!(place(&mut api, 2003, UID_1, 9_900, close_size, OrderAction::Bid, OrderType::Ioc, 1, true), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
    }

    // 对应 Java testReduceOnlyExceedPositionSize：reduce-only 数量超过持仓量时应被截断，不会反向超卖
    #[test]
    fn reduce_only_exceeding_size_is_truncated() {
        let deposit = 100_000;
        let position_size = 5;
        let large_close = 10;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, deposit);
        add_user_money(&mut api, UID_3, deposit);

        assert_eq!(place(&mut api, 1001, UID_1, 10_000, position_size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1002, UID_2, 10_000, position_size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2001, UID_3, 10_100, large_close, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(place(&mut api, 2002, UID_1, 10_100, large_close, OrderAction::Ask, OrderType::Ioc, 1, true), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "full close must not oversell");
        assert_eq!(api.user_position(UID_3, SYMBOL_ID).unwrap().open_volume, position_size);
    }

    // 对应 Java testReduceOnlyWrongDirection：反方向的 reduce-only 订单同向被裁到 0，不应改变仓位
    #[test]
    fn reduce_only_wrong_direction_is_noop() {
        let deposit = 100_000;
        let position_size = 5;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, deposit);

        assert_eq!(place(&mut api, 1001, UID_1, 10_000, position_size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1002, UID_2, 10_000, position_size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, SYMBOL_ID).unwrap();
            assert_eq!(pos.open_volume, position_size);
            assert_eq!(pos.pending_buy_size, 0);
            assert_eq!(pos.direction, PositionDirection::Long);
        }

        assert_eq!(place(&mut api, 2001, UID_1, 10_100, 3, OrderAction::Bid, OrderType::Gtc, 0, true), CommandResultCode::Success);
        let pos = api.user_position(UID_1, SYMBOL_ID).unwrap();
        assert_eq!(pos.open_volume, position_size);
        assert_eq!(pos.pending_buy_size, 0);
        assert_eq!(pos.direction, PositionDirection::Long);
    }

    // 对应 Java testReduceOnlySameDirectionDoesNotExtendPosition：同向 reduce-only 应被裁到 0（no-op），不得扩仓
    #[test]
    fn reduce_only_same_direction_does_not_extend() {
        let deposit = 100_000;
        let position_size = 5;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, 1_000_000);

        assert_eq!(place(&mut api, 10001, UID_1, 10_000, position_size, OrderAction::Bid, OrderType::Gtc, 5, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 10002, UID_2, 10_000, position_size, OrderAction::Ask, OrderType::Gtc, 5, false), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, position_size);

        assert_eq!(place(&mut api, 10003, UID_1, 10_000, 3, OrderAction::Bid, OrderType::Gtc, 5, true), CommandResultCode::Success);
        assert_eq!(place(&mut api, 10004, UID_2, 10_000, 3, OrderAction::Ask, OrderType::Ioc, 5, false), CommandResultCode::Success);

        assert_eq!(
            api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume,
            position_size,
            "ONEWAY same-direction reduce-only must not extend position"
        );
    }

    // 对应 Java testLeverageRejectDoesNotLeavePosition：杠杆超限拒单不应污染 positions，后续合法单仍可成功
    #[test]
    fn leverage_reject_does_not_leave_position() {
        let deposit = 100_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, deposit);

        assert_eq!(place(&mut api, 5001, UID_1, 10_000, 1, OrderAction::Bid, OrderType::Gtc, 100, false), CommandResultCode::RiskInvalidLeverage);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());

        assert_eq!(place(&mut api, 5002, UID_1, 10_000, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 5003, UID_2, 10_000, 1, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, 1);
    }

    // 对应 Java testNSFRejectDoesNotLeavePosition：无已存在仓位时 NSF 拒单不应污染 positions
    #[test]
    fn nsf_reject_does_not_leave_position() {
        let deposit = 100;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, 1_000_000);

        assert_eq!(place(&mut api, 6001, UID_1, 10_000, 10, OrderAction::Bid, OrderType::Gtc, 5, false), CommandResultCode::RiskNsf);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
    }

    // 对应 Java testNSFRejectKeepsExistingPosition：用户已有仓位时，加仓单 NSF 失败不能误删老仓位
    #[test]
    fn nsf_reject_keeps_existing_position() {
        let deposit = 50_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, 1_000_000);

        assert_eq!(place(&mut api, 7001, UID_1, 10_000, 1, OrderAction::Bid, OrderType::Gtc, 5, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 7002, UID_2, 10_000, 1, OrderAction::Ask, OrderType::Gtc, 5, false), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, 1);

        assert_eq!(place(&mut api, 7003, UID_1, 10_000, 3000, OrderAction::Bid, OrderType::Gtc, 5, false), CommandResultCode::RiskNsf);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, 1);
    }

    // 对应 Java testReduceOnlyZeroFollowedBySuccessfulOpen：reduce-only-zero 成功后紧接开仓仍应成功（对象池归还的 record 已正确重置）
    #[test]
    fn reduce_only_zero_followed_by_successful_open() {
        let deposit = 100_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, deposit);

        assert_eq!(place(&mut api, 8001, UID_1, 10_000, 1, OrderAction::Ask, OrderType::Gtc, 1, true), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());

        assert_eq!(place(&mut api, 8002, UID_1, 10_000, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 8003, UID_2, 10_000, 1, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, 1);
    }

    // 对应 Java testGlobalBalanceConservedAfterFailedPlaceOrders：连续多种拒单不应影响账户余额与全局资金守恒
    #[test]
    fn global_balance_conserved_after_failed_place_orders() {
        let deposit = 10_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);

        let mut order_id = 9000;
        for _ in 0..5 {
            order_id += 1;
            assert_eq!(place(&mut api, order_id, UID_1, 10_000, 1, OrderAction::Ask, OrderType::Gtc, 1, true), CommandResultCode::Success);
            order_id += 1;
            assert_eq!(place(&mut api, order_id, UID_1, 10_000, 1, OrderAction::Bid, OrderType::Gtc, 100, false), CommandResultCode::RiskInvalidLeverage);
            order_id += 1;
            assert_eq!(place(&mut api, order_id, UID_1, 10_000, 1000, OrderAction::Bid, OrderType::Gtc, 5, false), CommandResultCode::RiskNsf);
        }

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0);
        assert_conserved(&api);
    }

    // 对应 Java testForceClosePosition（在此基础上进一步驱动实际 FORCE 强平执行）：
    // 行情大幅不利变动使借款人 LONG 仓位水下，FORCE 应将其全平，清算费计入保险基金
    #[test]
    fn force_close_position_liquidates_underwater_long() {
        const FC_BASE: i32 = 1;
        const FC_QUOTE: i32 = 2;
        const FC_FUT: i32 = 400;
        const BORROWER: i64 = 10;
        const M1: i64 = 20;
        const M2: i64 = 30;

        let fc_spec = || {
            let mut mm = BTreeMap::new();
            mm.insert(i64::MAX, 500i64);
            CoreSymbolSpecification {
                symbol_id: FC_FUT,
                symbol_type: SymbolType::FuturesContractPerpetual,
                base_currency: FC_BASE,
                quote_currency: FC_QUOTE,
                base_scale_k: 1,
                quote_scale_k: 1,
                taker_fee: 0,
                maker_fee: 0,
                fee_scale_k: 10_000,
                maintenance_margin: mm,
                maintenance_margin_scale_k: 10_000,
                liquidation_fee: 200,
                ..Default::default()
            }
        };

        let mut api = ExchangeApi::new();
        api.add_currency(FC_BASE, 1);
        api.add_currency(FC_QUOTE, 1);
        assert_eq!(api.add_futures_symbol(fc_spec()), CommandResultCode::Success);
        api.enable_liquidation();

        for uid in [BORROWER, M1, M2] {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, FC_QUOTE, 10_000_000, 1), CommandResultCode::Success);
        }

        let order = |order_id: i64, uid: i64, price: i64, size: i64, action: OrderAction| PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: FC_FUT,
            price,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage: 10,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        };

        assert_eq!(api.set_mark_price(FC_FUT, 100), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(order(1, M1, 100, 10, OrderAction::Ask)), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(order(2, BORROWER, 100, 10, OrderAction::Bid)), CommandResultCode::Success);
        {
            let pos = api.user_position(BORROWER, FC_FUT).expect("borrower's LONG position should be open");
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.open_volume, 10);
        }

        assert_eq!(api.place_futures_order(order(3, M2, 92, 10, OrderAction::Bid)), CommandResultCode::Success);

        assert_eq!(api.set_mark_price(FC_FUT, 94), CommandResultCode::Success);

        assert!(
            api.user_position(BORROWER, FC_FUT).is_none(),
            "borrower's underwater LONG fully closed by FORCE (cascade fully driven single-node)",
        );
        let if_available: i64 = api.insurance_fund().futures.values().map(|e| e.available).sum();
        assert!(if_available > 0, "liquidation fee must be credited to insurance fund available");
    }

    // 对应 Java testAdjustment：多笔限价 ASK 挂单后一笔 IOC BID 部分扫单，验证扫单后全局资金守恒
    #[test]
    fn adjustment_partial_sweep_conserves_globally() {
        const USD: i32 = 840;
        const JPY: i32 = 392;
        const A_MARK: i64 = 10_000;
        let (u1, u2, u3, u4) = (1_440_001i64, 1_440_002i64, 1_440_003i64, 1_440_004i64);

        let a_spec = || CoreSymbolSpecification {
            symbol_id: SYMBOL_MARGIN,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: USD,
            quote_currency: JPY,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 2,
            taker_fee: 3,
            fee_scale_k: 0,
            maintenance_margin: BTreeMap::from([(1_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 0,
            max_leverage: BTreeMap::from([(2_000, 5), (100_000, 10)]),
            init_margin: 1,
            init_margin_scale_k: 21,
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(USD, 1);
        api.add_currency(JPY, 1);
        assert_eq!(api.add_futures_symbol(a_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_MARGIN, A_MARK), CommandResultCode::Success);
        for uid in [u1, u2, u3, u4] {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, JPY, 10_000_000, 1), CommandResultCode::Success);
        }

        let ord = |order_id: i64, uid: i64, price: i64, size: i64, action: OrderAction, ot: OrderType| PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: SYMBOL_MARGIN,
            price,
            size,
            action,
            order_type: ot,
            leverage: 0,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        };

        assert_eq!(api.place_futures_order(ord(101, u1, 160_000, 7, OrderAction::Ask, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(ord(202, u2, 159_900, 10, OrderAction::Ask, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(ord(303, u3, 160_000, 3, OrderAction::Ask, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(ord(304, u3, 160_500, 20, OrderAction::Ask, OrderType::Gtc)), CommandResultCode::Success);

        assert_eq!(api.place_futures_order(ord(405, u4, 160_500, 20, OrderAction::Bid, OrderType::Ioc)), CommandResultCode::Success);

        assert!(api.total_balance().is_global_zero(), "global conservation holds after partial sweep");
    }
}
