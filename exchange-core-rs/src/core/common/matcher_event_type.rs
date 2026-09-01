//! 对应 Java: exchange.core2.core.common.MatcherEventType。码值与 Java 严格一致。

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MatcherEventType {
    Trade,
    Reject,
    Reduce,
    BinaryEvent,
}
