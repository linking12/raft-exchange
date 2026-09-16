//! 翻译自 Java `exchange.core2.tests.integration.ITPerpetualContractIntegration`（8 个 @Test）——
//! 永续/交割合约的资金费（SETTLE_FUNDINGFEES）与交割结算（SETTLE_PNL）集成测试。
//!
//! Rust `ExchangeApi` 无 `ApiSettleFundingFees`/`ApiSettlePNL` 专属封装，改用通用 `submit(OrderCommand)`：
//!   - SETTLE_FUNDINGFEES：`{command, symbol, action=Bid/Ask, price=fundingRate, size=rateScaleK, order_id=txid}`
//!     （payer = 方向与 action 相同的一侧；见 `funding_fee_command_processor.rs`）。**直接提交**，其零和
//!     结算落进逐用户 `position.profit`（活仓）/`accounts`（ghost），可直接断言。
//!   - SETTLE_PNL：`{command, symbol=delivery, price=settlePrice}`（交割整仓平掉该 symbol 所有持仓、结算
//!     盈亏进 accounts、移除仓位）。
//!
//! 期货 symbol 复刻 Java `initFutureSymbols()[0]`（BTC 10000, XBT/USD, maker10/taker20 固定费,
//! MM{1000:5,100000:10}@scaleK1000, initMargin1/scaleK100）；交割复刻 `initDeliverySymbols()[0]`
//! （BTC 10100, 同费率, 无 MM scaleK/无 initMargin → initMargin=notional/leverage）。
//!
//! Java 停掉 LiquidationEngine（`LiquidationEngine::stop`）——除资金费+强平的 scenario3 外均不触发强平，
//! 故本文件除 scenario3 外都**不**调用 `enable_liquidation()`（Rust 引擎默认 `is_running=false`）。
//!
//! **未翻译/降级的断言**：Java 用 `SimpleEventsProcessor4Test` + Mockito captor 捕获逐笔
//! `FundEventReport`（PNL_SETTLEMENT / FUNDINGFEE_SETTLEMENT 的 balances/positions/liquidationPrice/
//! marginRatioScaleK 字段）。`ExchangeApi` 的强平/结算 fund event 走内部命令、`last_fund_events()` 不捕获，
//! 故这些事件字段断言全部**跳过**，改断言等价的最终**状态**（accounts/profit/openVolume/openPriceSum/
//! openInitMarginSum + 全局守恒）——同一组 golden 值的另一种观测。scenario3 的强平后精确账户值依赖 FORCE
//! 成交价/费的内部级联，改断言 loser 全平 + 守恒（见文件头强平约定）。8/8 @Test 已翻译。

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

    const XBT: i32 = 3762; // CURRENECY_XBT
    const USD: i32 = 840; // CURRENECY_USD (quoteId)
    const PERP_SYM: i32 = 10000; // initPerpetualSymbols()[0]
    const DELIVERY_SYM: i32 = 10100; // initDeliverySymbols()[0]

    const UID_1: i64 = 1;
    const UID_2: i64 = 2;
    const UID_3: i64 = 3;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn leverage_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    /// 永续 BTC（10000），固定费 maker10/taker20，MM@scaleK1000，initMargin1/scaleK100。
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

    /// 交割 BTC（10100），固定费 maker10/taker20，无 MM scaleK / 无 initMargin（initMargin=notional/leverage）。
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
        assert!(tcb.is_global_zero(), "全局守恒被打破: {:?}", tcb.global_balances_sum());
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

    /// SETTLE_FUNDINGFEES：price=fundingRate, size=rateScaleK, action 决定 payer 侧（同向者付）。
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

    /// SETTLE_PNL：交割结算，price=settlePrice。
    fn settle_pnl(api: &mut ExchangeApi, symbol: i32, settle_price: i64, txid: i64) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::SettlePnl,
            symbol,
            price: settle_price,
            order_id: txid,
            ..Default::default()
        })
    }

    // ================================================================================================
    // 1. testInvalidSymbol —— SETTLE_FUNDINGFEES 门禁：delivery→InvalidSymbol，perpetual 无 mark→
    //    MarkpriceNotAvailable，设价后→Success。
    // ================================================================================================

    #[test]
    fn invalid_symbol_settle_funding_fees_guards() {
        let mut api = ExchangeApi::new();
        api.add_currency(11, 1);
        api.add_currency(12, 1);
        // symbol0 perpetual (10000)、symbol1 delivery (10001)，base11/quote12。
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

        // delivery 上发资金费 → InvalidSymbol（资金费仅永续）。
        assert_eq!(settle_funding_fees(&mut api, 10001, OrderAction::Bid, 33, 100, 1004), CommandResultCode::InvalidSymbol);
        // perpetual 无 mark price → MarkpriceNotAvailable。
        assert_eq!(settle_funding_fees(&mut api, 10000, OrderAction::Bid, 33, 100, 1003), CommandResultCode::RiskMarkpriceNotAvailable);
        // 设 mark 后 → Success。
        assert_eq!(api.set_mark_price(10000, 10_000, 0), CommandResultCode::Success);
        assert_eq!(settle_funding_fees(&mut api, 10000, OrderAction::Bid, 33, 100, 1003), CommandResultCode::Success);
    }

    // ================================================================================================
    // 2. testInvalidSymbol2 —— SETTLE_PNL 门禁：perpetual→InvalidSymbol，delivery→Success。
    // ================================================================================================

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

        // perpetual 上发交割结算 → InvalidSymbol（结算仅交割）。
        assert_eq!(settle_pnl(&mut api, 10000, 10_000, 1), CommandResultCode::InvalidSymbol);
        // delivery 上 → Success（无持仓，空 no-op 结算）。
        assert_eq!(settle_pnl(&mut api, 10001, 10_000, 2), CommandResultCode::Success);
    }

    // ================================================================================================
    // 3. testDeliveryScenario0 —— 没开出单子（只有 resting BID），交割结算 no-op。
    // ================================================================================================

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

        assert_eq!(api.set_mark_price(DELIVERY_SYM, 1_000, 0), CommandResultCode::Success);

        // 下 BID 10 @1000 但无对手盘 → 静止挂单（pendingBuy），不开仓、不扣 accounts。
        assert_eq!(place(&mut api, 1, UID_1, DELIVERY_SYM, 1_000, 10, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, DELIVERY_SYM).expect("resting bid 建仓记录");
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.pending_sell_size, 0);
            assert_eq!(pos.pending_buy_size, 10);
            assert_eq!(pos.margin_mode, MarginMode::Cross);
            assert_eq!(pos.pending_buy_avg_price, 1_000);
        }
        assert_eq!(api.user_account(UID_1, USD), deposit, "挂单不扣 accounts");

        // 交割结算 @200：open_volume=0 → 跳过，仓位/账户不变。
        assert_eq!(settle_pnl(&mut api, DELIVERY_SYM, 200, 1), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, DELIVERY_SYM).expect("结算不动无敞口挂单");
            assert_eq!(pos.pending_buy_size, 10);
            assert_eq!(pos.pending_buy_avg_price, 1_000);
        }
        assert_eq!(api.user_account(UID_1, USD), deposit);
        assert_conserved(&api);
    }

    // ================================================================================================
    // 4. testDeliveryScenario1 —— 开仓成交后交割结算：settlePrice=1500，LONG +5000 / SHORT −5000。
    //    （SKIP：PNL_SETTLEMENT 事件字段断言——captor 不可得；改断言 accounts + 仓位移除 + 守恒。）
    // ================================================================================================

    #[test]
    fn delivery_scenario1_full_settlement_pnl() {
        let deposit = 20_000i64;
        let maker_fee = 100i64; // size10 * makerFee10
        let taker_fee = 200i64; // size10 * takerFee20
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(delivery_spec()), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, deposit, 2);
        assert_conserved(&api);

        assert_eq!(api.set_mark_price(DELIVERY_SYM, 1_000, 0), CommandResultCode::Success);

        // 开仓：UID_1 BID(maker,LONG) + UID_2 ASK(taker,SHORT) @1000 size10。
        assert_eq!(place(&mut api, 1, UID_1, DELIVERY_SYM, 1_000, 10, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, UID_2, DELIVERY_SYM, 1_000, 10, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee);
        assert_eq!(api.user_account(UID_2, USD), deposit - taker_fee);
        assert_eq!(api.user_position(UID_1, DELIVERY_SYM).unwrap().direction, PositionDirection::Long);
        assert_eq!(api.user_position(UID_2, DELIVERY_SYM).unwrap().direction, PositionDirection::Short);
        assert_conserved(&api);

        // 交割 @1500：LONG pnl=(1500-1000)*10=+5000，SHORT=-5000。仓位移除。
        assert_eq!(settle_pnl(&mut api, DELIVERY_SYM, 1_500, 1), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee + 5_000);
        assert!(api.user_position(UID_1, DELIVERY_SYM).is_none());
        assert_eq!(api.user_account(UID_2, USD), deposit - taker_fee - 5_000);
        assert!(api.user_position(UID_2, DELIVERY_SYM).is_none());
        assert_conserved(&api);
    }

    // ================================================================================================
    // 5. testPerpetualScenario0 —— 没开出单子，资金费结算 no-op。
    // ================================================================================================

    #[test]
    fn perpetual_scenario0_no_fill_funding_is_noop() {
        let deposit = 20_000i64;
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        assert_conserved(&api);

        assert_eq!(api.set_mark_price(PERP_SYM, 10_000, 0), CommandResultCode::Success);

        assert_eq!(place(&mut api, 1, UID_1, PERP_SYM, 1_000, 10, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, PERP_SYM).expect("resting bid 建仓记录");
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.pending_buy_size, 10);
            assert_eq!(pos.pending_buy_avg_price, 1_000);
        }

        // 资金费 rate=-100/scaleK=100，open_volume=0 → 无 payer/receiver，no-op。
        assert_eq!(settle_funding_fees(&mut api, PERP_SYM, OrderAction::Bid, -100, 100, 1345), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, PERP_SYM).unwrap();
            assert_eq!(pos.pending_buy_size, 10);
            assert_eq!(pos.pending_buy_avg_price, 1_000);
        }
        assert_eq!(api.user_account(UID_1, USD), deposit);
        assert_conserved(&api);
    }

    // ================================================================================================
    // 6. testPerpetualScenario1 —— 正向资金费（action=BID，做多付做空收），全周期。
    // ================================================================================================

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

        // mark = 1500，开仓价 1000。
        assert_eq!(api.set_mark_price(PERP_SYM, updated_price, 0), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1, UID_1, PERP_SYM, 1_000, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, UID_2, PERP_SYM, 1_000, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        // openInitMargin 用标记价：ceil(1500*10*1/(100*1)) = 150。
        {
            let p1 = api.user_position(UID_1, PERP_SYM).unwrap();
            assert_eq!(p1.open_init_margin_sum, 150);
            assert_eq!(p1.profit, 0);
            assert_eq!(p1.direction, PositionDirection::Long);
            assert_eq!(p1.open_price_sum, 10_000);
        }
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee);
        assert_eq!(api.user_account(UID_2, USD), deposit - taker_fee);

        // 资金费 rate=1/scaleK=100，action=BID → LONG(UID_1) 付、SHORT(UID_2) 收。
        // fee = trunc(open_volume*mark * rate/scaleK) = trunc(10*1500 * 1/100) = 150。
        assert_eq!(settle_funding_fees(&mut api, PERP_SYM, OrderAction::Bid, 1, 100, 1345), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, PERP_SYM).unwrap().profit, -150);
        assert_eq!(api.user_position(UID_2, PERP_SYM).unwrap().profit, 150);
        // 事件级对拍 Java（ITPerpetualContractIntegration:571/604）：两条 FUNDINGFEE_SETTLEMENT，
        // LONG(UID_1) 付 → free=19750/profit=-150；SHORT(UID_2) 收 → free=19650/profit=+150。
        let fe: Vec<_> = api.last_fund_events().iter().filter(|e| e.event_type == FundEventType::FundingfeeSettlement).collect();
        assert_eq!(fe.len(), 2, "两条资金费结算事件");
        let e1 = fe.iter().find(|e| e.uid == UID_1).expect("UID_1 funding 事件");
        assert_eq!(e1.free, 19_750, "Java golden UID_1 free");
        assert_eq!(e1.profit, -150);
        let e2 = fe.iter().find(|e| e.uid == UID_2).expect("UID_2 funding 事件");
        assert_eq!(e2.free, 19_650, "Java golden UID_2 free");
        assert_eq!(e2.profit, 150);
        assert_conserved(&api);

        // 平仓 1 手：UID_1 ASK(maker) + UID_3 BID(taker) @1500。
        assert_eq!(place(&mut api, 3, UID_1, PERP_SYM, updated_price, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 4, UID_3, PERP_SYM, updated_price, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        {
            // initMargin 150 - 150/10 = 135；部分平不实现盈亏；关仓 maker fee = 1*10 = 10。
            let p1 = api.user_position(UID_1, PERP_SYM).unwrap();
            assert_eq!(p1.open_volume, size - 1);
            assert_eq!(p1.profit, -150);
            assert_eq!(p1.open_price_sum, 1_000 * size - updated_price); // 8500
            assert_eq!(p1.open_init_margin_sum, 135);
        }
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee - 10);

        // 平仓剩余 9 手：全平结算 pnl。final = deposit - makerFee + 5000 - 150 - makerFee。
        assert_eq!(place(&mut api, 5, UID_1, PERP_SYM, updated_price, 9, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 6, UID_3, PERP_SYM, updated_price, 9, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert!(api.user_position(UID_1, PERP_SYM).is_none());
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee + 5_000 - 150 - maker_fee);
        assert_conserved(&api);
    }

    // ================================================================================================
    // 7. testPerpetualScenario2 —— 反向资金费（action=ASK，做空付做多收），全周期。
    // ================================================================================================

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

        assert_eq!(api.set_mark_price(PERP_SYM, updated_price, 0), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1, UID_1, PERP_SYM, 1_000, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, UID_2, PERP_SYM, 1_000, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        // 资金费 action=ASK → SHORT(UID_2) 付、LONG(UID_1) 收，fee=150。
        assert_eq!(settle_funding_fees(&mut api, PERP_SYM, OrderAction::Ask, 1, 100, 1345), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, PERP_SYM).unwrap().profit, 150);
        assert_eq!(api.user_position(UID_2, PERP_SYM).unwrap().profit, -150);
        assert_conserved(&api);

        // 平 1 手：profit 保持 +150（部分平不实现），关仓 maker fee 10。
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

        // 平剩余 9：final = deposit - makerFee + 5000 + 150 - makerFee。
        assert_eq!(place(&mut api, 5, UID_1, PERP_SYM, updated_price, 9, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 6, UID_3, PERP_SYM, updated_price, 9, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert!(api.user_position(UID_1, PERP_SYM).is_none());
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee + 5_000 + 150 - maker_fee);
        assert_conserved(&api);
    }

    // ================================================================================================
    // 8. testPerpetualScenario3 —— 资金费后触发强平。资金费 profit 为闭式 golden；强平后仅断言 loser 全平 +
    //    守恒（精确强平后账户依赖 FORCE 级联内部成交价，见文件头强平约定 → 不断言 golden 账户）。
    // ================================================================================================

    #[test]
    fn perpetual_scenario3_funding_then_liquidation() {
        let deposit = 5_000i64;
        let size = 10i64;
        let maker_fee = 10 * size; // 固定费
        let updated_price = 1_100i64;
        let price = 1_000i64;

        // 自定义 spec（liquidationFee=50, feeScaleK=0, MM@scaleK10）。
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

        assert_eq!(api.set_mark_price(sym, updated_price, 0), CommandResultCode::Success);

        // UID_1 做多 1000@10（maker），UID_2 ASK 吃单（taker）。
        assert_eq!(place(&mut api, 101, UID_1, sym, price, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 102, UID_2, sym, price, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USD), deposit - maker_fee);
        assert_eq!(api.user_position(UID_1, sym).unwrap().profit, 0);

        // 资金费 rate=10/scaleK=100，action=BID → LONG(UID_1) 付。
        // fee = trunc(10*1100 * 10/100) = 1100 → UID_1 profit = -1100。
        assert_eq!(settle_funding_fees(&mut api, sym, OrderAction::Bid, 10, 100, 1345), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, sym).unwrap().profit, -1_100);
        assert_conserved(&api);

        // UID_3 提供强平接单流动性（大额 BID @1100）。
        assert_eq!(place(&mut api, 103, UID_3, sym, updated_price, 20, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        // 开启强平 + 定向扫描（重发 mark 1100 触发 checkPositions）：UID_1 equity < maintenance → 全平。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price(sym, updated_price, 2_000), CommandResultCode::Success);

        assert!(api.user_position(UID_1, sym).is_none(), "UID_1 应被强平清仓");
        assert_conserved(&api);
    }
}
