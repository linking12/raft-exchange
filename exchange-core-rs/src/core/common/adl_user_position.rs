//! 对应 Java `exchange.core2.core.common.ADLUserPosition`。ADL(自动减仓)决策/执行
//! 用的轻量仓位视图:R1 构建、ME 选取、R2 落地。
//! Java 侧还带 symbol/direction/score/next(链式)字段用于 ME 排序;这里只保留
//! 执行阶段必需的 uid 与本次可贡献数量。

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AdlUserPosition {
    /// 对应 Java `uid`:被减仓对手方用户。
    pub uid: i64,
    /// 对应 Java `volume`:本次 ADL 中该仓位可贡献的最大数量。
    pub volume: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_plain_copy_value_type() {
        let a = AdlUserPosition { uid: 1, volume: 5 };
        let b = a;
        assert_eq!(a, b);
    }
}
