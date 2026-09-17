//! 对应 Java `exchange.core2.core.common.L2MarketData`。订单簿 L2 快照的搬运对象。
//! Java 侧用定长数组 + `askSize`/`bidSize` 双游标避免频繁分配;这里直接用 `Vec`,
//! 长度即游标,语义等价但更符合 Rust 惯用法。

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct L2MarketData {
    /// 对应 Java `askPrices`,按价格升序(离盘口最近的在前)。
    pub ask_prices: Vec<i64>,
    /// 对应 Java `askVolumes`,与 `ask_prices` 同下标对齐。
    pub ask_volumes: Vec<i64>,
    /// 对应 Java `askOrders`:该价位的挂单数。
    pub ask_orders: Vec<i64>,
    /// 对应 Java `bidPrices`,按价格降序(离盘口最近的在前)。
    pub bid_prices: Vec<i64>,
    /// 对应 Java `bidVolumes`,与 `bid_prices` 同下标对齐。
    pub bid_volumes: Vec<i64>,
    /// 对应 Java `bidOrders`:该价位的挂单数。
    pub bid_orders: Vec<i64>,
}
