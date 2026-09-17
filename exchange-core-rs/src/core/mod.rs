// common: 对应 Java `exchange.core2.core.common`（DTO/枚举/记录类）
pub mod common;
// orderbook: 对应 Java `exchange.core2.core.orderbook`（撮合簿实现）
pub mod orderbook;
// processors: 对应 Java `exchange.core2.core.processors`（RiskEngine/MatchingEngineRouter 等）
pub mod processors;
pub mod utils;

// exchange_core: 对应 Java `ExchangeCore`（Disruptor 装配 + R1/ME/R2 编排），此处塌缩为单线程管线
pub mod exchange_core;
// exchange_api: 对应 Java `ExchangeApi`（面向调用方的门面）
pub mod exchange_api;
// fund_events_handler: 对应 Java `IFundEventsHandler`
pub mod fund_events_handler;
// reports: 对应 Java `common/api/reports/*` 查询 DTO + RiskEngine 报表查询逻辑
pub mod reports;
pub mod snapshot;
// simple_events_processor: 对应 Java `SimpleEventsProcessor`
pub mod simple_events_processor;
// trade_events_handler: 对应 Java `ITradeEventsHandler`
pub mod trade_events_handler;
