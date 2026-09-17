#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LiquidationFlow {
    pub state: LiquidationState,
    pub bankruptcy_price: i64,
    pub size: i64,
    pub original_order_id: i64,
}

impl LiquidationFlow {
    pub fn new(bankruptcy_price: i64, size: i64, original_order_id: i64) -> Self {
        LiquidationFlow { state: LiquidationState::Liquidating, bankruptcy_price, size, original_order_id }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LiquidationState {
    Liquidating,
    WaitIfExecution,
    WaitAdlExecution,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_starts_in_liquidating_state() {
        let f = LiquidationFlow::new(100, 50, 7);
        assert_eq!(f.state, LiquidationState::Liquidating);
        assert_eq!(f.bankruptcy_price, 100);
        assert_eq!(f.size, 50);
        assert_eq!(f.original_order_id, 7);
    }

    #[test]
    fn is_copy_value_type() {
        let a = LiquidationFlow::new(1, 2, 3);
        let b = a;
        assert_eq!(a, b);
    }
}
