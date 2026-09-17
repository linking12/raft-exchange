//! 订单簿模块入口，对应 Java `exchange.core2.core.orderbook` 包。
//!
//! 子模块划分：
//! - `i_order_book`：对应 Java `IOrderBook` 接口，定义订单簿的通用行为（trait）。
//! - `order_book_direct_impl`：对应 Java `OrderBookDirectImpl`，基于双向链表/桶结构的高性能实现（O(log N) 撮合）。
//! - `order_book_naive_impl`：对应 Java `OrderBookNaiveImpl`，基于 TreeMap 的简单/朴素实现，主要用于对拍验证。
//! - `orders_bucket_naive`：对应 Java `OrdersBucketNaive`，朴素实现中单个价位上的订单桶。

pub mod i_order_book;
pub mod order_book_direct_impl;
pub mod order_book_naive_impl;
pub mod orders_bucket_naive;

// 对外重新导出 Direct 实现，是生产环境实际使用的订单簿实现（对应 Java 生产路径默认的 OrderBookDirectImpl）。
pub use order_book_direct_impl::OrderBookDirectImpl;
