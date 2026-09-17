//! 对应 Java `exchange.core2.core.processors.InternalTransferCommandProcessor`。
//!
//! INTERNAL_TRANSFER —— 用户间同币种原子转账。字段复用约定：`cmd.uid` = from，`cmd.size` = to，
//! `cmd.symbol` = currency，`cmd.price` = amount，`cmd.order_id` = 幂等事务 id。
//! `collect` 只对 from 侧做校验（自转账/非正金额/余额不足/幂等）并立即扣款（对应 Java R1
//! `collectInput`，唯一会失败的 NSF 校验只依赖 from 本地状态、按 seq 定序，不存在双花，无需冻结）；
//! `apply` 把金额记入 to 侧账户（对应 Java R2 `applyEvent`，未知 to 自动建 SUSPENDED 档）。
//! 守恒中性：accounts 桶内 from −amount / to +amount，不碰 adjustments 桶；幂等锚点在 from 一侧。

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::fund_event::FundEventType;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::twostep_command_processor::{TwoStepCommandProcessor, TwoStepContext};
use crate::core::processors::user_profile_service::UserProfileService;

/// 无状态标记类型，方法均为纯函数式的 `&self` 调用。
pub struct InternalTransferCommandProcessor;

impl TwoStepCommandProcessor for InternalTransferCommandProcessor {
    /// 对应 Java R1 `collectInput`：校验并从 from 账户即时扣款；成功时把 `(to_uid, currency, amount)`
    /// 打包写入 `cmd.internal_transfer_event` 供 `apply` 使用，并推一条 from 侧余额快照事件。
    /// 失败（自转账/非正金额/用户不存在/NSF/幂等重复）时返回非 Success 结果码，不留下任何副作用。
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

    /// 对应 Java R2 `applyEvent`：把 `collect` 阶段扣下的金额记入 to 账户（未知 to 自动建 SUSPENDED
    /// 档），并推一条 to 侧余额快照事件。若 `collect` 未产生转账事件（比如失败路径），直接跳过。
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
    /// 对应 Java `collectInput` 主体：自转账拒绝、金额必须为正、from 用户必须存在、可提现余额需覆盖
    /// amount（NSF 校验口径与提现一致），并通过 `try_claim_tx` 做幂等（同 order_id 重复提交直接拒绝，
    /// 不重复扣款）。校验全部通过后立即在 from 账户上扣款。
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

    /// 对应 Java matcher stage `buildMatcherEvents`：把 to/currency/amount 打包成待应用的事件。
    fn build_matcher_events(to_uid: i64, currency: i32, amount: i64) -> (i64, i32, i64) {
        (to_uid, currency, amount)
    }

    /// 对应 Java R2 `applyEvent` 的入账部分：给 to 账户加钱（不存在则先建 SUSPENDED 档）。
    fn apply_event(ups: &mut UserProfileService, to_uid: i64, currency: i32, amount: i64) {
        let to = ups.get_or_add_suspended(to_uid);
        to.add_to_account(currency, amount);
    }
}
