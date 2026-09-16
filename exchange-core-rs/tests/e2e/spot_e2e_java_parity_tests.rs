//! 现货 E2E 行为对拍：镜像 client `LiveClusterSpotE2ETest` 的场景，直连引擎（不经 raft 集群），
//! 断言整体结果与 Java 一致（撮合方向、冻结释放、result code）。

#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{
        CancelOrderRequest, ExchangeApi, MoveOrderRequest, PlaceOrderRequest, ReduceOrderRequest,
    };

    const BASE: i32 = 1; // BTC
    const QUOTE: i32 = 2; // USDT
    const SYMBOL: i32 = 100; // BTC/USDT
    const SELLER: i64 = 1;
    const BUYER: i64 = 3;

    fn spot_spec(taker_fee: i64, maker_fee: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee,
            maker_fee,
            ..Default::default()
        }
    }

    /// 建两币 + 现货 symbol + seller/buyer，各自充值 base/quote。
    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec(2, 1)), CommandResultCode::Success);
        assert_eq!(api.add_user(SELLER), CommandResultCode::Success);
        assert_eq!(api.add_user(BUYER), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(SELLER, BASE, 1_000_000, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(BUYER, QUOTE, 10_000_000_000, 2), CommandResultCode::Success);
        api
    }

    fn bid(order_id: i64, uid: i64, price: i64, reserve: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL, price, size, reserve_bid_price: reserve, action: OrderAction::Bid, order_type: ot }
    }
    fn ask(order_id: i64, uid: i64, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL, price, size, reserve_bid_price: 0, action: OrderAction::Ask, order_type: ot }
    }
    fn acct(api: &ExchangeApi, uid: i64, cur: i32) -> i64 {
        api.ups().get(uid).unwrap().account(cur)
    }
    fn locked(api: &ExchangeApi, uid: i64, cur: i32) -> i64 {
        api.ups().get(uid).unwrap().locked(cur)
    }
    /// 全局某币守恒 = Σ用户 account + fees 池（现货费入 fees）。
    fn total_quote(api: &ExchangeApi) -> i64 {
        let user_sum: i64 = api.ups().users.values().map(|p| p.account(QUOTE)).sum();
        user_sum + api.risk().fees.get(&QUOTE).copied().unwrap_or(0)
    }

    // 1) 撮合更新双方余额（对拍 placeOrder_match_balancesUpdated）
    #[test]
    fn match_updates_balances_directionally_and_conserves() {
        let mut api = setup();
        let (buyer_quote0, seller_base0) = (acct(&api, BUYER, QUOTE), acct(&api, SELLER, BASE));
        let q_before = total_quote(&api);

        assert_eq!(api.place_order(ask(5001, SELLER, 20_000, 1, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(5002, BUYER, 20_000, 20_000, 1, OrderType::Gtc)), CommandResultCode::Success);

        assert!(acct(&api, BUYER, BASE) > 0, "买方应收到 BTC");
        assert!(acct(&api, BUYER, QUOTE) < buyer_quote0, "买方 USDT 应减少");
        assert!(acct(&api, SELLER, BASE) < seller_base0, "卖方 BTC 应减少");
        assert!(acct(&api, SELLER, QUOTE) > 0, "卖方应收到 USDT");
        assert_eq!(total_quote(&api), q_before, "QUOTE 全局守恒（含 fees 池）");
    }

    // 2) 撤单完全释放冻结（对拍 cancelOrder_restoresLockedFunds）
    #[test]
    fn cancel_restores_locked_funds() {
        let mut api = setup();
        let locked_before = locked(&api, BUYER, QUOTE);
        assert_eq!(api.place_order(bid(6001, BUYER, 10_000, 10_000, 1, OrderType::Gtc)), CommandResultCode::Success);
        assert!(locked(&api, BUYER, QUOTE) > locked_before, "挂单后 quote 应被冻结");
        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 6001, uid: BUYER, symbol: SYMBOL }), CommandResultCode::Success);
        assert_eq!(locked(&api, BUYER, QUOTE), locked_before, "撤单后冻结应完全释放");
    }

    // 3) 部分成交后撤余量释放剩余冻结（对拍 partialFill…cancel）
    #[test]
    fn partial_fill_then_cancel_releases_remaining() {
        let mut api = setup();
        assert_eq!(api.place_order(ask(7001, SELLER, 20_000, 1, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(7002, BUYER, 20_000, 20_000, 3, OrderType::Gtc)), CommandResultCode::Success);
        let locked_after_partial = locked(&api, BUYER, QUOTE);
        assert!(locked_after_partial > 0, "剩余 2 手应仍在冻结中");
        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 7002, uid: BUYER, symbol: SYMBOL }), CommandResultCode::Success);
        assert!(locked(&api, BUYER, QUOTE) < locked_after_partial, "撤余量应释放冻结");
    }

    // 4) IOC 无对手不动余额/冻结（对拍 iocOrder_noCounterparty）
    #[test]
    fn ioc_no_counterparty_no_change() {
        let mut api = setup();
        let (bal, lk) = (acct(&api, BUYER, QUOTE), locked(&api, BUYER, QUOTE));
        // 远低于任何卖盘（簿上无卖盘）→ 无成交
        assert_eq!(api.place_order(bid(8001, BUYER, 2_000, 2_000, 1, OrderType::Ioc)), CommandResultCode::Success);
        assert_eq!(acct(&api, BUYER, QUOTE), bal, "IOC 未成交不应动余额");
        assert_eq!(locked(&api, BUYER, QUOTE), lk, "IOC 未成交不应留下冻结");
    }

    // 5) 超余额下单返回 RISK_NSF（对拍 oversizedOrder_rejected）
    #[test]
    fn oversized_order_rejected_risk_nsf() {
        let mut api = setup();
        assert_eq!(
            api.place_order(bid(9001, BUYER, 20_000, 20_000, 1_000_000_000, OrderType::Gtc)),
            CommandResultCode::RiskNsf,
            "超出余额应返回 RISK_NSF"
        );
    }

    // 6) 提现减少余额（对拍 withdraw_decreasesBalance）
    #[test]
    fn withdraw_decreases_balance() {
        let mut api = setup();
        let before = acct(&api, BUYER, QUOTE);
        assert_eq!(api.balance_adjustment(BUYER, QUOTE, -1_000, 100), CommandResultCode::Success);
        assert_eq!(acct(&api, BUYER, QUOTE), before - 1_000, "提现应精确减少余额");
    }

    // 7) 改价在 reserve 内不变冻结、越 reserve 被风控拒（对拍 moveOrder_repricing_adjustsLock）
    #[test]
    fn move_within_reserve_keeps_lock_over_reserve_rejected() {
        let mut api = setup();
        // 现货 BID 冻结 = size × reserveBidPrice（+费），与 price 无关。
        assert_eq!(api.place_order(bid(1001, BUYER, 10_000, 14_000, 1, OrderType::Gtc)), CommandResultCode::Success);
        let lock_at_place = locked(&api, BUYER, QUOTE);
        assert!(lock_at_place > 0);

        assert_eq!(api.move_order(MoveOrderRequest { order_id: 1001, uid: BUYER, symbol: SYMBOL, new_price: 12_000 }), CommandResultCode::Success);
        assert_eq!(locked(&api, BUYER, QUOTE), lock_at_place, "reserve 内改价不应改变冻结");

        assert_eq!(
            api.move_order(MoveOrderRequest { order_id: 1001, uid: BUYER, symbol: SYMBOL, new_price: 18_000 }),
            CommandResultCode::MatchingMoveFailedPriceOverRiskLimit,
            "越 reserveBidPrice 改价应被风控拒"
        );
    }

    // 8) 减量释放对应冻结（对拍 reduceOrder_releasesLock）
    #[test]
    fn reduce_releases_lock() {
        let mut api = setup();
        assert_eq!(api.place_order(bid(1101, BUYER, 10_000, 10_000, 2, OrderType::Gtc)), CommandResultCode::Success);
        let lock_full = locked(&api, BUYER, QUOTE);
        assert_eq!(api.reduce_order(ReduceOrderRequest { order_id: 1101, uid: BUYER, symbol: SYMBOL, reduce_size: 1 }), CommandResultCode::Success);
        assert!(locked(&api, BUYER, QUOTE) < lock_full, "减量应释放对应冻结");
    }

    // 9) FOK 无足量流动性则完全不成交（对拍 fok…noLiquidity）
    #[test]
    fn fok_no_full_liquidity_no_fill() {
        let mut api = setup();
        assert_eq!(api.place_order(ask(1201, SELLER, 20_000, 1, OrderType::Gtc)), CommandResultCode::Success); // 仅 1 手卖盘
        let (base_before, quote_before) = (acct(&api, BUYER, BASE), acct(&api, BUYER, QUOTE));
        // FOK 买 3 手（簿上仅 1 手）→ 全或无 → 完全不成交
        api.place_order(bid(1202, BUYER, 20_000, 20_000, 3, OrderType::Fok));
        assert_eq!(acct(&api, BUYER, BASE), base_before, "FOK 未全额成交不应有任何成交");
        assert_eq!(acct(&api, BUYER, QUOTE), quote_before, "FOK 未成交不应动余额");
        assert_eq!(locked(&api, BUYER, QUOTE), 0, "FOK 未成交不应留下冻结");
    }

    // 10) 未成交 IOC ASK 立即释放 base 冻结,连下不误拒。
    //     差分模糊抓到、并经 Java 侧 ITIocAskLockRelease 定性:Java 的**批处理 R1/R2 时序 hazard**——
    //     第一条未成交 IOC ASK 的 R2 锁释放滞后于第二条 R1 读,无 barrier 时第二条 spurious RISK_NSF
    //     (Java 引擎释放逻辑本身正确、settle 后归零;属 Disruptor 已知特性,用 barrier 规避)。
    //     Rust 单管线 R2 恒先于下条 R1,无此 hazard——下面两条连提都成功。
    #[test]
    fn unfilled_ioc_ask_releases_base_lock_no_leak() {
        let mut api = setup(); // SELLER 有 1_000_000 base
        let base0 = acct(&api, SELLER, BASE);
        // 空簿连下两个 IOC ASK:都未成交,都应 SUCCESS 且各自完全释放冻结。
        assert_eq!(api.place_order(ask(3001, SELLER, 20_000, 600_000, OrderType::Ioc)), CommandResultCode::Success);
        assert_eq!(locked(&api, SELLER, BASE), 0, "第一个未成交 IOC ASK 应释放全部 base 冻结");
        assert_eq!(api.place_order(ask(3002, SELLER, 20_000, 600_000, OrderType::Ioc)), CommandResultCode::Success, "锁已释放,第二个不应 NSF(Java 此处会因泄漏锁误拒)");
        assert_eq!(locked(&api, SELLER, BASE), 0);
        assert_eq!(acct(&api, SELLER, BASE), base0, "未成交不动余额");
    }
}
