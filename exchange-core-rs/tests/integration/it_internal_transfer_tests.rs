//! 翻译自 Java `exchange.core2.tests.integration.ITInternalTransfer`（8 个 @Test）。
//!
//! Java 原测走真实 R1→ME→R2 pipeline（多 shard/异步回调），验证 INTERNAL_TRANSFER 端到端：
//! 单/跨 shard 转账、守恒、幂等、NSF、自转、非法金额、to 自动建档、两腿 fund event。
//! Rust 引擎单线程直调（无 raft/多 shard），shard 数对结果无影响——故 Java 的 `riskEnginesNum=1/2`
//! 两个变体在 Rust 侧行为等价，但仍逐条保留以对齐 8 个 @Test 计数与断言黄金值。
//!
//! 字段映射（对应 Java `ApiInternalTransfer` + `InternalTransferProcessor`）：
//! `cmd.uid=fromUid`、`cmd.size=toUid`（overloaded：size 承载目标 uid 而非金额）、
//! `cmd.symbol=currency`、`cmd.price=amount`、`cmd.order_id=transactionId`。
//! 结果码：SUCCESS→Success、RISK_NSF→RiskNsf、RISK_INVALID_AMOUNT→RiskInvalidAmount、
//! INTERNAL_TRANSFER_INVALID_SELF→InternalTransferInvalidSelf、
//! USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME→UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame。
//! 守恒用 `total_balance().is_global_zero()`（对拍 Java `isGlobalBalancesAllZero`）。

#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::fund_event::FundEventType;
    use exchange_core_rs::core::exchange_api::ExchangeApi;

    // USD：digit=0 → currency_scale_k=1（raw 值即实际值）。
    const USD: i32 = 2;
    const A: i64 = 100;
    const B: i64 = 101; // 与 A 在 Java 2-shard 下异 shard；Rust 单引擎无 shard 之分。
    const TX: i64 = 1_000_001; // 高位 txId，避开 seed 存款的 order_id，隔离幂等去重。
    const SEED_TX: i64 = 1; // 播种存款用的 txId，须与 TX 互异（否则转账被 claim 去重误拒）。

    /// 构造并提交一条 INTERNAL_TRANSFER，返回结果码。
    fn transfer(api: &mut ExchangeApi, from: i64, to: i64, amount: i64, txid: i64) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::InternalTransfer,
            uid: from,
            size: to, // overloaded：承载目标 uid。
            symbol: USD,
            price: amount,
            order_id: txid,
            ..Default::default()
        })
    }

    /// 对拍 Java `twoUsers`：建 USD、建 A/B、给 A 充值 `balance_a`。
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

    // ================================================================
    // 8 个 @Test 逐条对拍
    // ================================================================

    // transfer_debitsFromCreditsTo_conserved（Java shards=1）。
    #[test]
    fn transfer_debits_from_credits_to_conserved() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, B, 300, TX), CommandResultCode::Success);
        assert_eq!(balance(&api, A), 700);
        assert_eq!(balance(&api, B), 300);
        assert!(api.total_balance().is_global_zero(), "转账守恒中性");
    }

    // transfer_crossShard_atomicAndConserved（Java shards=2；Rust 单引擎行为等价）。
    #[test]
    fn transfer_cross_shard_atomic_and_conserved() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, B, 400, TX), CommandResultCode::Success);
        assert_eq!(balance(&api, A), 600);
        assert_eq!(balance(&api, B), 400);
        assert!(api.total_balance().is_global_zero(), "跨 shard 转账守恒");
    }

    // transfer_nsf_rejected_noChange。
    #[test]
    fn transfer_nsf_rejected_no_change() {
        let mut api = two_users(100);
        assert_eq!(transfer(&mut api, A, B, 500, TX), CommandResultCode::RiskNsf);
        assert_eq!(balance(&api, A), 100);
        assert_eq!(balance(&api, B), 0);
    }

    // transfer_idempotent_replaySameTransactionId：同 txId 重投拒为 ALREADY_APPLIED_SAME，余额不二次变动。
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

    // transfer_selfTransfer_rejected。
    #[test]
    fn transfer_self_transfer_rejected() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, A, 100, TX), CommandResultCode::InternalTransferInvalidSelf);
        assert_eq!(balance(&api, A), 1_000);
    }

    // transfer_invalidAmount_rejected（amount=0 → RISK_INVALID_AMOUNT）。
    #[test]
    fn transfer_invalid_amount_rejected() {
        let mut api = two_users(1_000);
        assert_eq!(transfer(&mut api, A, B, 0, TX), CommandResultCode::RiskInvalidAmount);
        assert_eq!(balance(&api, A), 1_000);
    }

    // transfer_emitsFundEventForBothLegs：付款/收款两腿各发一条 INTERNAL_TRANSFER 事件，free 为该腿结算后快照。
    // Rust 单引擎里 R1(from) 与 R2(to) 均在同一 process_command 内完成，两条事件都落在 last_fund_events()。
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

        assert_eq!(legs.len(), 2, "两腿各一条 INTERNAL_TRANSFER 事件");
        assert_eq!(legs.get(&A).copied(), Some(700), "付款方 free 快照");
        assert_eq!(legs.get(&B).copied(), Some(300), "收款方 free 快照");
    }

    // transfer_toNotExist_autoCreatesAndCredits：未知 to 自动建档收钱，守恒不破。
    #[test]
    fn transfer_to_not_exist_auto_creates_and_credits() {
        let mut api = two_users(1_000);
        let unknown = 999; // 未 add_user。
        assert_eq!(transfer(&mut api, A, unknown, 250, TX), CommandResultCode::Success);
        assert_eq!(balance(&api, A), 750);
        assert_eq!(balance(&api, unknown), 250, "未知 to 自动建档收钱");
        assert!(api.total_balance().is_global_zero(), "自动建档仍守恒");
    }
}
