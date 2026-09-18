use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;

pub struct TwoStepContext<'a> {
    pub risk: &'a mut RiskEngine,
    pub ups: &'a mut UserProfileService,
    pub ssp: &'a SymbolSpecificationProvider,
}

impl<'a> TwoStepContext<'a> {
    pub fn new(
        risk: &'a mut RiskEngine,
        ups: &'a mut UserProfileService,
        ssp: &'a SymbolSpecificationProvider,
    ) -> Self {
        Self { risk, ups, ssp }
    }
}

pub trait TwoStepCommandProcessor {

    fn collect(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) -> CommandResultCode;

    fn apply(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand);
}
