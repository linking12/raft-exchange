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
        let sink = collector.clone();
        let mut core = exchange_core_rs::core::exchange_core::ExchangeCore::new();
        core.with_results_consumer(Box::new(move |cmd, _seq, _ssp, _ups| {
            sink.borrow_mut().extend(cmd.fund_events.iter().cloned());
        }));
        let mut api = ExchangeApi::from_core(core);
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
}
