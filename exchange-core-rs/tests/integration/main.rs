pub mod common {
    use std::cell::RefCell;
    use std::rc::Rc;

    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::fund_event::FundEvent;
    use exchange_core_rs::core::exchange_core::ResultsConsumer;
    use exchange_core_rs::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
    use exchange_core_rs::core::processors::user_profile_service::UserProfileService;

    pub struct FundEventCollector(pub Rc<RefCell<Vec<FundEvent>>>);
    impl ResultsConsumer for FundEventCollector {
        fn consume(&mut self, cmd: &OrderCommand, _seq: i64, _ssp: &SymbolSpecificationProvider, _ups: &UserProfileService) {
            self.0.borrow_mut().extend(cmd.fund_events.iter().cloned());
        }
    }
}

mod it_custom_leverage_tests;
mod it_exchange_core_integration_rejection_tests;
mod it_exchange_core_integration_tests;
mod it_extra_margin_tests;
mod it_fees_dynamic_exchange_tests;
mod it_fees_dynamic_margin_tests;
mod it_fees_exchange_tests;
mod it_fees_margin_tests;
mod it_future_base_tests;
mod it_future_basic_tests;
mod it_future_cross_tests;
mod it_futures_trading_fee_tests;
mod it_hedge_mode_tests;
mod it_internal_transfer_tests;
mod it_locked_margin_optimization_tests;
mod it_mark_price_tests;
mod it_mixed_tests;
mod it_open_close_fee_tests;
mod it_place_margin_order_nsf_tests;
mod it_price_scale_tests;
mod it_reset_fee_tests;
mod it_spot_futures_mixed_tests;
mod it_spot_markprice_from_trade_tests;
mod it_spot_trading_fee_tests;
mod it_liquidation_tests;
mod it_adl_tests;
mod it_perpetual_tests;
mod it_fee_audit_regression_tests;
mod it_loan_conservation_tests;
mod it_loan_fund_event_tests;
mod it_loan_dynamic_rate_tests;
mod it_loan_force_liquidate_tests;
mod it_loan_targeted_recovery_tests;
mod it_loan_targeted_liquidation_tests;
mod it_loan_disable_symbol_tests;
mod it_loan_failover_snapshot_tests;
mod it_ioc_ask_lock_release_tests;
mod it_r2_sync_funding_tests;
