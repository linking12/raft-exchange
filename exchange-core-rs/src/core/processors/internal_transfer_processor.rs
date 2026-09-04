//! 对应 Java `InternalTransferProcessor`（`TwoStepCommandProcessor` 薄实例，全文移植）。`INTERNAL_TRANSFER`
//! 两步处理器：用户间同币种原子转账（参考文档 §5）。字段映射：`cmd.uid=fromUid`、`cmd.size=toUid`（overloaded）、
//! `cmd.symbol=currency`、`cmd.price=amount`、`cmd.order_id=transactionId`。R1 校验+立即扣款，merge 1:1 直传，
//! R2 入账（收款方不存在则建 SUSPENDED 档）；守恒 from-=amount/to+=amount。事件载体用
//! `OrderCommand.internal_transfer_event` 而非 Java `MatcherEventType::INTERNAL_TRANSFER_EVENT`（Ruling
//! P6-A/P6-C）。无状态处理器。`emitSnapshot`（Java `:101-104`）未移植，纯外部事件下发（Ruling P6-B）。

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;

/// 对应 Java `InternalTransferProcessor`（`TwoStepCommandProcessor` 子类）。零大小类型，见模块文档。
pub struct InternalTransferProcessor;

impl InternalTransferProcessor {
    /// R1：对应 Java `collectInput`（`:37-69`）。校验顺序：self→amount<=0→from 缺失→NSF（同提现口径）→
    /// 幂等（try_claim_tx，claim-and-keep）；成功后立即 `from.accounts[currency] -= amount`，返回 Success。
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
        if !from.try_claim_tx(order_id) {
            return CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame;
        }
        from.add_to_account(currency, -amount);
        CommandResultCode::Success
    }

    /// merge：对应 Java `buildMatcherEvents`（`:72-81`）。trivial 1:1 直传，调用方已确认 R1 成功，不重复校验。
    pub fn build_matcher_events(to_uid: i64, currency: i32, amount: i64) -> (i64, i32, i64) {
        (to_uid, currency, amount)
    }

    /// R2：对应 Java `applyEvent`（`:84-98`）。`to` 从未见过则自动建 SUSPENDED 档，随后入账。
    pub fn apply_event(ups: &mut UserProfileService, to_uid: i64, currency: i32, amount: i64) {
        let to = ups.get_or_add_suspended(to_uid);
        to.add_to_account(currency, amount);
    }
}
