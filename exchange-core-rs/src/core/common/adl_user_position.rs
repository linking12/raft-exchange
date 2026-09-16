//! 对应 Java `ADLUserPosition`：ADL 候选视图；Java 侵入式链表+对象池不移植，改用 `Vec`。

/// 对应 Java `ADLUserPosition`。仅保留下游 R1 预占 / R2 释放真正读取的 `uid` + `volume`；
/// Java 的 `symbol`/`direction`/`score` 在本移植里死字段（symbol/direction 由外层 cmd 上下文导出，score 仅在
/// 构造前的本地排序用），故不携带。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AdlUserPosition {
    pub uid: i64,
    /// 本次 ADL 中该仓位可贡献的最大数量（R1 预占量 = 对称释放量，不管 merge 实际消费多少）。
    pub volume: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_plain_copy_value_type() {
        let a = AdlUserPosition { uid: 1, volume: 5 };
        let b = a; // Copy，不是 move-then-error
        assert_eq!(a, b);
    }
}
