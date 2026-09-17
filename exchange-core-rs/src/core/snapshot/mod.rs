//! Java 快照(Chronicle Wire RAW)读写:混合集群下 Rust 与 Java 共用快照格式。见 memory `snapshot-chronicle-format`。

pub mod chronicle_reader;
pub mod chronicle_writer;
pub mod marshalling;
pub mod module_frame;
