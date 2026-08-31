//! 命令 / 结果 / 报告 DTO。
//!
//! 对应 Java: `exchange.core2.core.common.api.**`（`api/` 46 文件 + `api/reports/` 19 文件）
//! + `core.common.cmd`（OrderCommand 等）+ `core.common`（Order / UserProfile / 枚举）。
//!
//! 确定性约定：金额一律 `i64` 定点，无浮点。

// TODO(port): commands（PlaceOrder / Cancel / Move / balance-adjust / loan / ...）
// TODO(port): results（CommandResultCode / MatcherTradeEvent / ...）
// TODO(port): reports（api/reports/**）
