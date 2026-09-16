//! Java IT 集成测试的 Rust 翻译对拍(从 src/core 迁入,作为 tests/ 集成测试跑,只用公开 API)。
//! 每个子模块对应一个 Java IT 文件;详见 memory it-translation-parity。

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
