//! 借贷利率模型（对应 Java `exchange.core2.core.processors.loan.rate` 包）。
//! 浮动利率引擎（floating_rate_model：kinked 曲线 + 累加器计息）与定期锁率模型
//! （fixed_rate_model：开仓锁定 floating 当前利率 + 点差，此后按固定利率线性计息）。

pub mod fixed_rate_model;
pub mod floating_rate_model;
