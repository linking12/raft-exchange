#[cfg(test)]

mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::fund_event::{FundEvent, FundEventType};
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::position_direction::PositionDirection;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest};

    const BASE_ID: i32 = 11;
    const QUOTE_ID: i32 = 12;
    const SYM: i32 = 10001;

    const UID_1: i64 = 1;
    const UID_2: i64 = 2;
    const UID_3: i64 = 3;
    const MAX_VALUE: i64 = 4_000_000;

    fn adl_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 2,
            fee_scale_k: 1_000,
            init_margin: 1,
            init_margin_scale_k: 100,
            maintenance_margin: BTreeMap::from([(1_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(1_000, 75), (100_000, 40)]),
            ..Default::default()
        }
    }

    fn setup() -> (ExchangeApi, std::rc::Rc<std::cell::RefCell<Vec<FundEvent>>>) {
        let collector: std::rc::Rc<std::cell::RefCell<Vec<FundEvent>>> = std::rc::Rc::new(std::cell::RefCell::new(Vec::new()));
        let mut api = ExchangeApi::new();
        api.core().with_results_consumer(Box::new(crate::common::FundEventCollector(collector.clone())));
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(adl_spec()), CommandResultCode::Success);
        (api, collector)
    }

    fn seed_user(api: &mut ExchangeApi, uid: i64, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE_ID, amount, txid), CommandResultCode::Success);
    }

    fn place(api: &mut ExchangeApi, order_id: i64, uid: i64, price: i64, size: i64, action: OrderAction, margin_mode: MarginMode) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: SYM,
            price,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage: 1,
            margin_mode,
            reduce_only: false,
        })
    }

    fn if_deposit(api: &mut ExchangeApi, amount: i64, txid: i64) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::IfDeposit,
            symbol: SYM,
            price: amount,
            order_id: txid,
            ..Default::default()
        })
    }

    fn assert_conserved(api: &ExchangeApi) {
        let tcb = api.total_balance();
        assert!(tcb.is_global_zero(), "Global balance conservation broken: {:?}", tcb.global_balances_sum());
    }

    fn collect_cascade_on_mark(api: &mut ExchangeApi, collector: &std::rc::Rc<std::cell::RefCell<Vec<FundEvent>>>, symbol: i32, price: i64) -> Vec<FundEvent> {
        collector.borrow_mut().clear();
        assert_eq!(api.set_mark_price(symbol, price), CommandResultCode::Success);
        collector.borrow().clone()
    }

    #[test]
    fn adl_deleverages_winning_counterparty() {
        let (mut api, cascade_collector) = setup();
        let (loser, winner, maker) = (UID_1, UID_2, UID_3);
        seed_user(&mut api, loser, 5_000, 1);
        seed_user(&mut api, winner, 50_000, 2);
        seed_user(&mut api, maker, MAX_VALUE, 3);

        assert_eq!(api.set_mark_price(SYM, 1_000), CommandResultCode::Success);

        assert_eq!(place(&mut api, 1, loser, 1_000, 5, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, maker, 1_000, 5, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_position(loser, SYM).unwrap().open_volume, 5);

        assert_eq!(place(&mut api, 3, winner, 1_000, 10, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 4, maker, 1_000, 10, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_position(winner, SYM).unwrap().open_volume, 10);

        api.enable_liquidation();
        let cascade = collect_cascade_on_mark(&mut api, &cascade_collector, SYM, 600);

        assert!(api.user_position(loser, SYM).is_none(), "LOSER should be fully closed");
        assert_eq!(api.user_position(winner, SYM).unwrap().open_volume, 5, "WINNER reduced by ADL from 10 to 5");
        assert_eq!(api.user_account(winner, QUOTE_ID), 50_000, "ADL does not touch winner's account balance");
        assert_eq!(api.user_position(winner, SYM).unwrap().profit, 0, "no residual unrealized profit on winner's remaining position");
        assert_eq!(api.user_account(loser, QUOTE_ID), 4_960, "loser loses isolated margin (residual after bankruptcy-price settlement)");
        assert_eq!(api.user_account(maker, QUOTE_ID), 3_999_970, "maker counterparty net settlement");
        assert_eq!(api.fees(QUOTE_ID), 30, "taker fees from open + ADL-reduce fills go into the fee pool");
        assert_eq!(api.insurance_fund().futures.values().map(|e| e.available).sum::<i64>(), 0, "no liquidation_fee -> insurance fund balance unchanged");
        let seq: Vec<(FundEventType, i64)> = cascade.iter().map(|e| (e.event_type, e.uid)).collect();
        assert_eq!(seq, vec![
            (FundEventType::LiquidationAlert, loser),
            (FundEventType::UnlockPending, loser),
            (FundEventType::AdlPositionClose, winner),
            (FundEventType::AdlOriginClose, loser),
            (FundEventType::PnlSettlement, loser),
        ], "ADL cascade fund-event sequence (type + uid)");
        assert_conserved(&api);
    }

    #[test]
    fn if_takeover_absorbs_loser_position_no_adl() {
        let (mut api, cascade_collector) = setup();
        let (loser, maker) = (UID_1, UID_2);
        seed_user(&mut api, loser, 5_000, 1);
        seed_user(&mut api, maker, MAX_VALUE, 2);

        assert_eq!(api.set_mark_price(SYM, 1_000), CommandResultCode::Success);

        assert_eq!(place(&mut api, 1, loser, 1_000, 5, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, maker, 1_000, 5, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_position(loser, SYM).unwrap().open_volume, 5);

        assert_eq!(if_deposit(&mut api, 5 * 1_000, 1), CommandResultCode::Success);

        api.enable_liquidation();
        let cascade = collect_cascade_on_mark(&mut api, &cascade_collector, SYM, 600);

        assert!(api.user_position(loser, SYM).is_none(), "LOSER should be fully closed");
        assert_eq!(api.user_position(maker, SYM).unwrap().open_volume, 5, "MAKER unaffected by ADL, stays at 5");
        assert_eq!(
            api.total_balance().if_open_interest_long.get(&SYM).copied().unwrap_or(0),
            5,
            "IF takes over LONG position of 5"
        );
        assert_eq!(api.user_account(loser, QUOTE_ID), 4_960, "loser loses isolated margin");
        assert_eq!(api.user_account(maker, QUOTE_ID), 3_999_990, "maker counterparty net settlement (no ADL reduction)");
        assert_eq!(api.fees(QUOTE_ID), 10, "only the opening trade fee (no reduce trade)");
        assert_eq!(api.insurance_fund().futures.values().map(|e| e.available).sum::<i64>(), 40, "insurance fund available balance after taking over the position");
        assert_eq!(api.insurance_fund().futures.values().map(|e| e.reserved).sum::<i64>(), 0, "no leak in insurance fund reserved balance");
        let seq: Vec<(FundEventType, i64)> = cascade.iter().map(|e| (e.event_type, e.uid)).collect();
        assert_eq!(seq, vec![
            (FundEventType::LiquidationAlert, loser),
            (FundEventType::UnlockPending, loser),
            (FundEventType::IfPositionClose, loser),
            (FundEventType::PnlSettlement, loser),
        ], "IF-takeover cascade fund-event sequence (type + uid)");
        assert!(
            !seq.iter().any(|(t, _)| *t == FundEventType::AdlPositionClose),
            "no ADL event should appear on the IF-takeover path"
        );
        assert_conserved(&api);
    }

    #[test]
    fn liquidation_reopen_and_reliquidate_no_reserved_leak() {
        let (mut api, _cascade_collector) = setup();
        let (loser, maker) = (UID_1, UID_2);
        seed_user(&mut api, loser, 20_000, 1);
        seed_user(&mut api, maker, MAX_VALUE, 2);

        assert_eq!(api.set_mark_price(SYM, 1_000), CommandResultCode::Success);

        assert_eq!(if_deposit(&mut api, 3 * 1_000, 1), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1, loser, 1_000, 5, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, maker, 1_000, 5, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(SYM, 600), CommandResultCode::Success);
        assert!(api.user_position(loser, SYM).is_none(), "first liquidation should fully close the position");

        assert_eq!(if_deposit(&mut api, 2 * 1_000, 100), CommandResultCode::Success);
        assert_eq!(place(&mut api, 3, loser, 700, 4, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 4, maker, 700, 4, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.set_mark_price(SYM, 400), CommandResultCode::Success);
        assert!(api.user_position(loser, SYM).is_none(), "second liquidation should also fully close the position");

        if let Some(entry) = api.insurance_fund().futures.get(&SYM) {
            assert_eq!(entry.reserved, 0, "insurance fund reserved balance must not be left over");
        }
        assert_conserved(&api);
    }

    #[test]
    fn adl_origin_consumed_mid_cascade_conservation() {
        const FUT: i32 = 5001;

        let spec = CoreSymbolSpecification {
            symbol_id: FUT,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 10_000,
            liquidation_fee: 200,
            maintenance_margin: BTreeMap::from([(i64::MAX, 500)]),
            maintenance_margin_scale_k: 10_000,
            max_leverage: BTreeMap::from([(i64::MAX, 1_000)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(spec), CommandResultCode::Success);

        let (u1, u2, u3, u4) = (1i64, 2i64, 3i64, 4i64);
        for (i, &uid) in [u1, u2, u3, u4].iter().enumerate() {
            seed_user(&mut api, uid, 1_000_000, (i + 1) as i64);
        }

        api.enable_liquidation();

        let place_iso = |api: &mut ExchangeApi, order_id: i64, uid: i64, price: i64, size: i64, bid: bool| {
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id,
                uid,
                symbol: FUT,
                price,
                size,
                action: if bid { OrderAction::Bid } else { OrderAction::Ask },
                order_type: OrderType::Gtc,
                leverage: 10,
                margin_mode: MarginMode::Isolated,
                reduce_only: false,
            })
        };

        assert_eq!(api.set_mark_price(FUT, 100), CommandResultCode::Success);

        let mut oid = 1000i64;
        assert_eq!(place_iso(&mut api, oid, u3, 84, 5, true), CommandResultCode::Success);
        oid += 1;
        assert_eq!(place_iso(&mut api, oid, u4, 95, 6, false), CommandResultCode::Success);
        oid += 1;
        assert_eq!(api.set_mark_price(FUT, 60), CommandResultCode::Success);
        assert_eq!(place_iso(&mut api, oid, u4, 86, 5, false), CommandResultCode::Success);
        oid += 1;
        assert_eq!(place_iso(&mut api, oid, u3, 86, 6, true), CommandResultCode::Success);
        oid += 1;
        assert_eq!(place_iso(&mut api, oid, u2, 98, 3, false), CommandResultCode::Success);
        oid += 1;
        assert_eq!(place_iso(&mut api, oid, u1, 98, 9, true), CommandResultCode::Success);
        oid += 1;
        assert_eq!(api.set_mark_price(FUT, 100), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(FUT, 60), CommandResultCode::Success);
        assert_eq!(place_iso(&mut api, oid, u2, 80, 14, false), CommandResultCode::Success);

        let check = |api: &ExchangeApi, uid: i64, dir: PositionDirection, vol: i64, price_sum: i64| {
            let p = api.user_position(uid, FUT).unwrap();
            assert_eq!(p.direction, dir, "uid{uid} direction");
            assert_eq!(p.open_volume, vol, "uid{uid} open_volume");
            assert_eq!(p.open_price_sum, price_sum, "uid{uid} open_price_sum");
        };
        check(&api, u1, PositionDirection::Long, 9, 864);
        check(&api, u2, PositionDirection::Short, 6, 506);
        check(&api, u3, PositionDirection::Long, 6, 506);
        check(&api, u4, PositionDirection::Short, 9, 808);

        assert_eq!(api.set_mark_price(FUT, 92), CommandResultCode::Success);

        let tcb = api.total_balance();
        assert!(
            tcb.is_global_zero(),
            "ADL origin consumed mid-cascade must not break global conservation: {:?}",
            tcb.global_balances_sum()
        );
    }
}
