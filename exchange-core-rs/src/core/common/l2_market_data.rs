//! 对应 Java: exchange.core2.core.common.L2MarketData（盘口快照）
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct L2MarketData {
    pub ask_prices: Vec<i64>,
    pub ask_volumes: Vec<i64>,
    /// 每档挂单数（对应 Java `askOrders`），与 `ask_prices` 同序等长。
    pub ask_orders: Vec<i64>,
    pub bid_prices: Vec<i64>,
    pub bid_volumes: Vec<i64>,
    pub bid_orders: Vec<i64>,
}
