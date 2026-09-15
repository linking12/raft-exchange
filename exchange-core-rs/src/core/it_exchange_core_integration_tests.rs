//! 翻译自 Java `exchange.core2.tests.integration.ITExchangeCoreIntegration`。
//! 直连引擎（无 raft/Disruptor），断言 API result code + 订单态 + L2 簿 + matcher event 具体值，
//! 逐条对拍 Java 黄金值，验证行为一致。字段映射：Java matchedOrderId→maker_order_id、
//! matchedOrderCompleted→maker_order_completed、matchedOrderUid→matched_order_uid。

#[cfg(test)]
mod tests {
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::matcher_event_type::MatcherEventType;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::exchange_api::{ExchangeApi, MoveOrderRequest, PlaceOrderRequest};

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const UID_1: i64 = 1;
    const UID_2: i64 = 2;

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
}
