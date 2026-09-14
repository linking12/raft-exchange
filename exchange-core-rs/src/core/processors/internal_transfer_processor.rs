//! 对应 Java `InternalTransferProcessor`（两步处理器）：`INTERNAL_TRANSFER` 用户间同币种原子转账。
//! 字段映射：`cmd.uid=fromUid`、`cmd.size=toUid`（overloaded）、`cmd.symbol=currency`、`cmd.price=amount`、`cmd.order_id=transactionId`。R1 校验+立即扣款，merge 1:1 直传，R2 入账（收款方不存在则建 SUSPENDED 档）；守恒 from-=amount/to+=amount。

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;

/// 无状态处理器——零大小类型，不持有字段。
pub struct InternalTransferProcessor;

impl InternalTransferProcessor {
    /// R1：对应 Java `collectInput`。校验顺序：self→amount<=0→from 缺失→NSF→幂等（try_claim_tx）；成功后立即 `from.accounts[currency] -= amount`。
    #[allow(clippy::too_many_arguments)]
    pub fn collect_input(
        engine: &RiskEngine,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        from_uid: i64,
        to_uid: i64,
        currency: i32,
        amount: i64,
        order_id: i64,
        timestamp: i64,
    ) -> CommandResultCode {
        if from_uid == to_uid {
            return CommandResultCode::InternalTransferInvalidSelf;
        }
        if amount <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }
        let from = match ups.get_mut(from_uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };
        if engine.withdrawable_balance(from, currency, ssp) < amount {
            return CommandResultCode::RiskNsf;
        }
        if !from.try_claim_tx(order_id, timestamp) {
            return CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame;
        }
        from.add_to_account(currency, -amount);
        CommandResultCode::Success
    }

    /// merge：对应 Java `buildMatcherEvents`。1:1 直传，调用方已确认 R1 成功，不重复校验。
    pub fn build_matcher_events(to_uid: i64, currency: i32, amount: i64) -> (i64, i32, i64) {
        (to_uid, currency, amount)
    }

    /// R2：对应 Java `applyEvent`。`to` 从未见过则自动建 SUSPENDED 档，随后入账。
    pub fn apply_event(ups: &mut UserProfileService, to_uid: i64, currency: i32, amount: i64) {
        let to = ups.get_or_add_suspended(to_uid);
        to.add_to_account(currency, amount);
    }
}
