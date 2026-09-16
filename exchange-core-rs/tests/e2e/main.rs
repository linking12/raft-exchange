//! 引擎级 e2e 测试(从 src/core 迁入 tests/):现货/期货/loan/清算 场景 + 守恒 proptest。
//! 只用公开 API(`exchange_core_rs::core::...`);crate 根文件的 `mod X;` 解析到同级,故用 e2e/main.rs。
mod e2e_tests;
mod futures_e2e_tests;
mod loan_e2e_tests;
mod liquidation_e2e_tests;
mod spot_e2e_java_parity_tests;
