#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct L2MarketData {
    pub ask_prices: Vec<i64>,
    pub ask_volumes: Vec<i64>,
    pub ask_orders: Vec<i64>,
    pub bid_prices: Vec<i64>,
    pub bid_volumes: Vec<i64>,
    pub bid_orders: Vec<i64>,
}
