//! Java exchange-core 快照二进制格式（Chronicle Wire "RAW"）的 Rust 重实现。
//!
//! Rust 没有 `net.openhft.chronicle-wire`/`chronicle-bytes` 库，而快照文件（`.ecs`）需要在
//! 混合 Java/Rust 的 raft 集群节点之间互相读写，因此这几个子模块手工重建了 Java 端用到的那一小部分
//! Chronicle 二进制原语，字节布局逐一对齐 Java 源码（`SerializationUtils.java`、
//! `CoreSymbolSpecification.java` 的 `writeTreeMapToBytes`/`readTreeMapFromBytes` 等）：
//!
//! - [`chronicle_reader`] / [`chronicle_writer`]：最底层的 Chronicle Bytes 原语（定长小端整数、
//!   stop-bit 变长编码、UTF-8 字符串、文档头、几种 map 编解码），一一对应 Java `BytesIn`/`BytesOut`
//!   上的方法。
//! - [`marshalling`]：把"读"和"写"合并成统一的 [`marshalling::ChronicleMarshallable`] trait，
//!   并提供 map 解码结果到 `BTreeMap` 的转换工具，供各快照结构体实现。
//! - [`module_frame`]：`.ecs` 文件最外层的分帧格式（LZ4 可选压缩 + 分片长度前缀 + Chronicle 文档头），
//!   包住上面几层编解码出的 RiskEngine(RE)/MatchingEngine(ME) 模块载荷。
pub mod chronicle_reader;
pub mod chronicle_writer;
pub mod marshalling;
pub mod module_frame;
pub mod serialization_processor;
