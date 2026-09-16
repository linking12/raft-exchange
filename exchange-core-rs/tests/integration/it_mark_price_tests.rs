//! 翻译自 Java `exchange.core2.tests.integration.ITExchangeCoreMarkPrice`。
//! 直连 Rust 引擎，逐条对拍 Java 黄金值：无标记价时期货拒单/现货放行、分档杠杆按 notional 校验 +
//! 标记价驱动的 `open_init_margin_sum` 记账。
//!
//! 未翻译（依赖 façade 未暴露的能力，逐条说明）：
//! - `testMarkPrice`：需 `updateCurrentPriceTo` + `triggerLiquidation`，且断言 `unrealizedProfit`/
//!   `liquidationPrice`/`marginRatioScaleK` 三个 report 级派生字段（`SymbolPositionRecord` 上只有
//!   `estimate_*` 方法，report 级 total_margin 无门面访问）→ 跳过。
//! - `testInitMarginAndMaintenanceMargin` / `testTieredMaintenanceMargin`：结尾均以
//!   `updateCurrentPriceTo` + `triggerLiquidation` 收束强平，`ExchangeApi` 无强平触发方法 → 跳过。
//!
//! `testTieredLeverage` 的 `marginRatioScaleK` 子断言略去（report 级 total_margin 未暴露），
//! 保留其杠杆分档校验 + `open_init_margin_sum` 记账（数值与 Java 一致）。

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

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    const BNB_ID: i32 = 11;
    const USDT_ID: i32 = 12;

    /// 对应 Java 字段 `symbol`：10001 perpetual、maker/taker=0、maintenanceMargin{10000:5,100000:10}@scaleK1000、
    /// maxLeverage{10000:75,100000:40}。
    fn symbol_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 10001,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(10_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(10_000, 75), (100_000, 40)]),
            ..Default::default()
        }
    }

    fn place_fut(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        symbol: i32,
        price: i64,
        size: i64,
        action: OrderAction,
        ot: OrderType,
        leverage: i32,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol,
            price,
            size,
            action,
            order_type: ot,
            leverage,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        })
    }

    fn create_user_with_money(api: &mut ExchangeApi, uid: i64, currency: i32, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, currency, amount, txid), CommandResultCode::Success);
    }

    // 没有标记价时不允许下期货单 → RISK_MARKPRICE_NOT_AVAILABLE；设置标记价后放行。
    #[test]
    fn test_submit_fail_when_no_mark_price() {
        let spec = symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);

        // 未设置 mark price → 拒单。
        assert_eq!(
            place_fut(&mut api, 101, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::RiskMarkpriceNotAvailable
        );
        // 设置 mark price 后重下同一 orderId → 成功。
        assert_eq!(api.set_mark_price(spec.symbol_id, 650), CommandResultCode::Success);
        assert_eq!(
            place_fut(&mut api, 101, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
    }

    // 没有标记价时允许下现货单（现货不依赖 mark price）→ 成功。
    #[test]
    fn test_submit_pass_when_no_mark_price() {
        // 对应 Java `initExchangeSymbols().get(0)`（BTC_USDT 现货）；scale_k=1，费率对本例行为无关。
        let spot = CoreSymbolSpecification {
            symbol_id: 10003,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_symbol(spot.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);

        assert_eq!(
            api.place_order(PlaceOrderRequest {
                order_id: 101,
                uid: UID_1,
                symbol: spot.symbol_id,
                price: 1000,
                size: 1,
                reserve_bid_price: 1000,
                action: OrderAction::Bid,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::Success
        );
    }

    // 分档 maxLeverage：<=10w 可 75x，>10w 只能 40x；开 100 手 @75x → 再开 @75x 触档拒单，
    // 调 40x 后开 1 手 → openInitMarginSum 分档累加 = 1358。
    #[test]
    fn test_tiered_leverage() {
        let spec = symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 500_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 500_000, 20);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);

        // 开 10w（100 手 @1000），75x。
        assert_eq!(
            place_fut(&mut api, 10000, UID_1, spec.symbol_id, 1000, 100, OrderAction::Bid, OrderType::Gtc, 75),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 10001, UID_2, spec.symbol_id, 1000, 100, OrderAction::Ask, OrderType::Gtc, 10),
            CommandResultCode::Success
        );

        // 再挂 1 手：notional 超过 10w，75x 触档 → RISK_INVALID_LEVERAGE。
        assert_eq!(
            place_fut(&mut api, 10002, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 75),
            CommandResultCode::RiskInvalidLeverage
        );

        // 调 40x 后开 1 手。
        assert_eq!(api.leverage_adjustment(UID_1, spec.symbol_id, 40), CommandResultCode::Success);
        assert_eq!(
            place_fut(&mut api, 10003, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 40),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 10004, UID_2, spec.symbol_id, 1000, 1, OrderAction::Ask, OrderType::Gtc, 10),
            CommandResultCode::Success
        );

        // openInitMarginSum = 1000*100/75 + 1000*1/40 = 1333 + 25 = 1358。
        let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
        assert_eq!(pos.open_init_margin_sum, 1358);
        assert_eq!(pos.pending_buy_size, 0);
        // marginRatioScaleK（Java 断 375）现由 single_user() report 派生字段暴露，可复现。
        let r = api.single_user(UID_1, 0);
        let p = r.positions.iter().find(|p| p.symbol == spec.symbol_id).unwrap();
        assert_eq!(p.margin_ratio_scale_k, 375);
    }

    // ============================================================================================
    // 强平/标记价 backfill —— harness 现支持 enable_liquidation()+set_mark_price_at() 触发定向扫描
    // （FORCE→IF→ADL 同调自动排空）。强平 fund event 走内部排空命令，`last_fund_events()` 不捕获，故断言
    // **状态**（仓位移除/减仓 + 全局守恒）+ 强平前的 report 派生字段（liquidation_price/margin_ratio_scale_k/
    // unrealized_pnl）via single_user()。MARGIN_ALERT/LIQUIDATION_ALERT 在 Rust 外置 no-op，不断言告警事件。
    // ============================================================================================

    /// 从 single_user 报表按 symbol 取 PositionView。
    fn pos_view(api: &ExchangeApi, uid: i64, symbol: i32) -> exchange_core_rs::core::reports::PositionView {
        api.single_user(uid, 0)
            .positions
            .into_iter()
            .find(|p| p.symbol == symbol)
            .expect("仓位报表记录应存在")
    }

    // testMarkPrice —— 标记价驱动的初始/维持保证金 + report 派生 unrealizedProfit/liquidationPrice/
    // marginRatioScaleK。**可复现前半段**（挂单估值 + 部分成交后的 report 派生字段）。收尾的
    // updateCurrentPriceTo(2)+triggerLiquidation 段依赖 updateCurrentPriceTo 的“撮合推进当前价 + 吃掉剩余
    // pending 8 手令 openVolume 涨到 10”语义（set_mark_price_at 只做 targeted 扫描，价格暴跌只会强平而非增仓）
    // → 该段无法复刻，按文件头 SKIP。
    #[test]
    fn test_mark_price_report_derived_margin() {
        let price = 680i64;
        let size = 10i64;
        let spec = symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 4_000_000, 20);

        assert_eq!(api.set_mark_price(spec.symbol_id, price), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 101, UID_1, spec.symbol_id, price, size, OrderAction::Bid, OrderType::Gtc, 0), CommandResultCode::Success);

        // 未成交：按挂单价估值，名义 680*10；无已开仓 → unrealized/liq/mr 全 0。
        {
            let p = pos_view(&api, UID_1, spec.symbol_id);
            assert_eq!(p.pending_buy_size, size);
            assert_eq!(p.pending_buy_avg_price, price);
            assert_eq!(p.unrealized_pnl, 0);
            assert_eq!(p.liquidation_price, 0);
            assert_eq!(p.margin_ratio_scale_k, 0);
        }

        // UID_2 ASK 2@680（taker）成交两手。
        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 102, uid: UID_2, symbol: spec.symbol_id, price, size: 2,
                action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 0,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );

        // openInitMarginSum = 2*680/1 = 1360；unrealized=0；liq=3；mr=4（逐字对齐 Java 黄金值）。
        {
            let p = pos_view(&api, UID_1, spec.symbol_id);
            assert_eq!(p.pending_buy_avg_price, 680);
            assert_eq!(p.open_volume, 2);
            assert_eq!(p.pending_buy_size, 8);
            assert_eq!(p.unrealized_pnl, 0);
            assert_eq!(p.liquidation_price, 3);
            assert_eq!(p.margin_ratio_scale_k, 4);
            assert_eq!(p.open_price_sum, 1360);
            assert_eq!(p.open_init_margin_sum, 1360);
        }
        assert!(api.total_balance().is_global_zero());
    }

    // testInitMarginAndMaintenanceMargin —— openInitMarginSum 随标记价 + 部分成交/减仓的记账（65→668→602），
    // 收尾在 mark=616 触发逐仓强平清仓。
    #[test]
    fn test_init_margin_and_maintenance_margin() {
        let spec = symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 6_800, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 50_000, 20);

        // markPrice 650，UID_1 挂 10@680 lev10。
        assert_eq!(api.set_mark_price(spec.symbol_id, 650), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10001, UID_1, spec.symbol_id, 680, 10, OrderAction::Bid, OrderType::Gtc, 10), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.pending_buy_size, 10);
            assert_eq!(pos.pending_buy_avg_price, 680);
        }

        // UID_2 ASK 1@680 → 成交 1；openInitMarginSum = 650*1/10 = 65；剩余 pending 9 仍按挂单价 680。
        assert_eq!(place_fut(&mut api, 10002, UID_2, spec.symbol_id, 680, 1, OrderAction::Ask, OrderType::Gtc, 0), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_init_margin_sum, 65);
            assert_eq!(pos.pending_buy_size, 9);
            assert_eq!(pos.pending_buy_avg_price, 680);
        }

        // markPrice 更新到 670，UID_2 ASK 9@680 → 成交 9；openInitMarginSum = 65 + 670*9/10 = 668。
        assert_eq!(api.set_mark_price(spec.symbol_id, 670), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10003, UID_2, spec.symbol_id, 680, 9, OrderAction::Ask, OrderType::Gtc, 0), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_init_margin_sum, 668);
            assert_eq!(pos.pending_buy_size, 0);
            assert_eq!(pos.pending_buy_avg_price, 0);
        }

        // UID_1 减仓 1（ASK 1@680 maker）+ UID_2 BID 1@680 taker；openInitMarginSum -= 668*1/10 = 66 → 602。
        assert_eq!(place_fut(&mut api, 10004, UID_1, spec.symbol_id, 680, 1, OrderAction::Ask, OrderType::Gtc, 10), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10005, UID_2, spec.symbol_id, 680, 1, OrderAction::Bid, OrderType::Gtc, 0), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_init_margin_sum, 602);
            assert_eq!(pos.pending_buy_size, 0);
            assert_eq!(pos.pending_buy_avg_price, 0);
        }

        // markPrice 617 仓位不变（未触发）。
        assert_eq!(api.set_mark_price(spec.symbol_id, 617), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 9);

        // markPrice 616 触发强平：先挂 UID_2 BID 10@616 承接 FORCE 卖单，再开引擎 + 落价触发定向扫描清仓。
        assert_eq!(place_fut(&mut api, 10006, UID_2, spec.symbol_id, 616, 10, OrderAction::Bid, OrderType::Gtc, 0), CommandResultCode::Success);
        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(spec.symbol_id, 616, 2_000), CommandResultCode::Success);
        assert!(api.user_position(UID_1, spec.symbol_id).is_none(), "mark=616 应触发逐仓全平");
        assert!(api.total_balance().is_global_zero());
    }

    // testTieredMaintenanceMargin —— 分档维持保证金：开 68w 名义，mark=622 时 report liquidationPrice=620、
    // marginRatioScaleK=817；mark=620 触发强平清仓。
    #[test]
    fn test_tiered_maintenance_margin() {
        let spec = symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 500_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 500_000, 20);

        assert_eq!(api.set_mark_price(spec.symbol_id, 650), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10001, UID_1, spec.symbol_id, 680, 1000, OrderAction::Bid, OrderType::Gtc, 10), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10002, UID_2, spec.symbol_id, 680, 1000, OrderAction::Ask, OrderType::Gtc, 10), CommandResultCode::Success);

        // 全部成交，openInitMarginSum = 650*1000/10 = 65000。
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_init_margin_sum, 65_000);
            assert_eq!(pos.pending_buy_size, 0);
            assert_eq!(pos.pending_buy_avg_price, 0);
        }

        // markPrice 622：分档累加 MM → LP=620，marginRatioScaleK=817（Java 断 0.817*scaleK）。
        assert_eq!(api.set_mark_price(spec.symbol_id, 622), CommandResultCode::Success);
        {
            let p = pos_view(&api, UID_1, spec.symbol_id);
            assert_eq!(p.open_volume, 1000);
            assert_eq!(p.liquidation_price, 620);
            assert_eq!(p.margin_ratio_scale_k, 817);
        }

        // markPrice 620 触发强平：UID_2 BID 1000@620 承接，开引擎 + 落价清仓。
        assert_eq!(place_fut(&mut api, 10003, UID_2, spec.symbol_id, 620, 1000, OrderAction::Bid, OrderType::Gtc, 10), CommandResultCode::Success);
        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(spec.symbol_id, 620, 2_000), CommandResultCode::Success);
        assert!(api.user_position(UID_1, spec.symbol_id).is_none(), "mark=620(LP) 应触发全平");
        assert!(api.total_balance().is_global_zero());
    }

    // testCrossMarginLiquidation —— 全仓单 symbol 多头，report liquidationPrice=9055；mark=9054 触发全平。
    // symbol 用 initFutureSymbol(symbolId=2, quoteId=840)（固定费 maker10/taker20），非本文件默认 10001 spec。
    #[test]
    fn test_cross_margin_liquidation() {
        const SYMBOL_ID: i32 = 2;
        const QUOTE_ID: i32 = 840;
        let spec = CoreSymbolSpecification {
            symbol_id: SYMBOL_ID,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: 1,
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
        };

        let mut api = ExchangeApi::new();
        api.add_currency(1, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, 10_000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, QUOTE_ID, 1_000, 10);
        create_user_with_money(&mut api, UID_2, QUOTE_ID, 4_000_000, 20);

        // 开仓：UID_1 BID 1@10000 maker（CROSS，LONG），UID_2 ASK 1@10000 taker。
        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 10000, uid: UID_1, symbol: SYMBOL_ID, price: 10_000, size: 1,
                action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );
        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 10001, uid: UID_2, symbol: SYMBOL_ID, price: 10_000, size: 1,
                action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );

        // 全仓强平价 9055（report 派生）。
        assert_eq!(pos_view(&api, UID_1, SYMBOL_ID).liquidation_price, 9_055);

        // 落价到 9054 触发强平：UID_2 BID 1@9054 承接 FORCE 卖单。
        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 10002, uid: UID_2, symbol: SYMBOL_ID, price: 9_054, size: 1,
                action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );
        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(SYMBOL_ID, 9_054, 2_000), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "mark=9054 应触发全仓全平");
        assert!(api.total_balance().is_global_zero());
    }
}
