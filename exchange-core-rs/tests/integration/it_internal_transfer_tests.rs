#[cfg(test)]

mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::fund_event::FundEventType;
    use exchange_core_rs::core::exchange_api::ExchangeApi;

    const USD: i32 = 2;
    const A: i64 = 100;
    const B: i64 = 101;
    const TX: i64 = 1_000_001;
    const SEED_TX: i64 = 1;

    fn transfer(api: &mut ExchangeApi, from: i64, to: i64, amount: i64, txid: i64) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::InternalTransfer,
            uid: from,
            size: to,
            symbol: USD,
            price: amount,
            order_id: txid,
            ..Default::default()
        })
    }

    fn two_users(balance_a: i64) -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(USD, 1);
        assert_eq!(api.add_user(A), CommandResultCode::Success);
        assert_eq!(api.add_user(B), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(A, USD, balance_a, SEED_TX), CommandResultCode::Success);
        api
    }

    fn balance(api: &ExchangeApi, uid: i64) -> i64 {
        api.user_account(uid, USD)
    }

    #[test]
    fn transfer_debits_from_credits_to_conserved() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, B, 300, TX), CommandResultCode::Success);
        assert_eq!(balance(&api, A), 700);
        assert_eq!(balance(&api, B), 300);
        assert!(api.total_balance().is_global_zero(), "transfer is conservation-neutral");
    }

    #[test]
    fn transfer_cross_shard_atomic_and_conserved() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, B, 400, TX), CommandResultCode::Success);
        assert_eq!(balance(&api, A), 600);
        assert_eq!(balance(&api, B), 400);
        assert!(api.total_balance().is_global_zero(), "cross-shard transfer conservation");
    }

    #[test]
    fn transfer_nsf_rejected_no_change() {
        let mut api = two_users(100);
        assert_eq!(transfer(&mut api, A, B, 500, TX), CommandResultCode::RiskNsf);
        assert_eq!(balance(&api, A), 100);
        assert_eq!(balance(&api, B), 0);
    }

    #[test]
    fn transfer_idempotent_replay_same_transaction_id() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, B, 300, TX), CommandResultCode::Success);
        assert_eq!(
            transfer(&mut api, A, B, 300, TX),
            CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame
        );
        assert_eq!(balance(&api, A), 700);
        assert_eq!(balance(&api, B), 300);
    }

    #[test]
    fn transfer_self_transfer_rejected() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, A, 100, TX), CommandResultCode::InternalTransferInvalidSelf);
        assert_eq!(balance(&api, A), 1_000);
    }

    #[test]
    fn transfer_invalid_amount_rejected() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, B, 0, TX), CommandResultCode::RiskInvalidAmount);
        assert_eq!(balance(&api, A), 1_000);
    }

    #[test]
    fn transfer_emits_fund_event_for_both_legs() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, B, 300, TX), CommandResultCode::Success);

        let legs: std::collections::HashMap<i64, i64> = api
            .last_fund_events()
            .iter()
            .filter(|e| e.event_type == FundEventType::InternalTransfer)
            .map(|e| (e.uid, e.free))
            .collect();

        assert_eq!(legs.len(), 2, "each leg should emit one INTERNAL_TRANSFER event");
        assert_eq!(legs.get(&A).copied(), Some(700), "payer free balance snapshot");
        assert_eq!(legs.get(&B).copied(), Some(300), "payee free balance snapshot");
    }

    #[test]
    fn transfer_to_not_exist_auto_creates_and_credits() {
        let mut api = two_users(1_000);
        let unknown = 999;
        assert_eq!(transfer(&mut api, A, unknown, 250, TX), CommandResultCode::Success);
        assert_eq!(balance(&api, A), 750);
        assert_eq!(balance(&api, unknown), 250, "unknown recipient should be auto-created and credited");
        assert!(api.total_balance().is_global_zero(), "conservation should hold even with an auto-created account");
    }
}
