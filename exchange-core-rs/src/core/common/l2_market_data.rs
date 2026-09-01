//! 对应 Java: exchange.core2.core.common.L2MarketData（盘口快照）
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct L2MarketData {
    pub ask_prices: Vec<i64>,
    pub ask_volumes: Vec<i64>,
    pub bid_prices: Vec<i64>,
    pub bid_volumes: Vec<i64>,
}
