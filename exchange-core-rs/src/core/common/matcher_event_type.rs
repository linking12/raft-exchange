#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MatcherEventType {
    Trade,
    Reject,
    Reduce,
    BinaryEvent,
}
