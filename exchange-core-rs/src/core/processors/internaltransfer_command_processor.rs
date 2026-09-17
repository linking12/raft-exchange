//! 对应 Java `InternalTransferCommandProcessor`（两步处理器）：`INTERNAL_TRANSFER` 用户间同币种原子转账。
//! 字段映射：`cmd.uid=fromUid`、`cmd.size=toUid`（overloaded）、`cmd.symbol=currency`、`cmd.price=amount`、`cmd.order_id=transactionId`。R1 校验+立即扣款，merge 1:1 直传，R2 入账（收款方不存在则建 SUSPENDED 档）；守恒 from-=amount/to+=amount。

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::fund_event::FundEventType;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::twostep_command_processor::{TwoStepCommandProcessor, TwoStepContext};
use crate::core::processors::user_profile_service::UserProfileService;

/// 无状态处理器——零大小类型，不持有字段。
pub struct InternalTransferCommandProcessor;

impl TwoStepCommandProcessor for InternalTransferCommandProcessor {
    /// R1：字段映射 `cmd.uid=from / cmd.size=to / cmd.symbol=currency / cmd.price=amount`;R1 失败直接返回拒绝码,
    /// 成功则立即扣付款方 + 写 `cmd.internal_transfer_event` 供 R2,并发付款方 INTERNAL_TRANSFER 事件。
    fn collect(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) -> CommandResultCode {
        let from_uid = cmd.uid;
        let to_uid = cmd.size;
        let currency = cmd.symbol;
        let amount = cmd.price;
        let order_id = cmd.order_id;
        let timestamp = cmd.timestamp;

        let rc = Self::collect_input(ctx.risk, ctx.ups, ctx.ssp, from_uid, to_uid, currency, amount, order_id, timestamp);
        if rc == CommandResultCode::Success {
            cmd.internal_transfer_event = Some(Self::build_matcher_events(to_uid, currency, amount));
            RiskEngine::push_spot_balance_event(cmd, ctx.ups, ctx.ssp, FundEventType::InternalTransfer, order_id, from_uid, currency, 0);
        }
        rc
    }

    /// R2：消费 `cmd.internal_transfer_event`（None 早退），收款方入账 + 发收款方 INTERNAL_TRANSFER 事件。
    fn apply(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) {
        let Some((to_uid, currency, amount)) = cmd.internal_transfer_event.take() else {
            return;
        };
        Self::apply_event(ctx.ups, to_uid, currency, amount);
        let order_id = cmd.order_id;
        RiskEngine::push_spot_balance_event(cmd, ctx.ups, ctx.ssp, FundEventType::InternalTransfer, order_id, to_uid, currency, 0);
    }
}

impl InternalTransferCommandProcessor {
    /// R1：对应 Java `collectInput`。校验顺序：self→amount<=0→from 缺失→NSF→幂等（try_claim_tx）；成功后立即 `from.accounts[currency] -= amount`。
    #[allow(clippy::too_many_arguments)]
    fn collect_input(
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
    fn build_matcher_events(to_uid: i64, currency: i32, amount: i64) -> (i64, i32, i64) {
        (to_uid, currency, amount)
    }

    /// R2：对应 Java `applyEvent`。`to` 从未见过则自动建 SUSPENDED 档，随后入账。
    fn apply_event(ups: &mut UserProfileService, to_uid: i64, currency: i32, amount: i64) {
        let to = ups.get_or_add_suspended(to_uid);
        to.add_to_account(currency, amount);
    }
}
