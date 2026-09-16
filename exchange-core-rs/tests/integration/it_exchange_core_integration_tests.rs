//! 翻译自 Java `exchange.core2.tests.integration.ITExchangeCoreIntegration`（7 个 @Test）。
//! 直连引擎（无 raft/Disruptor），断言 API result code + 订单态 + L2 簿 + matcher event 具体值，
//! 逐条对拍 Java 黄金值，验证行为一致。字段映射：Java matchedOrderId→maker_order_id、
//! matchedOrderCompleted→maker_order_completed、matchedOrderUid→matched_order_uid。
//!
//! **已翻译（4/7）**：`basicFullCycleTestExchange`→`basic_full_cycle_exchange`、
//! `exchangeRiskBasicTest`→`exchange_risk_basic_nsf_then_accept`、`exchangeCancelBid`→`exchange_cancel_bid`
//! （REDUCE 事件 + bidder_hold_price + 全额释放）、`exchangeRiskMoveTest`→`exchange_risk_move`
//! （移价 ASK/BID 越 reserve 拒 + 撮合 TRADE 逐字段 + cancel REDUCE）。
//! `exchange_*` 两个用 Java `initBasicSymbols` 的 SYMBOL_EXCHANGE(9269) 规格：base=ETH(3928,digit0→scale1)、
//! quote=XBT(3762,digit1→scale10)、baseScaleK=quoteScaleK=1、fee=0。BID 冻结 = size×reserve×quoteScaleK×
//! quote_currency_scale_k（×10 来自 XBT digit1）。
//!
//! **未翻译（3/7，非现货撮合核心）**：`basicFullCycleTestMargin`（期货全周期，见 `it_future_basic_tests`）、
//! `shouldInitSymbols`/`shouldInitUsers`（仅验证 addSymbols/addUsers 批量注册计数——本 harness 逐个
//! add_currency/add_symbol/add_user 已在每个 setup 中隐式覆盖，无独立批量注册命令可对拍）。

