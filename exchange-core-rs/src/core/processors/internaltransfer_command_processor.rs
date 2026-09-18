use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::fund_event::FundEventType;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::twostep_command_processor::{TwoStepCommandProcessor, TwoStepContext};
use crate::core::processors::user_profile_service::UserProfileService;

pub struct InternalTransferCommandProcessor;

impl TwoStepCommandProcessor for InternalTransferCommandProcessor {

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

    fn build_matcher_events(to_uid: i64, currency: i32, amount: i64) -> (i64, i32, i64) {
        (to_uid, currency, amount)
    }

    fn apply_event(ups: &mut UserProfileService, to_uid: i64, currency: i32, amount: i64) {
        let to = ups.get_or_add_suspended(to_uid);
        to.add_to_account(currency, amount);
    }
}
