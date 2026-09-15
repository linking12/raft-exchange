//! 引擎核心。对应 Java 包 `exchange.core2.core`；Disruptor 五段管线塌缩为单线程确定性顺序管线（R1→ME→R2），具体实现见 [`exchange_core::ExchangeCore`]。

pub mod common;
pub mod orderbook;
pub mod processors;
pub mod utils;

pub mod exchange_core;
pub mod exchange_api;
pub mod fund_events_handler;
pub mod reports;
pub mod simple_events_processor;
pub mod trade_events_handler;

#[cfg(test)]
mod e2e_tests;
#[cfg(test)]
mod futures_e2e_tests;
#[cfg(test)]
mod loan_e2e_tests;
#[cfg(test)]
mod liquidation_e2e_tests;
#[cfg(test)]
mod spot_e2e_java_parity_tests;
#[cfg(test)]
mod it_exchange_core_integration_tests;
#[cfg(test)]
mod it_exchange_core_integration_rejection_tests;
#[cfg(test)]
mod it_spot_trading_fee_tests;
#[cfg(test)]
mod it_price_scale_tests;
#[cfg(test)]
mod it_future_basic_tests;
#[cfg(test)]
mod it_future_base_tests;
#[cfg(test)]
mod it_futures_trading_fee_tests;
#[cfg(test)]
mod it_hedge_mode_tests;
#[cfg(test)]
mod it_custom_leverage_tests;
#[cfg(test)]
mod it_mark_price_tests;
#[cfg(test)]
mod it_future_cross_tests;
#[cfg(test)]
mod it_extra_margin_tests;
#[cfg(test)]
mod it_open_close_fee_tests;
#[cfg(test)]
mod it_place_margin_order_nsf_tests;
#[cfg(test)]
mod it_locked_margin_optimization_tests;
#[cfg(test)]
mod it_fees_margin_tests;
#[cfg(test)]
mod it_fees_exchange_tests;
#[cfg(test)]
mod it_fees_dynamic_margin_tests;
#[cfg(test)]
mod it_fees_dynamic_exchange_tests;
#[cfg(test)]
mod it_spot_futures_mixed_tests;
#[cfg(test)]
mod it_mixed_tests;
