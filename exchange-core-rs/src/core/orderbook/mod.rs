//! 订单簿与撮合。对应 Java `exchange.core2.core.orderbook.**`。
//!
//! 确定性：价位用有序容器（`BTreeMap<i64, _>`）保证价格/时间优先级遍历序与 Java 一致。

pub mod i_order_book;
pub mod order_book_naive_impl;
pub mod orders_bucket_naive;
