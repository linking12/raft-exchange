//! 订单簿与撮合。
//!
//! 对应 Java: `exchange.core2.core.orderbook.**`
//! - `IOrderBook`（trait）
//! - `OrderBookDirectImpl` / `OrderBookNaiveImpl`（实现）
//! - `OrdersBucketNaive` / `OrderBookEventsHelper` / `OrdersSpliterator`
//!
//! 确定性：价位用有序容器（`BTreeMap<i64, _>`）保证价格/时间优先级遍历序与 Java 一致。

// TODO(port): IOrderBook trait
// TODO(port): OrderBookNaiveImpl（先做 naive，语义直观、便于对齐单测）
// TODO(port): OrderBookDirectImpl
