pub const WINDOW_MS: i64 = 15_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LastPriceCacheRecord {
    pub ask_price: i64,
    pub bid_price: i64,
    pub mark_price: i64,
    pub mark_price_ts: i64,
}

impl LastPriceCacheRecord {

    pub fn new() -> Self {
        LastPriceCacheRecord { ask_price: i64::MAX, bid_price: 0, mark_price: 0, mark_price_ts: 0 }
    }

    pub fn with_mark(mark_price: i64) -> Self {
        LastPriceCacheRecord { ask_price: i64::MAX, bid_price: 0, mark_price, mark_price_ts: 0 }
    }

    pub fn apply_trade_price(&mut self, ts: i64, price: i64) {
        if price <= 0 || ts <= self.mark_price_ts {
            return;
        }
        let dt = ts - self.mark_price_ts;
        self.mark_price = if self.mark_price <= 0 || dt >= WINDOW_MS {
            price
        } else {

            ((self.mark_price as i128 * (WINDOW_MS - dt) as i128 + price as i128 * dt as i128)
                / WINDOW_MS as i128) as i64
        };
        self.mark_price_ts = ts;
    }

    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.ask_price);
        h = h.wrapping_mul(31).wrapping_add(self.bid_price);
        h = h.wrapping_mul(31).wrapping_add(self.mark_price);
        h = h.wrapping_mul(31).wrapping_add(self.mark_price_ts);
        ((h >> 32) as i32) ^ (h as i32)
    }
}

impl Default for LastPriceCacheRecord {
    fn default() -> Self {
        LastPriceCacheRecord::new()
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for crate::core::common::last_price_cache_record::LastPriceCacheRecord {
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i64(self.ask_price);
        w.write_i64(self.bid_price);
        w.write_i64(self.mark_price);
        w.write_i64(self.mark_price_ts);
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        Ok(crate::core::common::last_price_cache_record::LastPriceCacheRecord {
            ask_price: r.read_i64()?,
            bid_price: r.read_i64()?,
            mark_price: r.read_i64()?,
            mark_price_ts: r.read_i64()?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_matches_java_defaults() {
        let r = LastPriceCacheRecord::new();
        assert_eq!(r.ask_price, i64::MAX);
        assert_eq!(r.bid_price, 0);
        assert_eq!(r.mark_price, 0);
        assert_eq!(r.mark_price_ts, 0);
    }

    #[test]
    fn apply_trade_price_first_tick_adopts_price_directly() {
        let mut r = LastPriceCacheRecord::new();
        r.apply_trade_price(1_000, 100);
        assert_eq!(r.mark_price, 100);
        assert_eq!(r.mark_price_ts, 1_000);
    }

    #[test]
    fn apply_trade_price_ignores_non_positive_price() {
        let mut r = LastPriceCacheRecord::new();
        r.apply_trade_price(1_000, 0);
        assert_eq!(r.mark_price, 0);
        assert_eq!(r.mark_price_ts, 0);
        r.apply_trade_price(1_000, -5);
        assert_eq!(r.mark_price, 0);
        assert_eq!(r.mark_price_ts, 0);
    }

    #[test]
    fn apply_trade_price_ignores_stale_timestamp() {
        let mut r = LastPriceCacheRecord::new();
        r.apply_trade_price(1_000, 100);
        r.apply_trade_price(999, 200);
        assert_eq!(r.mark_price, 100);
        assert_eq!(r.mark_price_ts, 1_000);
        r.apply_trade_price(1_000, 200);
        assert_eq!(r.mark_price, 100);
    }

    #[test]
    fn apply_trade_price_blends_within_window() {
        let mut r = LastPriceCacheRecord::new();
        r.apply_trade_price(1, 100);
        r.apply_trade_price(5_001, 200);
        assert_eq!(r.mark_price, 133);
        assert_eq!(r.mark_price_ts, 5_001);
    }

    #[test]
    fn apply_trade_price_dt_at_or_beyond_window_adopts_price_directly() {
        let mut r = LastPriceCacheRecord::new();
        r.apply_trade_price(1, 100);
        r.apply_trade_price(1 + WINDOW_MS, 300);
        assert_eq!(r.mark_price, 300);

        let mut r2 = LastPriceCacheRecord::new();
        r2.apply_trade_price(1, 100);
        r2.apply_trade_price(1 + WINDOW_MS + 1, 300);
        assert_eq!(r2.mark_price, 300);
    }

    #[test]
    fn apply_trade_price_blend_no_overflow_on_large_price() {
        let mut r = LastPriceCacheRecord::new();
        r.apply_trade_price(1, i64::MAX / 2);
        r.apply_trade_price(2, i64::MAX / 2);
        assert_eq!(r.mark_price, i64::MAX / 2);
    }

    #[test]
    fn state_hash_deterministic_and_sensitive_to_each_field() {
        let a = LastPriceCacheRecord::with_mark(3);
        let b = LastPriceCacheRecord::with_mark(3);
        assert_eq!(a.state_hash(), b.state_hash());

        let mut diff_ask = a;
        diff_ask.ask_price = 9;
        assert_ne!(a.state_hash(), diff_ask.state_hash());

        let mut diff_bid = a;
        diff_bid.bid_price = 9;
        assert_ne!(a.state_hash(), diff_bid.state_hash());

        let diff_mark = LastPriceCacheRecord::with_mark(9);
        assert_ne!(a.state_hash(), diff_mark.state_hash());

        let mut diff_ts = a;
        diff_ts.mark_price_ts = 9;
        assert_ne!(a.state_hash(), diff_ts.state_hash());
    }
}
