//! 翻译自 Java `exchange.core2.tests.integration.ITExchangeCorePriceScale`。
//!
//! 直连引擎断言 result code + 逐用户余额（accounts / exchangeLocked）+ 期货仓位标量 + 全局守恒，
//! 逐条对拍 Java 黄金值。缩放约定：`currency_scale_k = 10^digit`；`available = accounts − exchangeLocked`
//! 对应 Java `ExchangeTestContainer.available`；`isGlobalBalancesAllZero` 的可观测等价 =
//! Σ accounts[cur] + adjustments[cur] + fees[cur] == 0（exchangeLocked 在 Java 聚合里既进 accountBalances
//! 的减项又单列一桶，净额等于 raw accounts，故守恒式只需 raw accounts）。
//!
//! 未翻译（依赖 ExchangeApi 门面未暴露的能力，见文件末尾说明）：
//!   - testWithdrawBlockedBySpotLockOnSpotOnlyMode（需 createSpotOnly / marginTradingMode=DISABLED 容器）
//!   - testSuspendSweepsDustToFees / testSuspendDoesNotSweepWhenUserHasRealAccounts /
//!     testSuspendCleanAccountDoesNotTriggerSweep（需 SUSPEND_USER 命令）
//!   - testCrossLiquidationPriceAccountsForSpotLock（需 SingleUserReport.liquidationPrice）

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::position_direction::PositionDirection;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::exchange_api::{
        CancelOrderRequest, ExchangeApi, MarginAdjustmentRequest, PlaceFuturesOrderRequest, PlaceOrderRequest,
    };

    // 币种 id（Java 字段：BTC_ID=8, ETH_ID=9, BNB_ID=11, USDT_ID=12；XBT/LTC 走 TestConstants）。
    const BNB: i32 = 11;
    const USDT: i32 = 12;
    const BTC: i32 = 8;
    const XBT: i32 = 3762;
    const LTC: i32 = 4141;

    // currency_scale_k = 10^digit：BNB/BTC/XBT/LTC digit 8，USDT digit 6。
    const BNB_SCALE: i64 = 100_000_000;
    const USDT_SCALE: i64 = 1_000_000;
    const BTC_SCALE: i64 = 100_000_000;
    const C8_SCALE: i64 = 100_000_000; // XBT/LTC digit 8

    const SYM_FUT: i32 = 10001; // BNB_USDT (futures)
    const SYM_SPOT: i32 = 20001; // BNB_USDT_SPOT
    const SYM_BTC_FUT: i32 = 10002; // BTC_USDT (futures)
    const SYM_FEE: i32 = 9340; // SYMBOLSPECFEE_XBT_LTC (SYMBOL_EXCHANGE_FEE)

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    // ---------------- symbol spec 建造器（严格对齐 Java 常量） ----------------

    fn bnb_usdt_fut() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM_FUT,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB,
            quote_currency: USDT,
            base_scale_k: 1_000,     // 10^3
            quote_scale_k: 100_000,  // 10^5
            taker_fee: 0,
            maker_fee: 0,
            maintenance_margin: BTreeMap::from([(10_000 * 100_000_000i64, 5i64)]), // 10_000 * 10^(3+5)
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(10_000 * 100_000_000i64, 75i64)]),
            ..Default::default()
        }
    }

    fn bnb_usdt_spot() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM_SPOT,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BNB,
            quote_currency: USDT,
            base_scale_k: 1_000,
            quote_scale_k: 100_000,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn btc_usdt_fut() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM_BTC_FUT,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BTC,
            quote_currency: USDT,
            base_scale_k: 10_000,    // 10^4
            quote_scale_k: 100_000,  // 10^5
            maker_fee: 1,
            taker_fee: 2,
            maintenance_margin: BTreeMap::from([(10_000 * 1_000_000_000i64, 5i64)]), // 10_000 * 10^(4+5)
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(10_000 * 1_000_000_000i64, 10i64)]),
            ..Default::default()
        }
    }

    // SYMBOLSPECFEE_XBT_LTC：固定费 taker=1900 / maker=700，baseScaleK=1e6 quoteScaleK=1e4。
    fn xbt_ltc_fee() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM_FEE,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: XBT,
            quote_currency: LTC,
            base_scale_k: 1_000_000, // 10^6
            quote_scale_k: 10_000,   // 10^4
            taker_fee: 1_900,
            maker_fee: 700,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    // ---------------- 通用 helper ----------------

    fn create_user_with_money(api: &mut ExchangeApi, uid: i64, currency: i32, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, currency, amount, txid), CommandResultCode::Success);
    }

    fn spot_bid(order_id: i64, uid: i64, symbol: i32, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        // budget 单的 price 字段即总预算，reserve 同价（对齐 Java reservePrice=price/budget）。
        PlaceOrderRequest { order_id, uid, symbol, price, size, reserve_bid_price: price, action: OrderAction::Bid, order_type: ot }
    }
    fn spot_ask(order_id: i64, uid: i64, symbol: i32, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol, price, size, reserve_bid_price: 0, action: OrderAction::Ask, order_type: ot }
    }

    fn fut(order_id: i64, uid: i64, symbol: i32, price: i64, size: i64, action: OrderAction, ot: OrderType, leverage: i32, mm: MarginMode) -> PlaceFuturesOrderRequest {
        PlaceFuturesOrderRequest { order_id, uid, symbol, price, size, action, order_type: ot, leverage, margin_mode: mm, reduce_only: false }
    }

    fn available(api: &ExchangeApi, uid: i64, cur: i32) -> i64 {
        api.user_account(uid, cur) - api.user_locked(uid, cur)
    }

    fn conserved(api: &ExchangeApi, cur: i32) -> i64 {
        api.ups().users.values().map(|p| p.account(cur)).sum::<i64>() + api.adjustments(cur) + api.fees(cur)
    }

    // ================================================================
    // 期货 / 现货成交的价格缩放
    // ================================================================

    // 对拍 testMarginTradePriceScale：0.1 BNB @ 753.4 10x，markPrice 750，校验浮盈/初始保证金/持仓成本缩放。
    #[test]
    fn margin_trade_price_scale() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);

        let charge = (750.55 * USDT_SCALE as f64) as i64; // 750_550_000
        create_user_with_money(&mut api, UID_1, USDT, charge, 1);
        create_user_with_money(&mut api, UID_2, USDT, charge, 2);
        assert_eq!(api.user_account(UID_1, USDT), charge);

        let mark_price = 750 * 100_000; // 750 * quoteScaleK = 75_000_000
        assert_eq!(api.set_mark_price(SYM_FUT, mark_price), CommandResultCode::Success);

        let size = (0.1 * 1_000.0) as i64; // 100
        let price = (753.4 * 100_000.0) as i64; // 75_340_000

        assert_eq!(api.place_futures_order(fut(10001, UID_1, SYM_FUT, price, size, OrderAction::Bid, OrderType::Gtc, 10, MarginMode::Isolated)), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(fut(10002, UID_2, SYM_FUT, price, size, OrderAction::Ask, OrderType::Gtc, 10, MarginMode::Isolated)), CommandResultCode::Success);

        // 开仓：markPrice==750 → 浮亏 (750-753.4)*0.1 = -0.34 → -0.34 * 10^(3+5) = -34_000_000。
        let unrealized = (-0.34 * 100_000_000.0) as i64; // -34_000_000（product scale = base*quote = 1e8）
        let initial_margin = (0.1 * 750.0 / 10.0 * 100_000_000.0) as i64; // 750_000_000（init margin 按 mark notional/leverage）
        let open_price_sum = (0.1 * 753.4 * 100_000_000.0) as i64; // 7_534_000_000（成交价 × size）

        let pos = api.user_position(UID_1, SYM_FUT).expect("多头开仓后必有仓位");
        assert_eq!(pos.direction, PositionDirection::Long);
        assert_eq!(pos.open_volume, size);
        assert_eq!(pos.open_init_margin_sum, initial_margin);
        assert_eq!(pos.open_price_sum, open_price_sum);
        assert_eq!(pos.estimate_unrealized_profit(mark_price), unrealized);

        // isolated 保证金虚拟，不动 accounts；fee=0。
        assert_eq!(api.user_account(UID_1, USDT), charge);
    }

    // 对拍 testSpotTradePriceScale：0.05 BNB @ 745.123 现货成交，校验双方 base/quote 余额缩放。
    #[test]
    fn spot_trade_price_scale() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE; // 1_000_000_000
        let bnb_deposit = 2 * BNB_SCALE; // 200_000_000
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, bnb_deposit, 2);

        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_account(UID_1, BNB), 0);
        assert_eq!(api.user_account(UID_2, BNB), bnb_deposit);
        assert_eq!(api.user_account(UID_2, USDT), 0);

        let size = (0.05 * 1_000.0) as i64; // 50
        let price = (745.123 * 100_000.0) as i64; // 74_512_300

        assert_eq!(api.place_order(spot_bid(10001, UID_1, SYM_SPOT, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_ask(10002, UID_2, SYM_SPOT, price, size, OrderType::Gtc)), CommandResultCode::Success);

        // 交易额 = 0.05 * 745.123 = 37.25615 USDT → 37_256_150（sizePriceToCurrencyScale）。
        let trade_amount_currency = 37_256_150i64;
        // 收入 0.05 BNB → 5_000_000（symbolToCurrencyScale）。
        let bnb_balance = 5_000_000i64;

        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit - trade_amount_currency);
        assert_eq!(api.user_account(UID_1, BNB), bnb_balance);
        assert_eq!(api.user_account(UID_2, BNB), bnb_deposit - bnb_balance);
        assert_eq!(api.user_account(UID_2, USDT), trade_amount_currency);
    }

    // ================================================================
    // 提现 / 下单要考虑期货持仓 & 现货冻结
    // ================================================================

    // 对拍 testWithdrawWhenMarginExist：期货持仓隐式冻结 750 USDT，提现受阻直到补足。
    #[test]
    fn withdraw_when_margin_exist() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 750 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 2 * BNB_SCALE, 2);

        // 期货 BID 1 BNB @750（无对手，挂单）→ 隐式冻结 750 USDT margin（leverage 默认 1）。
        let size = 1_000; // 1 BNB * baseScaleK
        let price = 750 * 100_000;
        assert_eq!(api.place_futures_order(fut(10001, UID_1, SYM_FUT, price, size, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Isolated)), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);

        // 提现全部 → NSF（free = 1000 - 750 = 250 < 1000）。
        assert_eq!(api.balance_adjustment(UID_1, USDT, -usdt_deposit, 10), CommandResultCode::RiskNsf);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);

        // 补 750-1，再提 1000 → 仍 NSF（free = 999_999_999 < 1e9）。
        let extra = 750 * USDT_SCALE - 1;
        assert_eq!(api.balance_adjustment(UID_1, USDT, extra, 11), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, USDT, -usdt_deposit, 12), CommandResultCode::RiskNsf);

        // 再补 1 → free 恰好 1e9，提现成功。
        assert_eq!(api.balance_adjustment(UID_1, USDT, 1, 13), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, USDT, -usdt_deposit, 100), CommandResultCode::Success);
    }

    // 对拍 testPlaceExchangeWhenMarginExist：期货冻结 500u 后现货只能再下 ≤500u 的单。
    #[test]
    fn place_exchange_when_margin_exist() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 500 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 2 * BNB_SCALE, 2);

        let size = 1_000;
        let price = 500 * 100_000;
        // 期货 BID 冻结 500 USDT（虚拟，不进 exchangeLocked）。
        assert_eq!(api.place_futures_order(fut(10001, UID_1, SYM_FUT, price, size, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Isolated)), CommandResultCode::Success);
        assert_eq!(available(&api, UID_1, USDT), usdt_deposit);

        // 现货 BID @price+1：名义 > 剩余 500u → NSF。
        assert_eq!(api.place_order(spot_bid(10002, UID_1, SYM_SPOT, price + 1, size, OrderType::Gtc)), CommandResultCode::RiskNsf);
        // 现货 BID @price：名义恰好 500u → SUCCESS。
        assert_eq!(api.place_order(spot_bid(10003, UID_1, SYM_SPOT, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(available(&api, UID_1, USDT), usdt_deposit / 2);
    }

    // ================================================================
    // 全仓 / 逐仓补充保证金边缘场景
    // ================================================================

    // 对拍 testExtraMargin4Cross：CROSS 冻结满后差 1 不能下单，补足后成功。
    #[test]
    fn extra_margin_4_cross() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 500 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 2 * BNB_SCALE, 2);

        let price = 500 * 100_000;
        // CROSS BID 2 BNB @500 → 冻结 1000u。
        assert_eq!(api.place_futures_order(fut(10001, UID_1, SYM_FUT, price, 2_000, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Cross)), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);

        // 再下 1 BNB → NSF。
        let mk_order = |id: i64| fut(id, UID_1, SYM_FUT, price, 1_000, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Cross);
        assert_eq!(api.place_futures_order(mk_order(10002)), CommandResultCode::RiskNsf);

        // CROSS 补 500-1 → 仍差 1，下单 NSF。
        let extra = usdt_deposit / 2 - 1;
        assert_eq!(api.margin_adjustment(MarginAdjustmentRequest { uid: UID_1, symbol: USDT, action: OrderAction::Bid, amount: extra, margin_mode: MarginMode::Cross, order_id: 20 }), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit + extra);
        assert_eq!(api.place_futures_order(mk_order(10003)), CommandResultCode::RiskNsf);

        // 补最后 1 → 下单成功。
        assert_eq!(api.margin_adjustment(MarginAdjustmentRequest { uid: UID_1, symbol: USDT, action: OrderAction::Bid, amount: 1, margin_mode: MarginMode::Cross, order_id: 21 }), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(mk_order(10004)), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit + extra + 1);
    }

    // 对拍 testExtraMargin4Isolated：ISOLATED 持仓，CROSS 补账户余额差 1 不能下单，补足后成功。
    #[test]
    fn extra_margin_4_isolated() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 500 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 2 * BNB_SCALE, 2);

        let price = 500 * 100_000;
        assert_eq!(api.place_futures_order(fut(10001, UID_1, SYM_FUT, price, 2_000, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Isolated)), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);

        let mk_order = |id: i64| fut(id, UID_1, SYM_FUT, price, 1_000, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Isolated);
        assert_eq!(api.place_futures_order(mk_order(10002)), CommandResultCode::RiskNsf);

        let extra = usdt_deposit / 2 - 1;
        assert_eq!(api.margin_adjustment(MarginAdjustmentRequest { uid: UID_1, symbol: USDT, action: OrderAction::Bid, amount: extra, margin_mode: MarginMode::Cross, order_id: 20 }), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit + extra);
        assert_eq!(api.place_futures_order(mk_order(10003)), CommandResultCode::RiskNsf);

        assert_eq!(api.margin_adjustment(MarginAdjustmentRequest { uid: UID_1, symbol: USDT, action: OrderAction::Bid, amount: 1, margin_mode: MarginMode::Cross, order_id: 21 }), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(mk_order(10004)), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit + extra + 1);
    }

    // 对拍 testAdjustLeverage：100 USDT 刚够 10x；调高杠杆成功、调低杠杆 NSF。
    #[test]
    fn adjust_leverage() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 500 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 100 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);

        let price = 500 * 100_000;
        // ISOLATED BID 2 BNB @500 10x → margin 100 USDT，刚好够。
        assert_eq!(api.place_futures_order(fut(10001, UID_1, SYM_FUT, price, 2_000, OrderAction::Bid, OrderType::Gtc, 10, MarginMode::Isolated)), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);

        // 调高到 11x（占用更少）→ 成功。
        assert_eq!(api.leverage_adjustment(UID_1, SYM_FUT, 11), CommandResultCode::Success);
        // 调低到 9x（占用更多，资金不够）→ NSF。
        assert_eq!(api.leverage_adjustment(UID_1, SYM_FUT, 9), CommandResultCode::RiskNsf);
    }

    // ================================================================
    // 现货挂单冻结的守恒 / 隔离性
    // ================================================================

    // 对拍 testWithdrawBlockedBySpotLock：提现可支配上限 = accounts − exchangeLocked。
    #[test]
    fn withdraw_blocked_by_spot_lock() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);

        let size = 1_000;
        let price = 500 * 100_000;
        assert_eq!(api.place_order(spot_bid(10001, UID_1, SYM_SPOT, price, size, OrderType::Gtc)), CommandResultCode::Success);

        let expected_lock = 500 * USDT_SCALE;
        let free_after_lock = usdt_deposit - expected_lock;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), expected_lock);
        assert_eq!(available(&api, UID_1, USDT), free_after_lock);

        // 提现 free+1 → NSF；提现 free → SUCCESS。
        assert_eq!(api.balance_adjustment(UID_1, USDT, -(free_after_lock + 1), 10), CommandResultCode::RiskNsf);
        assert_eq!(api.balance_adjustment(UID_1, USDT, -free_after_lock, 11), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, USDT), expected_lock);
        assert_eq!(api.user_locked(UID_1, USDT), expected_lock);
        assert_eq!(available(&api, UID_1, USDT), 0);
    }

    // 对拍 testPlaceMarginOrderBlockedBySpotLock：现货冻结后期货保证金校验按 accounts − exchangeLocked。
    #[test]
    fn place_margin_order_blocked_by_spot_lock() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 500 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);

        // 现货 BID 1 BNB @500 → 冻结 500 USDT。
        assert_eq!(api.place_order(spot_bid(10001, UID_1, SYM_SPOT, 500 * 100_000, 1_000, OrderType::Gtc)), CommandResultCode::Success);

        // 期货 BID @501 CROSS：名义 501 + openLoss 1 > free 500 → NSF。
        let fut_price = 501 * 100_000;
        assert_eq!(api.place_futures_order(fut(20001, UID_1, SYM_FUT, fut_price, 1_000, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Cross)), CommandResultCode::RiskNsf);

        // 充 2 USDT → free 502 ≥ 502 → 成功。
        assert_eq!(api.balance_adjustment(UID_1, USDT, 2 * USDT_SCALE, 2), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(fut(20002, UID_1, SYM_FUT, fut_price, 1_000, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Cross)), CommandResultCode::Success);
    }

    // 对拍 testIsolatedMarginAdjustBlockedBySpotLock：现货冻结后 ISOLATED 加保证金必须扣 exchangeLocked。
    #[test]
    fn isolated_margin_adjust_blocked_by_spot_lock() {
        let mut api = ExchangeApi::new();
        api.add_currency(BTC, BTC_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        api.add_currency(BNB, BNB_SCALE);
        assert_eq!(api.add_futures_symbol(btc_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_BTC_FUT, 10_000 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, USDT, 100_000_000i64 * USDT_SCALE, 2);

        // UID_2 挂 ASK 提供流动性。
        let open_size = 10_000; // 1 BTC * baseScaleK(1e4)
        let open_price = 10_000 * 100_000;
        assert_eq!(api.place_futures_order(fut(30001, UID_2, SYM_BTC_FUT, open_price, open_size, OrderAction::Ask, OrderType::Gtc, 1, MarginMode::Cross)), CommandResultCode::Success);

        // UID_1 开 ISOLATED BTC 多仓 10x（size = 1/10 BTC）。
        assert_eq!(api.place_futures_order(fut(30002, UID_1, SYM_BTC_FUT, open_price, open_size / 10, OrderAction::Bid, OrderType::Gtc, 10, MarginMode::Isolated)), CommandResultCode::Success);

        // 现货冻结 500 USDT。
        assert_eq!(api.place_order(spot_bid(30003, UID_1, SYM_SPOT, 500 * 100_000, 1_000, OrderType::Gtc)), CommandResultCode::Success);
        let spot_lock = 500 * USDT_SCALE;
        assert_eq!(api.user_locked(UID_1, USDT), spot_lock);

        // ISOLATED 加保证金 (accounts − spotLock + 1) → NSF。
        let too_much = usdt_deposit - spot_lock + 1;
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest { uid: UID_1, symbol: SYM_BTC_FUT, action: OrderAction::Bid, amount: too_much, margin_mode: MarginMode::Isolated, order_id: 40 }),
            CommandResultCode::RiskNsf
        );
    }

    // 对拍 testSpotLockPartialReleaseOnPartialFill：maker BID 部分成交，lock 按比例释放、accounts 按实际净额扣。
    #[test]
    fn spot_lock_partial_release_on_partial_fill() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 2 * BNB_SCALE, 2);

        let price = 500 * 100_000;
        assert_eq!(api.place_order(spot_bid(10001, UID_1, SYM_SPOT, price, 1_000, OrderType::Gtc)), CommandResultCode::Success);
        let full_lock = 500 * USDT_SCALE;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), full_lock);

        // UID_2 ASK 0.4 BNB @500 IOC 部分撮合。
        assert_eq!(api.place_order(spot_ask(10002, UID_2, SYM_SPOT, price, 400, OrderType::Ioc)), CommandResultCode::Success);

        let matched_quote = 200 * USDT_SCALE;
        let matched_base = (0.4 * BNB_SCALE as f64) as i64; // 40_000_000
        let remaining_lock = full_lock - matched_quote;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit - matched_quote);
        assert_eq!(api.user_locked(UID_1, USDT), remaining_lock);
        assert_eq!(api.user_account(UID_1, BNB), matched_base);
        assert_eq!(available(&api, UID_1, USDT), usdt_deposit - matched_quote - remaining_lock);

        assert_eq!(conserved(&api, USDT), 0);
        assert_eq!(conserved(&api, BNB), 0);
    }

    // 对拍 testSpotLockFullReleaseOnCancel：cancel 后 exchangeLocked 归零、accounts 不动。
    #[test]
    fn spot_lock_full_release_on_cancel() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);

        let price = 500 * 100_000;
        assert_eq!(api.place_order(spot_bid(10001, UID_1, SYM_SPOT, price, 1_000, OrderType::Gtc)), CommandResultCode::Success);
        let lock = 500 * USDT_SCALE;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), lock);

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 10001, uid: UID_1, symbol: SYM_SPOT }), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), 0);
        assert_eq!(available(&api, UID_1, USDT), usdt_deposit);
        assert_eq!(conserved(&api, USDT), 0);
    }

    // 对拍 testSpotLockAccumulatesAcrossMultipleOrders：多挂单 lock 累加，cancel 其一只释放对应额度。
    #[test]
    fn spot_lock_accumulates_across_multiple_orders() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 2_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);

        assert_eq!(api.place_order(spot_bid(10001, UID_1, SYM_SPOT, 500 * 100_000, 1_000, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_bid(10002, UID_1, SYM_SPOT, 400 * 100_000, 500, OrderType::Gtc)), CommandResultCode::Success);

        let total_lock = (500 + 200) * USDT_SCALE;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), total_lock);
        assert_eq!(available(&api, UID_1, USDT), usdt_deposit - total_lock);

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 10002, uid: UID_1, symbol: SYM_SPOT }), CommandResultCode::Success);
        let remaining = 500 * USDT_SCALE;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), remaining);
        assert_eq!(available(&api, UID_1, USDT), usdt_deposit - remaining);
        assert_eq!(conserved(&api, USDT), 0);
    }

    // ================================================================
    // BUDGET 单 exchangeLocked 释放
    // ================================================================

    // 对拍 testFokBudgetFullFillReleasesExchangeLocked。
    #[test]
    fn fok_budget_full_fill_releases_exchange_locked() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 5 * BNB_SCALE, 2);

        let size = 1_000;
        let unit_price = 200 * 100_000;
        assert_eq!(api.place_order(spot_ask(20001, UID_2, SYM_SPOT, unit_price, size, OrderType::Gtc)), CommandResultCode::Success);

        let budget = size * unit_price;
        assert_eq!(api.place_order(spot_bid(20002, UID_1, SYM_SPOT, budget, size, OrderType::FokBudget)), CommandResultCode::Success);

        let actual_paid = 200 * USDT_SCALE;
        let actual_base = BNB_SCALE; // 1 BNB
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit - actual_paid);
        assert_eq!(api.user_locked(UID_1, USDT), 0);
        assert_eq!(api.user_account(UID_1, BNB), actual_base);
        let l2 = api.request_l2(SYM_SPOT, 10);
        assert!(l2.bid_prices.is_empty(), "taker 无挂单残留");
        assert_eq!(conserved(&api, USDT), 0);
        assert_eq!(conserved(&api, BNB), 0);
    }

    // 对拍 testFokBudgetFullRejectReleasesExchangeLocked。
    #[test]
    fn fok_budget_full_reject_releases_exchange_locked() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 5 * BNB_SCALE, 2);

        // maker ASK @250（高于 taker budget 单价）→ FOK 无法 fill。
        assert_eq!(api.place_order(spot_ask(21001, UID_2, SYM_SPOT, 250 * 100_000, 1_000, OrderType::Gtc)), CommandResultCode::Success);

        let budget = 1_000 * (200 * 100_000);
        assert_eq!(api.place_order(spot_bid(21002, UID_1, SYM_SPOT, budget, 1_000, OrderType::FokBudget)), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), 0);
        assert_eq!(api.user_account(UID_1, BNB), 0);
        assert_eq!(conserved(&api, USDT), 0);
    }

    // 对拍 testIocBudgetPartialFillReleasesExchangeLocked。
    #[test]
    fn ioc_budget_partial_fill_releases_exchange_locked() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 5 * BNB_SCALE, 2);

        // maker ASK 仅 0.4 BNB @200。
        let unit_price = 200 * 100_000;
        assert_eq!(api.place_order(spot_ask(22001, UID_2, SYM_SPOT, unit_price, 400, OrderType::Gtc)), CommandResultCode::Success);

        // taker IOC_BUDGET 想要 1 BNB，预算 200 USDT → 实成 0.4，剩 0.6 拒。
        let budget = 1_000 * unit_price;
        assert_eq!(api.place_order(spot_bid(22002, UID_1, SYM_SPOT, budget, 1_000, OrderType::IocBudget)), CommandResultCode::Success);

        let actual_paid = 80 * USDT_SCALE;
        let actual_base = (0.4 * BNB_SCALE as f64) as i64;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit - actual_paid);
        assert_eq!(api.user_locked(UID_1, USDT), 0, "部分成交后 exchangeLocked 完全释放");
        assert_eq!(api.user_account(UID_1, BNB), actual_base);
        assert_eq!(conserved(&api, USDT), 0);
        assert_eq!(conserved(&api, BNB), 0);
    }

    // 对拍 testIocBudgetFullRejectReleasesExchangeLocked：撞空 orderbook，走 ev.nextEvent==null 路径。
    #[test]
    fn ioc_budget_full_reject_releases_exchange_locked() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);

        let budget = 1_000 * (200 * 100_000);
        assert_eq!(api.place_order(spot_bid(23002, UID_1, SYM_SPOT, budget, 1_000, OrderType::IocBudget)), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), 0);
        assert_eq!(api.user_account(UID_1, BNB), 0);
        assert_eq!(conserved(&api, USDT), 0);
    }

    // ================================================================
    // 精度 dust（SYMBOLSPECFEE_XBT_LTC）
    // ================================================================

    // 对拍 testMakerBidExchangeLockedPrecisionDriftOnPartialFills：单次 lock floor 153、4 次 release floor 152、残 1。
    #[test]
    fn maker_bid_exchange_locked_precision_drift_on_partial_fills() {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, C8_SCALE);
        api.add_currency(LTC, C8_SCALE);
        assert_eq!(api.add_symbol(xbt_ltc_fee()), CommandResultCode::Success);

        create_user_with_money(&mut api, UID_1, LTC, 100_000, 1);
        create_user_with_money(&mut api, UID_2, XBT, 100_000_000i64 * 100_000_000, 2);

        let price = 1_933;
        assert_eq!(api.place_order(spot_bid(40001, UID_1, SYM_FEE, price, 4, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, LTC), 153, "单次 lock = floor(15332/100) = 153");

        // 4 个独立 size=1 ASK 撮合。
        for i in 0..4 {
            assert_eq!(api.place_order(spot_ask(40100 + i, UID_2, SYM_FEE, price, 1, OrderType::Ioc)), CommandResultCode::Success);
        }

        let l2 = api.request_l2(SYM_FEE, 10);
        assert!(l2.bid_prices.is_empty(), "BID 全部成交，无残量");
        assert_eq!(api.user_locked(UID_1, LTC), 1, "精度漂移：4×floor(3833/100)=152，残 1");
        assert_eq!(conserved(&api, LTC), 0);
        assert_eq!(conserved(&api, XBT), 0);
    }

    // 对拍 testMakerBidExchangeLockedDustStaysAfterCancelOfRemainder。
    #[test]
    fn maker_bid_exchange_locked_dust_stays_after_cancel_of_remainder() {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, C8_SCALE);
        api.add_currency(LTC, C8_SCALE);
        assert_eq!(api.add_symbol(xbt_ltc_fee()), CommandResultCode::Success);

        create_user_with_money(&mut api, UID_1, LTC, 100_000, 1);
        create_user_with_money(&mut api, UID_2, XBT, 100_000_000i64 * 100_000_000, 2);

        let price = 1_933;
        let bid_order_id = 50001;
        assert_eq!(api.place_order(spot_bid(bid_order_id, UID_1, SYM_FEE, price, 4, OrderType::Gtc)), CommandResultCode::Success);

        // 部分成交 2 lots。
        for i in 0..2 {
            assert_eq!(api.place_order(spot_ask(50100 + i, UID_2, SYM_FEE, price, 1, OrderType::Ioc)), CommandResultCode::Success);
        }

        assert_eq!(api.user_locked(UID_1, LTC), 77, "partial 后 lock = 153 - 38×2 = 77");
        let accounts_before_cancel = 100_000 - 26 * 2; // 99_948
        assert_eq!(api.user_account(UID_1, LTC), accounts_before_cancel);

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: bid_order_id, uid: UID_1, symbol: SYM_FEE }), CommandResultCode::Success);

        // cancel 释放 floor(2×3833/100)=76 → lock 77-76=1；dust 留在 lock，accounts 不动。
        assert_eq!(api.user_locked(UID_1, LTC), 1);
        assert_eq!(api.user_account(UID_1, LTC), accounts_before_cancel);
        assert_eq!(available(&api, UID_1, LTC), accounts_before_cancel - 1);
        let l2 = api.request_l2(SYM_FEE, 10);
        assert!(l2.bid_prices.is_empty(), "BID 已 cancel 清除");
        assert_eq!(conserved(&api, LTC), 0);
        assert_eq!(conserved(&api, XBT), 0);
    }

    // 对拍 testMakerBidCancelWithoutAnyFillFullyReleasesExchangeLocked。
    #[test]
    fn maker_bid_cancel_without_any_fill_fully_releases_exchange_locked() {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, C8_SCALE);
        api.add_currency(LTC, C8_SCALE);
        assert_eq!(api.add_symbol(xbt_ltc_fee()), CommandResultCode::Success);

        create_user_with_money(&mut api, UID_1, LTC, 100_000, 1);

        let price = 1_933;
        let bid_order_id = 60001;
        assert_eq!(api.place_order(spot_bid(bid_order_id, UID_1, SYM_FEE, price, 4, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, LTC), 153);

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: bid_order_id, uid: UID_1, symbol: SYM_FEE }), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, LTC), 0, "无成交 cancel 精确归零");
        assert_eq!(api.user_account(UID_1, LTC), 100_000);
        assert_eq!(available(&api, UID_1, LTC), 100_000);
        assert_eq!(conserved(&api, LTC), 0);
    }

    // ================================================================
    // 跨条线独立性
    // ================================================================

    // 对拍 testFuturesFillDoesNotAffectSpotLock：期货下单/成交不影响现货 exchangeLocked。
    #[test]
    fn futures_fill_does_not_affect_spot_lock() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 500 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, USDT, 100_000_000i64 * USDT_SCALE, 2);

        // 现货 BID 1 BNB @100 → 冻结 100 USDT。
        let spot_price = 100 * 100_000;
        assert_eq!(api.place_order(spot_bid(40001, UID_1, SYM_SPOT, spot_price, 1_000, OrderType::Gtc)), CommandResultCode::Success);
        let spot_lock = 100 * USDT_SCALE;
        assert_eq!(api.user_locked(UID_1, USDT), spot_lock);

        // UID_2 挂 ASK，UID_1 CROSS BID 撮合开仓。
        let fut_price = 500 * 100_000;
        assert_eq!(api.place_futures_order(fut(40002, UID_2, SYM_FUT, fut_price, 1_000, OrderAction::Ask, OrderType::Gtc, 1, MarginMode::Cross)), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(fut(40003, UID_1, SYM_FUT, fut_price, 1_000, OrderAction::Bid, OrderType::Ioc, 1, MarginMode::Cross)), CommandResultCode::Success);

        // 期货成交后现货 exchangeLocked 不变，现货挂单仍在。
        assert!(api.user_position(UID_1, SYM_FUT).is_some(), "期货持仓应开成");
        assert_eq!(api.user_locked(UID_1, USDT), spot_lock, "现货 lock 不受期货流程影响");
        let l2 = api.request_l2(SYM_SPOT, 10);
        assert_eq!(l2.bid_prices, vec![spot_price], "现货挂单仍在");
        assert_eq!(conserved(&api, USDT), 0);
        assert_eq!(conserved(&api, BNB), 0);
    }
}
