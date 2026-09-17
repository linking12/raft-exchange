//! 引擎核心。对应 Java 包 `exchange.core2.core`；Disruptor 五段管线塌缩为单线程确定性顺序管线（R1→ME→R2），具体实现见 [`exchange_core::ExchangeCore`]。

pub mod common;
pub mod orderbook;
pub mod processors;
pub mod utils;

pub mod exchange_core;
pub mod exchange_api;
pub mod fund_events_handler;
pub mod reports;
pub mod snapshot;
pub mod simple_events_processor;
pub mod trade_events_handler;

// 引擎级 e2e 测试已迁至 `tests/e2e/`(只用公开 API)。
