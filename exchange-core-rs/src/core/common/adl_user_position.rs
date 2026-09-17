#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AdlUserPosition {
    pub uid: i64,
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
