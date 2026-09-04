//! 引擎核心。对应 Java 包 `exchange.core2.core`；Disruptor 五段管线塌缩为单线程确定性顺序管线（R1→ME→R2），具体实现见 [`exchange_core::ExchangeCore`]。

pub mod common;
pub mod orderbook;
pub mod processors;
pub mod utils;

pub mod exchange_core;
pub mod exchange_api;

#[cfg(test)]
mod e2e_tests;
#[cfg(test)]
mod futures_e2e_tests;
#[cfg(test)]
mod loan_e2e_tests;
#[cfg(test)]
mod liquidation_e2e_tests;
