//! 对应 Java 测试类 `ITPerpetualContractIntegration.java` 的移植：验证 SettleFundingFees /
//! SettlePnl 两类结算命令的 symbol 类型校验，以及交割合约（delivery）和永续合约（perpetual）
//! 在资金费结算（funding fee）、PnL 结算（delivery settlement）、强平（liquidation）全生命周期下
//! 仓位与账户余额的正确性。
#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::fund_event::FundEventType;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::position_direction::PositionDirection;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest};

    const XBT: i32 = 3762;
    const USD: i32 = 840;
    const PERP_SYM: i32 = 10000;
    const DELIVERY_SYM: i32 = 10100;

    const UID_1: i64 = 1;
    const UID_2: i64 = 2;
    const UID_3: i64 = 3;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn leverage_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    fn perp_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: PERP_SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: XBT,
            quote_currency: USD,
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

    fn delivery_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: DELIVERY_SYM,
            symbol_type: SymbolType::FuturesContractDelivery,
            base_currency: XBT,
            quote_currency: USD,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            maintenance_margin: mm_table(),
            max_leverage: leverage_table(),
            ..Default::default()
        }
    }

    fn assert_conserved(api: &ExchangeApi) {
        let tcb = api.total_balance();
        assert!(tcb.is_global_zero(), "global conservation broken: {:?}", tcb.global_balances_sum());
    }

    fn seed_user(api: &mut ExchangeApi, uid: i64, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, USD, amount, txid), CommandResultCode::Success);
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

    fn settle_funding_fees(api: &mut ExchangeApi, symbol: i32, action: OrderAction, rate: i64, rate_scale_k: i64, txid: i64) -> CommandResultCode {
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

    // 对应 Java testInvalidSymbol() 场景：SettleFundingFees 命令发到非永续（delivery）symbol
    // 应报 InvalidSymbol；发到永续 symbol 但尚无 mark price 时报 RiskMarkpriceNotAvailable；
    // 设置 mark price 后同一命令才能成功。
    #[test]
    fn invalid_symbol_settle_funding_fees_guards() {
        let mut api = ExchangeApi::new();
        api.add_currency(11, 1);
        api.add_currency(12, 1);
        let perp = CoreSymbolSpecification {
            symbol_id: 10000, symbol_type: SymbolType::FuturesContractPerpetual, base_currency: 11, quote_currency: 12,
            base_scale_k: 1, quote_scale_k: 1, maker_fee: 1, taker_fee: 2, fee_scale_k: 100,
            init_margin: 1, init_margin_scale_k: 100,
            maintenance_margin: mm_table(), maintenance_margin_scale_k: 100, max_leverage: leverage_table(),
            ..Default::default()
        };
        let delivery = CoreSymbolSpecification { symbol_id: 10001, symbol_type: SymbolType::FuturesContractDelivery, ..perp.clone() };
        assert_eq!(api.add_futures_symbol(perp), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(delivery), CommandResultCode::Success);

        assert_eq!(settle_funding_fees(&mut api, 10001, OrderAction::Bid, 33, 100, 1004), CommandResultCode::InvalidSymbol);
        assert_eq!(settle_funding_fees(&mut api, 10000, OrderAction::Bid, 33, 100, 1003), CommandResultCode::RiskMarkpriceNotAvailable);
        assert_eq!(api.set_mark_price(10000, 10_000), CommandResultCode::Success);
        assert_eq!(settle_funding_fees(&mut api, 10000, OrderAction::Bid, 33, 100, 1003), CommandResultCode::Success);
    }

    // 对应 Java testInvalidSymbol2() 场景：SettlePnl 命令发到永续 symbol 应报 InvalidSymbol
    // （PnL 结算只适用于交割合约），发到 delivery symbol 才能成功。
    #[test]
    fn invalid_symbol_settle_pnl_guards() {
        let mut api = ExchangeApi::new();
        api.add_currency(11, 1);
        api.add_currency(12, 1);
        let perp = CoreSymbolSpecification {
            symbol_id: 10000, symbol_type: SymbolType::FuturesContractPerpetual, base_currency: 11, quote_currency: 12,
            base_scale_k: 1, quote_scale_k: 1, maker_fee: 1, taker_fee: 2, fee_scale_k: 100,
            init_margin: 1, init_margin_scale_k: 100,
            maintenance_margin: mm_table(), maintenance_margin_scale_k: 100, max_leverage: leverage_table(),
            ..Default::default()
        };
        let delivery = CoreSymbolSpecification { symbol_id: 10001, symbol_type: SymbolType::FuturesContractDelivery, ..perp.clone() };
        assert_eq!(api.add_futures_symbol(perp), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(delivery), CommandResultCode::Success);

        assert_eq!(settle_pnl(&mut api, 10000, 10_000, 1), CommandResultCode::InvalidSymbol);
        assert_eq!(settle_pnl(&mut api, 10001, 10_000, 2), CommandResultCode::Success);
    }

    // 对应 Java testDeliveryScenario0() 场景（"没开出来单子交割后不需要结算 -- 交割"）：
    // 挂单未成交（无实际仓位敞口）时，SettlePnl 是 no-op，不影响 pending 挂单和账户余额。
    #[test]
    fn delivery_scenario0_no_fill_settle_is_noop() {
        let deposit = 20_000i64;
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(delivery_spec()), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        assert_eq!(api.user_account(UID_1, USD), deposit);
        assert!(api.user_position(UID_1, DELIVERY_SYM).is_none());
        assert_conserved(&api);

        assert_eq!(api.set_mark_price(DELIVERY_SYM, 1_000), CommandResultCode::Success);

        assert_eq!(place(&mut api, 1, UID_1, DELIVERY_SYM, 1_000, 10, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, DELIVERY_SYM).expect("resting bid should create a position record");
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.pending_sell_size, 0);
            assert_eq!(pos.pending_buy_size, 10);
            assert_eq!(pos.margin_mode, MarginMode::Cross);
            assert_eq!(pos.pending_buy_avg_price, 1_000);
        }
        assert_eq!(api.user_account(UID_1, USD), deposit, "resting order must not deduct accounts");

        assert_eq!(settle_pnl(&mut api, DELIVERY_SYM, 200, 1), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, DELIVERY_SYM).expect("settlement should not touch a still-pending, unfilled order");
            assert_eq!(pos.pending_buy_size, 10);
            assert_eq!(pos.pending_buy_avg_price, 1_000);
        }
        assert_eq!(api.user_account(UID_1, USD), deposit);
        assert_conserved(&api);
    }

    // 对应 Java testDeliveryScenario1() 场景（"开出来单子后需要做交割结算 -- 交割"）：
    // maker/taker 各持有一份多/空仓位，SettlePnl 结算后按 (settlePrice - openPrice) 分配盈亏，
    // 两边仓位归零、fee 与 PnL 都正确记入账户余额。
    #[test]
    fn delivery_scenario1_full_settlement_pnl() {
        let deposit = 20_000i64;
        let maker_fee = 100i64;
        let taker_fee = 200i64;
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(delivery_spec()), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, deposit, 2);
        assert_conserved(&api);

        assert_eq!(api.set_mark_price(DELIVERY_SYM, 1_000), CommandResultCode::Success);

        assert_eq!(place(&mut api, 1, UID_1, DELIVERY_SYM, 1_000, 10, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, UID_2, DELIVERY_SYM, 1_000, 10, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee);
        assert_eq!(api.user_account(UID_2, USD), deposit - taker_fee);
        assert_eq!(api.user_position(UID_1, DELIVERY_SYM).unwrap().direction, PositionDirection::Long);
        assert_eq!(api.user_position(UID_2, DELIVERY_SYM).unwrap().direction, PositionDirection::Short);
        assert_conserved(&api);

        assert_eq!(settle_pnl(&mut api, DELIVERY_SYM, 1_500, 1), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee + 5_000);
        assert!(api.user_position(UID_1, DELIVERY_SYM).is_none());
        assert_eq!(api.user_account(UID_2, USD), deposit - taker_fee - 5_000);
        assert!(api.user_position(UID_2, DELIVERY_SYM).is_none());
        assert_conserved(&api);
    }

    // 对应 Java testPerpetualScenario0() 场景（"下期货单但是没有成交, 所有没有开仓成功"）：
    // 挂单未成交时，SettleFundingFees 是 no-op，不影响 pending 挂单和账户余额。
    #[test]
    fn perpetual_scenario0_no_fill_funding_is_noop() {
        let deposit = 20_000i64;
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        assert_conserved(&api);

        assert_eq!(api.set_mark_price(PERP_SYM, 10_000), CommandResultCode::Success);

        assert_eq!(place(&mut api, 1, UID_1, PERP_SYM, 1_000, 10, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, PERP_SYM).expect("resting bid should create a position record");
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.pending_buy_size, 10);
            assert_eq!(pos.pending_buy_avg_price, 1_000);
        }

        assert_eq!(settle_funding_fees(&mut api, PERP_SYM, OrderAction::Bid, -100, 100, 1345), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, PERP_SYM).unwrap();
            assert_eq!(pos.pending_buy_size, 10);
            assert_eq!(pos.pending_buy_avg_price, 1_000);
        }
        assert_eq!(api.user_account(UID_1, USD), deposit);
        assert_conserved(&api);
    }

    // 对应 Java testPerpetualScenario1() 场景（"开出来单子后需要做结算 -- 永续, 正向"）：
    // 资金费率 > 0 时多头向空头付费，验证开仓、结算后双方 profit 相应变化（附 golden 数值核对
    // funding fee 事件的 free 余额），随后分两笔平仓（1 手 + 9 手）并核对 initMargin/账户余额。
    #[test]
    fn perpetual_scenario1_positive_funding_full_lifecycle() {
        let deposit = 20_000i64;
        let maker_fee = 100i64;
        let taker_fee = 200i64;
        let size = 10i64;
        let updated_price = 1_500i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, deposit, 2);
        seed_user(&mut api, UID_3, deposit, 3);
        assert_conserved(&api);

        assert_eq!(api.set_mark_price(PERP_SYM, updated_price), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1, UID_1, PERP_SYM, 1_000, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, UID_2, PERP_SYM, 1_000, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        {
            let p1 = api.user_position(UID_1, PERP_SYM).unwrap();
            assert_eq!(p1.open_init_margin_sum, 150);
            assert_eq!(p1.profit, 0);
            assert_eq!(p1.direction, PositionDirection::Long);
            assert_eq!(p1.open_price_sum, 10_000);
        }
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee);
        assert_eq!(api.user_account(UID_2, USD), deposit - taker_fee);

        assert_eq!(settle_funding_fees(&mut api, PERP_SYM, OrderAction::Bid, 1, 100, 1345), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, PERP_SYM).unwrap().profit, -150);
        assert_eq!(api.user_position(UID_2, PERP_SYM).unwrap().profit, 150);
        let fe: Vec<_> = api.last_fund_events().iter().filter(|e| e.event_type == FundEventType::FundingfeeSettlement).collect();
        assert_eq!(fe.len(), 2, "expected two funding-fee settlement events");
        let e1 = fe.iter().find(|e| e.uid == UID_1).expect("UID_1 funding event");
        assert_eq!(e1.free, 19_750, "Java golden UID_1 free");
        assert_eq!(e1.profit, -150);
        let e2 = fe.iter().find(|e| e.uid == UID_2).expect("UID_2 funding event");
        assert_eq!(e2.free, 19_650, "Java golden UID_2 free");
        assert_eq!(e2.profit, 150);
        assert_conserved(&api);

        assert_eq!(place(&mut api, 3, UID_1, PERP_SYM, updated_price, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 4, UID_3, PERP_SYM, updated_price, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        {
            let p1 = api.user_position(UID_1, PERP_SYM).unwrap();
            assert_eq!(p1.open_volume, size - 1);
            assert_eq!(p1.profit, -150);
            assert_eq!(p1.open_price_sum, 1_000 * size - updated_price);
            assert_eq!(p1.open_init_margin_sum, 135);
        }
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee - 10);

        assert_eq!(place(&mut api, 5, UID_1, PERP_SYM, updated_price, 9, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 6, UID_3, PERP_SYM, updated_price, 9, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert!(api.user_position(UID_1, PERP_SYM).is_none());
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee + 5_000 - 150 - maker_fee);
        assert_conserved(&api);
    }

    // 对应 Java testPerpetualScenario2() 场景（"开出来单子后需要做结算 -- 永续, 反向"）：
    // 资金费率 < 0 时空头向多头付费（与 scenario1 方向相反），同样验证结算后 profit 变化
    // 及分批平仓（1 手 + 9 手）后的 initMargin/账户余额。
    #[test]
    fn perpetual_scenario2_negative_funding_full_lifecycle() {
        let deposit = 20_000i64;
        let maker_fee = 100i64;
        let updated_price = 1_500i64;
        let size = 10i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, deposit, 2);
        seed_user(&mut api, UID_3, deposit, 3);
        assert_conserved(&api);

        assert_eq!(api.set_mark_price(PERP_SYM, updated_price), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1, UID_1, PERP_SYM, 1_000, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, UID_2, PERP_SYM, 1_000, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(settle_funding_fees(&mut api, PERP_SYM, OrderAction::Ask, 1, 100, 1345), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, PERP_SYM).unwrap().profit, 150);
        assert_eq!(api.user_position(UID_2, PERP_SYM).unwrap().profit, -150);
        assert_conserved(&api);

        assert_eq!(place(&mut api, 3, UID_1, PERP_SYM, updated_price, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 4, UID_3, PERP_SYM, updated_price, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        {
            let p1 = api.user_position(UID_1, PERP_SYM).unwrap();
            assert_eq!(p1.open_volume, size - 1);
            assert_eq!(p1.profit, 150);
            assert_eq!(p1.open_price_sum, 1_000 * size - updated_price);
            assert_eq!(p1.open_init_margin_sum, 135);
        }
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee - 10);

        assert_eq!(place(&mut api, 5, UID_1, PERP_SYM, updated_price, 9, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 6, UID_3, PERP_SYM, updated_price, 9, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert!(api.user_position(UID_1, PERP_SYM).is_none());
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee + 5_000 + 150 - maker_fee);
        assert_conserved(&api);
    }

    // 对应 Java testPerpetualScenario3() 场景（"测试某订单多次发起SettleFundingFees是否正常"）：
    // 开仓后结算资金费把多头 profit 拖到资不抵维持保证金水平，再开启强平引擎并重新推送 mark
    // price 触发强平流程，验证仓位最终被清空。
    #[test]
    fn perpetual_scenario3_funding_then_liquidation() {
        let deposit = 5_000i64;
        let size = 10i64;
        let maker_fee = 10 * size;
        let updated_price = 1_100i64;
        let price = 1_000i64;

        let spec = CoreSymbolSpecification {
            symbol_id: 10000,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: XBT,
            quote_currency: USD,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            liquidation_fee: 50,
            fee_scale_k: 0,
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 10,
            max_leverage: leverage_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        };
        let sym = spec.symbol_id;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(spec), CommandResultCode::Success);
        for uid in [UID_1, UID_2, UID_3] {
            seed_user(&mut api, uid, deposit, uid);
        }
        assert_conserved(&api);

        assert_eq!(api.set_mark_price(sym, updated_price), CommandResultCode::Success);

        assert_eq!(place(&mut api, 101, UID_1, sym, price, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 102, UID_2, sym, price, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee);
        assert_eq!(api.user_position(UID_1, sym).unwrap().profit, 0);

        assert_eq!(settle_funding_fees(&mut api, sym, OrderAction::Bid, 10, 100, 1345), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, sym).unwrap().profit, -1_100);
        assert_conserved(&api);

        assert_eq!(place(&mut api, 103, UID_3, sym, updated_price, 20, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(sym, updated_price), CommandResultCode::Success);

        assert!(api.user_position(UID_1, sym).is_none(), "UID_1 should have been liquidated and closed out");
        assert_conserved(&api);
    }
}
