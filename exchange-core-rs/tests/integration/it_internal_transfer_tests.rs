#[cfg(test)]
// 翻译自 Java `ITInternalTransfer`
// 验证 INTERNAL_TRANSFER 内部转账：单/跨 shard、守恒、幂等重放、NSF、自转、收款方自动建档等场景
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

    // 对应 Java transfer_debitsFromCreditsTo_conserved：验证转账后双方余额正确且全局守恒
    #[test]
    fn transfer_debits_from_credits_to_conserved() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, B, 300, TX), CommandResultCode::Success);
        assert_eq!(balance(&api, A), 700);
        assert_eq!(balance(&api, B), 300);
        assert!(api.total_balance().is_global_zero(), "transfer is conservation-neutral");
    }

    // 对应 Java transfer_crossShard_atomicAndConserved：验证跨 shard 转账原子生效且全局守恒
    #[test]
    fn transfer_cross_shard_atomic_and_conserved() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, B, 400, TX), CommandResultCode::Success);
        assert_eq!(balance(&api, A), 600);
        assert_eq!(balance(&api, B), 400);
        assert!(api.total_balance().is_global_zero(), "cross-shard transfer conservation");
    }

    // 对应 Java transfer_nsf_rejected_noChange：验证余额不足时转账被拒绝且双方余额不变
    #[test]
    fn transfer_nsf_rejected_no_change() {
        let mut api = two_users(100);
        assert_eq!(transfer(&mut api, A, B, 500, TX), CommandResultCode::RiskNsf);
        assert_eq!(balance(&api, A), 100);
        assert_eq!(balance(&api, B), 0);
    }

    // 对应 Java transfer_idempotent_replaySameTransactionId：验证相同 transactionId 重放被识别为已处理，不重复扣款
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

    // 对应 Java transfer_selfTransfer_rejected：验证自己转给自己被拒绝
    #[test]
    fn transfer_self_transfer_rejected() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, A, 100, TX), CommandResultCode::InternalTransferInvalidSelf);
        assert_eq!(balance(&api, A), 1_000);
    }

    // 对应 Java transfer_invalidAmount_rejected：验证转账金额为 0 时被拒绝
    #[test]
    fn transfer_invalid_amount_rejected() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, B, 0, TX), CommandResultCode::RiskInvalidAmount);
        assert_eq!(balance(&api, A), 1_000);
    }

    // 对应 Java transfer_emitsFundEventForBothLegs：验证转账两条腿各触发一条 INTERNAL_TRANSFER 资金事件（此处直接读取本地事件，无需 Java 版轮询等待）
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

    // 对应 Java transfer_toNotExist_autoCreatesAndCredits：验证转给未知用户会自动建档并收到款项，全局仍守恒
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
