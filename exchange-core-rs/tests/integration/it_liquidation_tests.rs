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
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::fund_event::{FundEvent, FundEventType};

    const XBT: i32 = 3762;
    const USD: i32 = 840;
    const ETH: i32 = 3928;
    const BTC_SYM: i32 = 10000;
    const ETH_SYM: i32 = 10001;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn leverage_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    fn btc_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: BTC_SYM,
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

    fn eth_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: ETH_SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: ETH,
            quote_currency: USD,
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

    fn setup_btc(entry: i64) -> ExchangeApi {
        setup_btc_with_collector(entry).0
    }

    fn setup_btc_with_collector(entry: i64) -> (ExchangeApi, std::rc::Rc<std::cell::RefCell<Vec<FundEvent>>>) {
        let collector: std::rc::Rc<std::cell::RefCell<Vec<FundEvent>>> = std::rc::Rc::new(std::cell::RefCell::new(Vec::new()));
        let mut api = ExchangeApi::new();
        api.core().with_results_consumer(Box::new(crate::common::FundEventCollector(collector.clone())));
        api.add_currency(XBT, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(btc_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, entry), CommandResultCode::Success);
        (api, collector)
    }

    fn setup_two(entry: i64) -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(USD, 1);
        api.add_currency(ETH, 1);
        assert_eq!(api.add_futures_symbol(btc_spec()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, entry), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, entry), CommandResultCode::Success);
        api
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

    fn assert_conserved(api: &ExchangeApi) {
        let tcb = api.total_balance();
        assert!(tcb.is_global_zero(), "global conservation broken: {:?}", tcb.global_balances_sum());
    }

    fn open_volume(api: &ExchangeApi, uid: i64, sym: i32) -> i64 {
        api.user_position(uid, sym).map(|p| p.open_volume).unwrap_or(0)
    }

    fn collect_cascade_on_mark(api: &mut ExchangeApi, collector: &std::rc::Rc<std::cell::RefCell<Vec<FundEvent>>>, symbol: i32, price: i64) -> Vec<FundEvent> {
        collector.borrow_mut().clear();
        assert_eq!(api.set_mark_price(symbol, price), CommandResultCode::Success);
        collector.borrow().clone()
    }

    #[test]
    fn basic_liquidation_isolated_long() {
        let (trader, lp) = (1001i64, 2001i64);
        let position_size = 10i64;
        let entry = 10_000i64;
        let bp_fill = 9_920i64;
        let liq_price = 500i64;

        let (mut api, cascade_collector) = setup_btc_with_collector(entry);
        seed_user(&mut api, trader, 3_000, 1);
        seed_user(&mut api, lp, 100_000, 2);

        assert_eq!(place(&mut api, 10001, trader, BTC_SYM, entry, position_size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 10002, lp, BTC_SYM, entry, position_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, trader, BTC_SYM), position_size);

        let liquidity = position_size + 15;
        assert_eq!(place(&mut api, 10003, lp, BTC_SYM, bp_fill, liquidity, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        let cascade = collect_cascade_on_mark(&mut api, &cascade_collector, BTC_SYM, liq_price);

        assert!(api.user_position(trader, BTC_SYM).is_none(), "trader position should be fully closed");
        assert_eq!(api.user_account(trader, USD), 1_900, "liquidated party loses isolated margin + fees paid, 1900 remains");
        assert_eq!(api.fees(USD), 600, "maker/taker fees from both open and liquidation fills go entirely into the pool");
        let if_available: i64 = api.insurance_fund().futures.values().map(|e| e.available).sum();
        assert_eq!(if_available, 0, "no liquidation_fee configured -> IF available should not increase");
        assert_eq!(api.user_account(lp, USD), 99_700, "LP counterparty net settlement");
        assert_eq!(api.user_position(lp, BTC_SYM).unwrap().pending_buy_size, liquidity - position_size, "lp liquidity consumed by 10");
        let seq: Vec<(FundEventType, i64)> = cascade.iter().map(|e| (e.event_type, e.uid)).collect();
        assert_eq!(seq, vec![
            (FundEventType::LiquidationAlert, trader),
            (FundEventType::LiquidationClose, trader),
            (FundEventType::PnlSettlement, trader),
            (FundEventType::UnlockPending, lp),
            (FundEventType::ClosePosition, lp),
            (FundEventType::LiquidationFee, trader),
        ], "liquidation cascade event stream (type + uid)");
        let pnl = cascade.iter().find(|e| e.event_type == FundEventType::PnlSettlement && e.uid == trader).unwrap();
        assert_eq!(pnl.free, 1_900, "PnlSettlement event free should equal the final account balance");
        assert_conserved(&api);
    }

    #[test]
    fn targeted_liquidation_by_mark_price_drop_isolated() {
        let (trader, lp) = (1101i64, 2101i64);
        let size = 10i64;
        let entry = 10_000i64;
        let bp_fill = 9_920i64;
        let liq_price = 500i64;

        let mut api = setup_btc(entry);
        seed_user(&mut api, trader, 3_000, 1);
        seed_user(&mut api, lp, 100_000, 2);

        assert_eq!(place(&mut api, 110001, trader, BTC_SYM, entry, size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 110002, lp, BTC_SYM, entry, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, trader, BTC_SYM), size);

        assert_eq!(place(&mut api, 110003, lp, BTC_SYM, bp_fill, size + 15, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, liq_price), CommandResultCode::Success);

        assert!(api.user_position(trader, BTC_SYM).is_none(), "targeted path should complete isolated liquidation");
        assert_conserved(&api);
    }

    #[test]
    fn short_position_liquidation() {
        let (trader, lp) = (1003i64, 2003i64);
        let size = 6i64;
        let entry = 5_000i64;
        let bp_fill = 5_030i64;
        let liq_price = 12_000i64;

        let mut api = setup_btc(entry);
        seed_user(&mut api, trader, 8_000, 1);
        seed_user(&mut api, lp, 100_000, 2);

        assert_eq!(place(&mut api, 30001, trader, BTC_SYM, entry, size, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 30002, lp, BTC_SYM, entry, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, trader, BTC_SYM), size);

        let liquidity = size + 13;
        assert_eq!(place(&mut api, 30003, lp, BTC_SYM, bp_fill, liquidity, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, liq_price), CommandResultCode::Success);

        assert!(api.user_position(trader, BTC_SYM).is_none(), "short trader position should be fully closed");
        assert!(api.user_position(lp, BTC_SYM).unwrap().pending_sell_size < liquidity, "lp ask liquidity should be consumed");
        assert_conserved(&api);
    }

    #[test]
    fn partial_liquidation_reduces_position() {
        let (trader, lp) = (1004i64, 2004i64);
        let position_size = 20i64;
        let entry = 8_000i64;
        let partial_liq_price = 6_000i64;

        let mut api = setup_btc(entry);
        seed_user(&mut api, trader, 15_000, 1);
        seed_user(&mut api, lp, 100_000, 2);

        assert_eq!(place(&mut api, 40001, trader, BTC_SYM, entry, position_size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 40002, lp, BTC_SYM, entry, position_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, trader, BTC_SYM), position_size);

        let liquidity = position_size / 2;
        assert_eq!(place(&mut api, 40003, lp, BTC_SYM, partial_liq_price, liquidity, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, partial_liq_price), CommandResultCode::Success);

        assert!(open_volume(&api, trader, BTC_SYM) < position_size, "position should be reduced after liquidation");
        assert_conserved(&api);
    }

    #[test]
    fn multiple_users_liquidation() {
        let traders = [1005i64, 1006, 1007, 1008, 1009];
        let lp = 2005i64;
        let position_size = 4i64;
        let entry = 9_000i64;
        let liq_price = 2_500i64;

        let mut api = setup_btc(entry);
        for (i, &t) in traders.iter().enumerate() {
            seed_user(&mut api, t, 6_000, (i + 1) as i64);
        }
        seed_user(&mut api, lp, 200_000, 100);

        let mut oid = 50001i64;
        for &t in &traders {
            assert_eq!(place(&mut api, oid, t, BTC_SYM, entry, position_size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
            oid += 1;
            assert_eq!(place(&mut api, oid, lp, BTC_SYM, entry, position_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
            oid += 1;
            assert_eq!(open_volume(&api, t, BTC_SYM), position_size);
        }

        let liquidity = traders.len() as i64 * position_size + 5;
        assert_eq!(place(&mut api, oid, lp, BTC_SYM, liq_price, liquidity, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, liq_price), CommandResultCode::Success);

        for &t in &traders {
            assert!(api.user_position(t, BTC_SYM).is_none(), "trader {t} should be fully liquidated");
        }
        assert_conserved(&api);
    }

    #[test]
    fn multi_user_liquidation_liquidity_consumed() {
        let users = [1001i64, 1002, 1003, 1004, 1005, 1006];
        let (lp1, lp2) = (2001i64, 2002i64);
        let size = 5i64;
        let entry = 10_000i64;
        let bp_fill = 9_920i64;
        let liq_price = 2_000i64;

        let mut api = setup_btc(entry);
        for (i, &u) in users.iter().enumerate() {
            seed_user(&mut api, u, 5_000, (i + 1) as i64);
        }
        seed_user(&mut api, lp1, 1_000_000, 100);
        seed_user(&mut api, lp2, 1_000_000, 101);

        let mut oid = 10001i64;
        for &u in &users {
            assert_eq!(place(&mut api, oid, u, BTC_SYM, entry, size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
            oid += 1;
            assert_eq!(place(&mut api, oid, lp1, BTC_SYM, entry, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
            oid += 1;
        }
        for &u in &users {
            assert_eq!(open_volume(&api, u, BTC_SYM), size, "user {u} should have an open position");
        }

        let liquidity = 50i64;
        assert_eq!(place(&mut api, oid, lp2, BTC_SYM, bp_fill, liquidity, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, liq_price), CommandResultCode::Success);

        for &u in &users {
            assert!(api.user_position(u, BTC_SYM).is_none(), "user {u} should be fully liquidated");
        }
        assert!(api.user_position(lp2, BTC_SYM).unwrap().pending_buy_size < liquidity, "lp2 liquidity should be consumed");
        assert_conserved(&api);
    }

    #[test]
    fn mixed_margin_mode_isolated_liquidated_cross_survives() {
        let (cross1, cross2, iso1, iso2) = (4001i64, 4002i64, 4003i64, 4004i64);
        let lp = 9001i64;
        let size = 3i64;
        let entry = 10_000i64;
        let bp_fill = 9_920i64;
        let liq_price = 8_000i64;

        let mut api = setup_btc(entry);
        seed_user(&mut api, cross1, 50_000, 1);
        seed_user(&mut api, cross2, 50_000, 2);
        seed_user(&mut api, iso1, 50_000, 3);
        seed_user(&mut api, iso2, 50_000, 4);
        seed_user(&mut api, lp, 1_000_000, 5);

        let mut oid = 70001i64;
        for (u, mm) in [(cross1, MarginMode::Cross), (cross2, MarginMode::Cross), (iso1, MarginMode::Isolated), (iso2, MarginMode::Isolated)] {
            assert_eq!(place(&mut api, oid, u, BTC_SYM, entry, size, OrderAction::Bid, mm), CommandResultCode::Success);
            oid += 1;
            assert_eq!(place(&mut api, oid, lp, BTC_SYM, entry, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
            oid += 1;
            assert_eq!(open_volume(&api, u, BTC_SYM), size);
        }

        let liquidity = 4 * size + 10;
        assert_eq!(place(&mut api, oid, lp, BTC_SYM, bp_fill, liquidity, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, liq_price), CommandResultCode::Success);

        assert!(api.user_position(iso1, BTC_SYM).is_none(), "isolated iso1 should be liquidated");
        assert!(api.user_position(iso2, BTC_SYM).is_none(), "isolated iso2 should be liquidated");
        assert_eq!(open_volume(&api, cross1, BTC_SYM), size, "cross cross1 should not be liquidated");
        assert_eq!(open_volume(&api, cross2, BTC_SYM), size, "cross cross2 should not be liquidated");
        assert_conserved(&api);
    }

    #[test]
    fn cross_margin_liquidation_reduces_total_volume() {
        let (trader, lp) = (1002i64, 2002i64);
        let (size1, size2) = (5i64, 8i64);
        let entry = 10_000i64;
        let bp_fill = 10_000i64;
        let liq_price = 8_000i64;

        let mut api = setup_two(entry);
        seed_user(&mut api, trader, 20_000, 1);
        seed_user(&mut api, lp, 100_000, 2);

        assert_eq!(place(&mut api, 20001, trader, BTC_SYM, entry, size1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 20002, lp, BTC_SYM, entry, size1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 20003, trader, ETH_SYM, entry, size2, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 20004, lp, ETH_SYM, entry, size2, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        let initial_total = open_volume(&api, trader, BTC_SYM) + open_volume(&api, trader, ETH_SYM);
        assert_eq!(initial_total, size1 + size2);

        assert_eq!(place(&mut api, 20005, lp, BTC_SYM, bp_fill, size1 + 15, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 20006, lp, ETH_SYM, bp_fill, size2 + 17, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, liq_price), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, liq_price), CommandResultCode::Success);

        let remaining = open_volume(&api, trader, BTC_SYM) + open_volume(&api, trader, ETH_SYM);
        assert!(remaining < initial_total, "total position after cross liquidation should be less than initial (at least one position reduced)");
        assert_conserved(&api);
    }

    #[test]
    fn liquidation_scan_slice_only_covers_matching_uid() {
        let entry = 10_000i64;
        let bp_fill = 9_920i64;
        let trader_even = 1_000i64;
        let trader_odd = 1_001i64;
        let lp = 2_000i64;
        let size = 10i64;

        let mut api = setup_btc(entry);
        seed_user(&mut api, trader_even, 3_000, 1);
        seed_user(&mut api, trader_odd, 3_000, 2);
        seed_user(&mut api, lp, 1_000_000, 3);

        assert_eq!(place(&mut api, 1, trader_even, BTC_SYM, entry, size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, lp, BTC_SYM, entry, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 3, trader_odd, BTC_SYM, entry, size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 4, lp, BTC_SYM, entry, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, trader_even, BTC_SYM), size);
        assert_eq!(open_volume(&api, trader_odd, BTC_SYM), size);

        assert_eq!(place(&mut api, 5, lp, BTC_SYM, bp_fill, 3 * size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.set_mark_price(BTC_SYM, 500), CommandResultCode::Success);
        assert_eq!(open_volume(&api, trader_even, BTC_SYM), size, "no scan should happen before liquidation is enabled");
        assert_eq!(open_volume(&api, trader_odd, BTC_SYM), size);

        api.enable_liquidation();

        let scan = |slice: i64| OrderCommand {
            command: OrderCommandType::LiquidationScan, symbol: -1, uid: slice, size: 2, timestamp: 3_000, ..Default::default()
        };
        assert_eq!(api.submit(scan(0)), CommandResultCode::Success);
        assert!(api.user_position(trader_even, BTC_SYM).is_none(), "even uid matches slice 0 -> liquidated");
        assert_eq!(open_volume(&api, trader_odd, BTC_SYM), size, "odd uid does not match slice 0 -> kept");

        assert_eq!(api.submit(scan(1)), CommandResultCode::Success);
        assert!(api.user_position(trader_odd, BTC_SYM).is_none(), "odd uid matches slice 1 -> liquidated");
        assert_conserved(&api);
    }

    #[test]
    fn symbol_index_pruned_lazily_on_scan_after_close() {
        let (trader, counterparty) = (1102i64, 2102i64);
        let size = 5i64;
        let entry = 10_000i64;

        let mut api = setup_btc(entry);
        seed_user(&mut api, trader, 100_000, 1);
        seed_user(&mut api, counterparty, 100_000, 2);

        assert_eq!(place(&mut api, 120001, trader, BTC_SYM, entry, size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 120002, counterparty, BTC_SYM, entry, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, trader, BTC_SYM), size);

        assert!(
            api.risk().liquidation_engine.symbol_to_users.get(&BTC_SYM).is_some_and(|h| h.contains(&trader)),
            "after opening, the symbol index must contain the trader"
        );

        assert_eq!(place(&mut api, 120003, counterparty, BTC_SYM, entry, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 120004, trader, BTC_SYM, entry, size, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert!(api.user_position(trader, BTC_SYM).is_none(), "position should be closed");

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, entry), CommandResultCode::Success);

        assert!(
            !api.risk().liquidation_engine.symbol_to_users.get(&BTC_SYM).is_some_and(|h| h.contains(&trader)),
            "after a post-close scan, the trader must be pruned from the symbol index"
        );
        assert_conserved(&api);
    }
}
