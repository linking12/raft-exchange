//! 对应 Java 测试类 `ITMixedIntegration.java` 的部分场景移植：验证现货与期货混合持仓下
//! 资金校验/账户守恒是否正确，以及保险基金（Insurance Fund）充值/提现在全局对账下的闭环。
#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest};

    const QUOTE_ID: i32 = 840;
    const BASE_ID: i32 = 3762;
    const PERP_SYMBOL: i32 = 10000;
    const EXCHANGE_SYMBOL: i32 = 10003;

    const UID_1: i64 = 1001;

    fn perp_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: PERP_SYMBOL,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_ID,
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
        }
    }

    fn exchange_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: EXCHANGE_SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    // 遍历所有币种，校验账户余额 + adjustments + fees + 持仓 PnL/extra_margin 的总和为零（全局资金守恒）。
    fn assert_conserved(api: &ExchangeApi) {
        for &cur in api.ssp().currencies.keys() {
            let mut total: i64 = api.ups().users.values().map(|p| p.account(cur)).sum();
            total += api.adjustments(cur);
            total += api.fees(cur);
            for p in api.ups().users.values() {
                for pos in p.positions.values() {
                    if pos.currency != cur {
                        continue;
                    }
                    let mark = api
                        .risk()
                        .mark_price(pos.symbol)
                        .unwrap_or_else(|| panic!("open position on symbol {} missing mark price", pos.symbol));
                    total += pos.estimate_pnl(mark);
                    total += pos.extra_margin;
                }
            }
            assert_eq!(total, 0, "global conservation broken: currency={cur} total={total}");
        }
    }

    // 对应 Java testMixedExchangeAndMargin() 场景：用户同时持有现货挂单资金占用和期货 pending 仓位，
    // 验证现货下单的资金不足（RISK_NSF）校验、逐步补足资金后成功下单，以及最终 accounts/locked 的正确性。
    #[test]
    fn mixed_exchange_and_margin() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 10_000), CommandResultCode::Success);
        assert_eq!(api.add_symbol(exchange_spec()), CommandResultCode::Success);

        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 10_000, 1), CommandResultCode::Success);

        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 1005, uid: UID_1, symbol: PERP_SYMBOL, price: 10_000, size: 1,
                action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 10_000, "futures pending order must not touch accounts");
        assert!(api.user_position(UID_1, PERP_SYMBOL).is_some(), "pending position should have been created");

        let spot_order = PlaceOrderRequest {
            order_id: 112233, uid: UID_1, symbol: EXCHANGE_SYMBOL, price: 10_000, size: 1,
            reserve_bid_price: 10_000, action: OrderAction::Bid, order_type: OrderType::Gtc,
        };

        assert_eq!(api.place_order(spot_order.clone()), CommandResultCode::RiskNsf);

        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 139, 2), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_order.clone()), CommandResultCode::RiskNsf);
        assert!(api.user_position(UID_1, PERP_SYMBOL).is_some());

        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 1, 3), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_order), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), 10_140, "accounts = actual holdings = 10140");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 10_020, "spot order lock = 10020");
        assert!(api.user_position(UID_1, PERP_SYMBOL).is_some(), "futures pending position should still be present");
        assert_conserved(&api);
    }

    fn if_deposit(api: &mut ExchangeApi, symbol: i32, amount: i64, txid: i64) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::IfDeposit,
            symbol,
            price: amount,
            order_id: txid,
            ..Default::default()
        })
    }

    fn if_withdraw(api: &mut ExchangeApi, symbol: i32, amount: i64, txid: i64) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::IfWithdraw,
            symbol,
            price: amount,
            order_id: txid,
            ..Default::default()
        })
    }

    fn if_balance(api: &ExchangeApi, symbol: i32) -> i64 {
        api.insurance_fund().futures.get(&symbol).map(|e| e.available).unwrap_or(0)
    }

    // 构造一个已初始化好永续合约 symbol 及 mark price、但未做任何充值/提现操作的 ExchangeApi，供 IF 测试复用。
    fn setup_futures_if() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 10_000), CommandResultCode::Success);
        api
    }

    // 对应 Java testInsuranceFundDepositKeepsGlobalReconciliation() 场景：保险基金（IF）充值成功后
    // 余额累加、adjustments 反向对冲，非法入参（负数/零/未知 symbol）不改动账本，全局对账始终闭环。
    #[test]
    fn insurance_fund_deposit_keeps_global_reconciliation() {
        let first_deposit = 1_000_000i64;
        let second_deposit = 500i64;

        let mut api = setup_futures_if();

        assert_eq!(if_balance(&api, PERP_SYMBOL), 0);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, first_deposit, 1), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), first_deposit);
        assert_eq!(api.adjustments(QUOTE_ID), -first_deposit, "deposit -> adjustments records a negative offset");
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, -1, 2), CommandResultCode::RiskInvalidAmount);
        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, 0, 3), CommandResultCode::RiskInvalidAmount);
        assert_eq!(if_deposit(&mut api, 99_999, 1_000, 4), CommandResultCode::InvalidSymbol);

        assert_eq!(if_balance(&api, PERP_SYMBOL), first_deposit);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, second_deposit, 5), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), first_deposit + second_deposit);
        assert_eq!(api.adjustments(QUOTE_ID), -(first_deposit + second_deposit));
        assert!(api.total_balance().is_global_zero());
    }

    // 对应 Java testInsuranceFundWithdrawKeepsGlobalReconciliation() 场景：IF 为空时提现立即返回
    // RISK_IF_INSUFFICIENT，充值后正常提现成功、超额提现被拒、非法入参不改动账本，全局对账始终闭环。
    #[test]
    fn insurance_fund_withdraw_keeps_global_reconciliation() {
        let deposit = 1_000_000i64;
        let first_withdraw = 300_000i64;
        let second_withdraw = 200_000i64;

        let mut api = setup_futures_if();

        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, 100, 1), CommandResultCode::RiskIfInsufficient);
        assert_eq!(if_balance(&api, PERP_SYMBOL), 0);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, deposit, 2), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, first_withdraw, 3), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit - first_withdraw);
        assert_eq!(api.adjustments(QUOTE_ID), -(deposit - first_withdraw));
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, 800_000, 4), CommandResultCode::RiskIfInsufficient);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit - first_withdraw);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, -1, 5), CommandResultCode::RiskInvalidAmount);
        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, 0, 6), CommandResultCode::RiskInvalidAmount);
        assert_eq!(if_withdraw(&mut api, 99_999, 1_000, 7), CommandResultCode::InvalidSymbol);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit - first_withdraw);
        assert!(api.total_balance().is_global_zero());

        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, second_withdraw, 8), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit - first_withdraw - second_withdraw);
        assert_eq!(api.adjustments(QUOTE_ID), -(deposit - first_withdraw - second_withdraw));
        assert!(api.total_balance().is_global_zero());
    }
}
