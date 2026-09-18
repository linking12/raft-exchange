// Java ITR2SyncOptimization 的 Rust 对拍翻译(防线①):资金费率结算的精确金额 + 零和守恒。
//
// Java 原测跑 2 分片(uid%2)验证跨分片资金费一致;Rust 单分片塌缩,funding 逐张费率与零和不变,
// 仍逐值对齐 Java 黄金值(多头每张付 1、空头每张收 1)。资金费 delta 只取决于仓位方向/张数,
// 与自由余额/杠杆无关,故此处放宽播种/杠杆以避开保证金 NSF,不影响被断言的 profit delta。
#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest};

    const XBT: i32 = 3762;
    const USD: i32 = 840;
    const SYM: i32 = 10001;
    const MARK: i64 = 10_000;

    fn btc_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: XBT,
            quote_currency: USD,
            base_scale_k: 100,
            quote_scale_k: 100,
            taker_fee: 0,
            maker_fee: 0,
            init_margin: 1,
            init_margin_scale_k: 100,
            maintenance_margin: BTreeMap::from([(1_000, 5)]),
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(1_000, 100)]),
            ..Default::default()
        }
    }

    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(btc_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM, MARK), CommandResultCode::Success);
        api
    }

    fn seed(api: &mut ExchangeApi, uid: i64, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, USD, amount, txid), CommandResultCode::Success);
    }

    fn place(api: &mut ExchangeApi, order_id: i64, uid: i64, size: i64, action: OrderAction) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: SYM,
            price: MARK,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage: 10,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        })
    }

    // uid `holder` 以 `size` 张开仓(bid=LONG / ask=SHORT),`taker` 反向吃单成交对手方。
    fn open(api: &mut ExchangeApi, holder_oid: i64, holder: i64, taker_oid: i64, taker: i64, size: i64, holder_bid: bool) {
        let (h_act, t_act) = if holder_bid { (OrderAction::Bid, OrderAction::Ask) } else { (OrderAction::Ask, OrderAction::Bid) };
        assert_eq!(place(api, holder_oid, holder, size, h_act), CommandResultCode::Success);
        assert_eq!(place(api, taker_oid, taker, size, t_act), CommandResultCode::Success);
        assert_eq!(api.user_position(holder, SYM).unwrap().open_volume, size, "holder position opened");
    }

    fn profit(api: &ExchangeApi, uid: i64) -> i64 {
        api.user_position(uid, SYM).map(|p| p.profit).unwrap_or(0)
    }

    // 对齐 Java testSettleFundingFees_TwoShards_Symmetric:
    // 多头 L1=10 张 / L2=5 张 付费,空头 S1=5 张 / S2=10 张 收费,费率 0.01%(1/10000)@ mark 10000。
    #[test]
    fn settle_funding_fees_symmetric() {
        let (l1, l2, s1, s2) = (101i64, 102, 201, 202);
        let (t1, t2, t3, t4) = (301i64, 302, 303, 304);

        let mut api = setup();
        for (i, uid) in [l1, l2, s1, s2].iter().enumerate() {
            seed(&mut api, *uid, 1_000_000, 10 + i as i64);
        }
        for (i, uid) in [t1, t2, t3, t4].iter().enumerate() {
            seed(&mut api, *uid, 100_000_000, 20 + i as i64);
        }

        open(&mut api, 1001, l1, 1002, t1, 10, true); // L1 LONG 10
        open(&mut api, 2001, s1, 2002, t2, 5, false); // S1 SHORT 5
        open(&mut api, 3001, l2, 3002, t3, 5, true); // L2 LONG 5
        open(&mut api, 4001, s2, 4002, t4, 10, false); // S2 SHORT 10

        let (l1b, l2b, s1b, s2b) = (profit(&api, l1), profit(&api, l2), profit(&api, s1), profit(&api, s2));

        // 多头付空头(action=BID),费率 1/10000
        assert_eq!(api.settle_funding_fees(SYM, OrderAction::Bid, 1, 10_000, 9999), CommandResultCode::Success);

        let (dl1, dl2, ds1, ds2) = (
            profit(&api, l1) - l1b,
            profit(&api, l2) - l2b,
            profit(&api, s1) - s1b,
            profit(&api, s2) - s2b,
        );

        assert_eq!(dl1, -10, "L1 (long 10) pays 10");
        assert_eq!(dl2, -5, "L2 (long 5) pays 5");
        assert_eq!(ds1, 5, "S1 (short 5) receives 5");
        assert_eq!(ds2, 10, "S2 (short 10) receives 10");
        assert_eq!(dl1 + dl2 + ds1 + ds2, 0, "named holders funding is zero-sum");
        assert_eq!(ds1 / 5, 1, "per-contract funding = 1 (short S1)");
        assert_eq!(ds2 / 10, 1, "per-contract funding = 1 (short S2)");
    }

    // 单持仓退化:一个多头 + 一个空头,等张数,funding 一进一出零和(对齐 Test 1.2 精神)。
    #[test]
    fn settle_funding_fees_single_pair() {
        let (long_u, short_u, taker) = (111i64, 211, 311);
        let mut api = setup();
        seed(&mut api, long_u, 1_000_000, 1);
        seed(&mut api, short_u, 1_000_000, 2);
        seed(&mut api, taker, 100_000_000, 3);

        // long 8 张:long_u BID 8,short_u ASK 8 直接对成(无需 taker)
        assert_eq!(place(&mut api, 1, short_u, 8, OrderAction::Ask), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, long_u, 8, OrderAction::Bid), CommandResultCode::Success);
        assert_eq!(api.user_position(long_u, SYM).unwrap().open_volume, 8);
        assert_eq!(api.user_position(short_u, SYM).unwrap().open_volume, 8);

        let (lb, sb) = (profit(&api, long_u), profit(&api, short_u));
        assert_eq!(api.settle_funding_fees(SYM, OrderAction::Bid, 1, 10_000, 9999), CommandResultCode::Success);
        let (dl, ds) = (profit(&api, long_u) - lb, profit(&api, short_u) - sb);

        assert_eq!(dl, -8, "long 8 pays 8");
        assert_eq!(ds, 8, "short 8 receives 8");
        assert_eq!(dl + ds, 0, "funding zero-sum");
    }
}