#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::matcher_event_type::MatcherEventType;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{
        CancelOrderRequest, ExchangeApi, MoveOrderRequest, PlaceOrderRequest,
    };

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const UID_1: i64 = 1;
    const UID_2: i64 = 2;

    // Java initBasicSymbols 的 SYMBOL_EXCHANGE（ETH/XBT）——见文件头。
    const SYMBOL_EX: i32 = 9269;
    const ETH: i32 = 3928;
    const XBT: i32 = 3762;

    /// initBasicSymbols：add_currency(ETH,digit0→1) + add_currency(XBT,digit1→10) + SYMBOL_EXCHANGE(9269)。
    fn setup_exchange() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(ETH, 1);
        api.add_currency(XBT, 10);
        let spec = CoreSymbolSpecification {
            symbol_id: SYMBOL_EX,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: ETH,
            quote_currency: XBT,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            ..Default::default()
        };
        assert_eq!(api.add_symbol(spec), CommandResultCode::Success);
        api
    }
    fn ex_ask(order_id: i64, uid: i64, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL_EX, price, size, reserve_bid_price: 0, action: OrderAction::Ask, order_type: ot }
    }
    fn ex_bid(order_id: i64, uid: i64, price: i64, reserve: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL_EX, price, size, reserve_bid_price: reserve, action: OrderAction::Bid, order_type: ot }
    }

    fn spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            ..Default::default()
        }
    }

    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec()), CommandResultCode::Success);
        for uid in [UID_1, UID_2] {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, BASE, 1_000_000, uid * 10), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, QUOTE, 100_000_000, uid * 10 + 1), CommandResultCode::Success);
        }
        api
    }

    fn bid(order_id: i64, uid: i64, price: i64, reserve: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL, price, size, reserve_bid_price: reserve, action: OrderAction::Bid, order_type: ot }
    }
    fn ask(order_id: i64, uid: i64, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL, price, size, reserve_bid_price: 0, action: OrderAction::Ask, order_type: ot }
    }

    fn assert_l2(api: &mut ExchangeApi, asks: &[(i64, i64)], bids: &[(i64, i64)]) {
        let l2 = api.request_l2(SYMBOL, 10);
        assert_eq!(l2.ask_prices, asks.iter().map(|x| x.0).collect::<Vec<_>>(), "ask prices");
        assert_eq!(l2.ask_volumes, asks.iter().map(|x| x.1).collect::<Vec<_>>(), "ask volumes");
        assert_eq!(l2.bid_prices, bids.iter().map(|x| x.0).collect::<Vec<_>>(), "bid prices");
        assert_eq!(l2.bid_volumes, bids.iter().map(|x| x.1).collect::<Vec<_>>(), "bid volumes");
    }

    fn total(api: &ExchangeApi, cur: i32) -> i64 {
        api.ups().users.values().map(|p| p.account(cur)).sum::<i64>() + api.risk().fees.get(&cur).copied().unwrap_or(0)
    }

    // 对拍 basicFullCycleTest（exchange 变体）：下单/撮合/移价全周期 + matcher event 具体值 + L2 簿 + 守恒。
    #[test]
    fn basic_full_cycle_exchange() {
        let mut api = setup();
        let (base0, quote0) = (total(&api, BASE), total(&api, QUOTE));

        // ① UID_1 挂 ASK 101 (1600×7) 与 BID 102 (1550×4, reserve 1561)
        assert_eq!(api.place_order(ask(101, UID_1, 1600, 7, OrderType::Gtc)), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none(), "101 挂单不撮合");
        assert_eq!(api.place_order(bid(102, UID_1, 1550, 1561, 4, OrderType::Gtc)), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none(), "102 挂单不撮合");
        assert_l2(&mut api, &[(1600, 7)], &[(1550, 4)]);

        // ② UID_2 IOC BID 201 (1700×2, reserve 1800) → 撮 101 部分成交 2@1600
        assert_eq!(api.place_order(bid(201, UID_2, 1700, 1800, 2, OrderType::Ioc)), CommandResultCode::Success);
        {
            let ev = api.last_matcher_event().expect("201 应有 1 条 TRADE");
            assert!(ev.next.is_none(), "仅 1 条撮合事件");
            assert_eq!(ev.event_type, MatcherEventType::Trade);
            assert!(ev.active_order_completed, "taker 201 全成交(2/2)");
            assert_eq!(ev.maker_order_id, 101, "对手 = 101");
            assert_eq!(ev.matched_order_uid, UID_1);
            assert!(!ev.maker_order_completed, "maker 101 未全成交(剩 5)");
            assert_eq!(ev.size, 2);
            assert_eq!(ev.price, 1600);
        }
        assert_l2(&mut api, &[(1600, 5)], &[(1550, 4)]);

        // ③ UID_2 GTC BID 202 (1583×4) 挂单不撮合
        assert_eq!(api.place_order(bid(202, UID_2, 1583, 1583, 4, OrderType::Gtc)), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none());
        assert_l2(&mut api, &[(1600, 5)], &[(1583, 4), (1550, 4)]);

        // ④ UID_1 移价 101 → 1580，撮 202（部分：101 剩 5 卖 4，202 全成交 4@1583）
        assert_eq!(api.move_order(MoveOrderRequest { order_id: 101, uid: UID_1, symbol: SYMBOL, new_price: 1580 }), CommandResultCode::Success);
        {
            let ev = api.last_matcher_event().expect("移价应有 1 条 TRADE");
            assert!(ev.next.is_none());
            assert_eq!(ev.event_type, MatcherEventType::Trade);
            assert!(!ev.active_order_completed, "101 未全成交(剩 1)");
            assert_eq!(ev.maker_order_id, 202);
            assert_eq!(ev.matched_order_uid, UID_2);
            assert!(ev.maker_order_completed, "maker 202 全成交");
            assert_eq!(ev.size, 4);
            assert_eq!(ev.price, 1583);
        }
        assert_l2(&mut api, &[(1580, 1)], &[(1550, 4)]);

        // ⑤ 守恒（0 费）：各币全局总额不变
        assert_eq!(total(&api, BASE), base0, "BASE 守恒");
        assert_eq!(total(&api, QUOTE), quote0, "QUOTE 守恒");
    }

    // 对拍 exchangeRiskBasicTest：余额不足拒单(RISK_NSF) → 补款 → 成功挂单，冻结 = size×reservePrice。
    #[test]
    fn exchange_risk_basic_nsf_then_accept() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec()), CommandResultCode::Success);
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        // 仅存 QUOTE 200_000：BID 7×30000=210000 → 不足
        assert_eq!(api.balance_adjustment(UID_1, QUOTE, 200_000, 1), CommandResultCode::Success);

        assert_eq!(
            api.place_order(bid(101, UID_1, 30_000, 30_000, 7, OrderType::Gtc)),
            CommandResultCode::RiskNsf,
            "余额不足应 RISK_NSF"
        );
        assert_eq!(api.ups().get(UID_1).unwrap().account(QUOTE), 200_000, "拒单后余额不变");
        assert_eq!(api.ups().get(UID_1).unwrap().locked(QUOTE), 0, "拒单后无冻结");

        // 补 100_000 → 300_000，可挂 210_000
        assert_eq!(api.balance_adjustment(UID_1, QUOTE, 100_000, 2), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(101, UID_1, 30_000, 30_000, 7, OrderType::Gtc)), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none());
        // 现货下单不扣 accounts，把 size×reservePrice 计入 exchangeLocked
        assert_eq!(api.ups().get(UID_1).unwrap().account(QUOTE), 300_000, "accounts 不变");
        assert_eq!(api.ups().get(UID_1).unwrap().locked(QUOTE), 210_000, "冻结 = 7×30000");
    }

    // 对拍 exchangeCancelBid：BID 下单冻结 quote（不扣 accounts）→ 撤单发 REDUCE 事件（bidder_hold_price/size）+ 全额释放。
    #[test]
    fn exchange_cancel_bid() {
        let mut api = setup_exchange();
        assert_eq!(api.add_user(UID_2), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_2, XBT, 94_000_000, 1), CommandResultCode::Success);

        assert_eq!(api.place_order(ex_bid(203, UID_2, 18_000, 18_500, 500, OrderType::Gtc)), CommandResultCode::Success);
        // BID 不扣 accounts，size×reserve×quoteScaleK(1)×quote_currency_scale_k(10) 计入 locked。
        let xbt_lock = 10 * 18_500 * 500;
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 94_000_000, "accounts 不变");
        assert_eq!(api.ups().get(UID_2).unwrap().locked(XBT), xbt_lock, "BID 冻结");

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 203, uid: UID_2, symbol: SYMBOL_EX }), CommandResultCode::Success);
        {
            let ev = api.last_matcher_event().expect("撤单应有 REDUCE 事件");
            assert_eq!(ev.event_type, MatcherEventType::Reduce);
            assert_eq!(ev.bidder_hold_price, 18_500);
            assert_eq!(ev.size, 500);
        }
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 94_000_000, "撤单后 94M 全回");
        assert_eq!(api.ups().get(UID_2).unwrap().locked(XBT), 0, "撤单全额释放冻结");
        assert!(api.total_balance().is_global_zero());
    }

    // 对拍 exchangeRiskMoveTest：ASK 移价自由（越/降均可）+ BID 越 reserve 拒 / 等 reserve 收 + 移价触发撮合 TRADE 逐字段 + cancel REDUCE。
    // （Java 首步先挂 size=1001 再重挂同 id size=1000 的重复 orderId 桥段语义可疑、且非本测试要点，此处直接挂 1000。）
    #[test]
    fn exchange_risk_move() {
        let mut api = setup_exchange();
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, ETH, 100_000_000, 1), CommandResultCode::Success);

        // UID_1 ASK 202：price 30000 size 1000（base 冻结 = size×baseScaleK(1)×base_currency_scale_k(1) = 1000）。
        assert_eq!(api.place_order(ex_ask(202, UID_1, 30_000, 1000, OrderType::Gtc)), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none());
        assert_eq!(api.ups().get(UID_1).unwrap().account(ETH), 100_000_000, "ASK 不扣 accounts");
        assert_eq!(api.ups().get(UID_1).unwrap().locked(ETH), 1000, "ASK 冻结 base = size");

        // ASK 移价到更高 40000（对 ASK 无风控问题）、再到更低 20000。
        assert_eq!(api.move_order(MoveOrderRequest { order_id: 202, uid: UID_1, symbol: SYMBOL_EX, new_price: 40_000 }), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none(), "移价不撮合");
        assert_eq!(api.move_order(MoveOrderRequest { order_id: 202, uid: UID_1, symbol: SYMBOL_EX, new_price: 20_000 }), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none());
        assert_eq!(api.ups().get(UID_1).unwrap().account(ETH), 100_000_000);

        // UID_2 XBT 94M。
        assert_eq!(api.add_user(UID_2), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_2, XBT, 94_000_000, 2), CommandResultCode::Success);

        // BID 203 reserve 19000 → 冻结 = 10×19000×500 = 95M > 94M → RISK_NSF。
        assert_eq!(
            api.place_order(ex_bid(203, UID_2, 18_000, 19_000, 500, OrderType::Gtc)),
            CommandResultCode::RiskNsf,
            "reserve 越资金上限应 RISK_NSF"
        );
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 94_000_000);
        assert_eq!(api.ups().get(UID_2).unwrap().locked(XBT), 0, "拒单无冻结");

        // BID 203 reserve 18500 → 冻结 = 10×18500×500 = 92.5M < 94M → 成功。
        assert_eq!(api.place_order(ex_bid(203, UID_2, 18_000, 18_500, 500, OrderType::Gtc)), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none());
        let xbt_lock = 10 * 18_500 * 500;
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 94_000_000, "BID 不扣 accounts");
        assert_eq!(api.ups().get(UID_2).unwrap().locked(XBT), xbt_lock, "BID 冻结 92.5M");

        // BID 移价更低 15000（无风控问题）。
        assert_eq!(api.move_order(MoveOrderRequest { order_id: 203, uid: UID_2, symbol: SYMBOL_EX, new_price: 15_000 }), CommandResultCode::Success);
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 94_000_000);

        // BID 移价更高 18501（越 reserve 18500）→ 风控拒。
        assert_eq!(
            api.move_order(MoveOrderRequest { order_id: 203, uid: UID_2, symbol: SYMBOL_EX, new_price: 18_501 }),
            CommandResultCode::MatchingMoveFailedPriceOverRiskLimit,
            "越 reserveBidPrice 改价应被风控拒"
        );

        // BID 移价 = reserve 18500 → 收；再到 17500。
        assert_eq!(api.move_order(MoveOrderRequest { order_id: 203, uid: UID_2, symbol: SYMBOL_EX, new_price: 18_500 }), CommandResultCode::Success);
        assert_eq!(api.move_order(MoveOrderRequest { order_id: 203, uid: UID_2, symbol: SYMBOL_EX, new_price: 17_500 }), CommandResultCode::Success);
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 94_000_000);

        // UID_1 ASK 202 移价到 16900 → 触发撮合（maker=203@17500 全成交 500）。
        assert_eq!(api.move_order(MoveOrderRequest { order_id: 202, uid: UID_1, symbol: SYMBOL_EX, new_price: 16_900 }), CommandResultCode::Success);
        {
            let ev = api.last_matcher_event().expect("移价触发撮合应有 TRADE");
            assert_eq!(ev.event_type, MatcherEventType::Trade);
            assert!(!ev.active_order_completed, "taker 202 未全成交（剩 500）");
            assert_eq!(ev.maker_order_id, 203, "对手 = 203");
            assert_eq!(ev.matched_order_uid, UID_2);
            assert!(ev.maker_order_completed, "maker 203 全成交");
            assert_eq!(ev.price, 17_500, "成交价 = maker 挂单价");
            assert_eq!(ev.bidder_hold_price, 18_500, "bidder 原始 reserve 价");
            assert_eq!(ev.size, 500);
        }
        // UID_1：quote XBT = 17500×10×500 = 87.5M；base ETH = 100M − 500（已交割），剩 500 在 locked。
        assert_eq!(api.ups().get(UID_1).unwrap().account(XBT), 87_500_000);
        assert_eq!(api.ups().get(UID_1).unwrap().account(ETH), 99_999_500);
        assert_eq!(api.ups().get(UID_1).unwrap().locked(ETH), 500, "剩余 500 待撤/撮合");
        // UID_2：XBT = 94M − 87.5M = 6.5M；base ETH = 500。
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 6_500_000);
        assert_eq!(api.ups().get(UID_2).unwrap().account(ETH), 500);

        // cancel 剩余 ASK 202 → REDUCE 事件 size 500。
        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 202, uid: UID_1, symbol: SYMBOL_EX }), CommandResultCode::Success);
        {
            let ev = api.last_matcher_event().expect("撤单应有 REDUCE");
            assert_eq!(ev.event_type, MatcherEventType::Reduce);
            assert_eq!(ev.size, 500);
        }
        assert_eq!(api.ups().get(UID_1).unwrap().account(XBT), 87_500_000);
        assert_eq!(api.ups().get(UID_1).unwrap().account(ETH), 99_999_500);
        assert_eq!(api.ups().get(UID_1).unwrap().locked(ETH), 0, "撤单释放剩余冻结");
        assert!(api.total_balance().is_global_zero());
    }
}
