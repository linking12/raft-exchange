//! 对应 Java `LastPriceCacheRecord`：每 symbol 最新价快照，进 raft snapshot、参与 state hash；期货由外部喂价，现货由 [`apply_trade_price`] 维护。Java `markPrice`/`markPriceTs` 本移植改名 `last_price`/`last_price_ts`。

/// 对应 Java `LastPriceCacheRecord.WINDOW_MS`：15 秒滑动混合窗口。
pub const WINDOW_MS: i64 = 15_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LastPriceCacheRecord {
    pub ask_price: i64,
    pub bid_price: i64,
    /// 对应 Java `markPrice`：期货由外部喂价，现货由 [`apply_trade_price`] 滑动混合维护。
    pub last_price: i64,
    /// 对应 Java `markPriceTs`：`last_price` 最近一次更新的时间戳。
    pub last_price_ts: i64,
}

impl LastPriceCacheRecord {
    /// 对应 Java 无参构造：`askPrice=MAX_VALUE, bidPrice=0, markPrice=0, markPriceTs=0`。
    pub fn new() -> Self {
        LastPriceCacheRecord { ask_price: i64::MAX, bid_price: 0, last_price: 0, last_price_ts: 0 }
    }

    /// 对应 Java `LastPriceCacheRecord(long, long, long)`：`markPriceTs` 缺省 0。
    pub fn with_prices(ask_price: i64, bid_price: i64, last_price: i64) -> Self {
        LastPriceCacheRecord { ask_price, bid_price, last_price, last_price_ts: 0 }
    }

    /// 对应 Java `applyTradePrice(long, long)`：现货专用，成交价滑动混合维护 `markPrice`；过期/非法输入 no-op，首次或超窗口直接采纳，否则按时间占比线性混合。
    pub fn apply_trade_price(&mut self, ts: i64, price: i64) {
        if price <= 0 || ts <= self.last_price_ts {
            return;
        }
        let dt = ts - self.last_price_ts;
        self.last_price = if self.last_price <= 0 || dt >= WINDOW_MS {
            price
        } else {
            (self.last_price * (WINDOW_MS - dt) + price * dt) / WINDOW_MS
        };
        self.last_price_ts = ts;
    }

    /// 对应 Java `stateHash()`，风格对齐 `UserProfile::state_hash`；不保证与 Java 数值相等，仅保证同态同 hash。
    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.ask_price);
        h = h.wrapping_mul(31).wrapping_add(self.bid_price);
        h = h.wrapping_mul(31).wrapping_add(self.last_price);
        h = h.wrapping_mul(31).wrapping_add(self.last_price_ts);
        ((h >> 32) as i32) ^ (h as i32)
    }
}

impl Default for LastPriceCacheRecord {
    fn default() -> Self {
        LastPriceCacheRecord::new()
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
        assert_eq!(r.last_price, 0);
        assert_eq!(r.last_price_ts, 0);
    }

    #[test]
    fn apply_trade_price_first_tick_adopts_price_directly() {
        let mut r = LastPriceCacheRecord::new();
        r.apply_trade_price(1_000, 100);
        assert_eq!(r.last_price, 100);
        assert_eq!(r.last_price_ts, 1_000);
    }

    #[test]
    fn apply_trade_price_ignores_non_positive_price() {
        let mut r = LastPriceCacheRecord::new();
        r.apply_trade_price(1_000, 0);
        assert_eq!(r.last_price, 0);
        assert_eq!(r.last_price_ts, 0);
        r.apply_trade_price(1_000, -5);
        assert_eq!(r.last_price, 0);
        assert_eq!(r.last_price_ts, 0);
    }

    #[test]
    fn apply_trade_price_ignores_stale_timestamp() {
        let mut r = LastPriceCacheRecord::new();
        r.apply_trade_price(1_000, 100);
        r.apply_trade_price(999, 200); // ts <= last_price_ts：忽略
        assert_eq!(r.last_price, 100);
        assert_eq!(r.last_price_ts, 1_000);
        r.apply_trade_price(1_000, 200); // ts == last_price_ts：忽略
        assert_eq!(r.last_price, 100);
    }

    #[test]
    fn apply_trade_price_blends_within_window() {
        let mut r = LastPriceCacheRecord::new();
        // ts=0 会命中默认 last_price_ts=0 的 no-op 分支，故起始 tick 用 ts=1。
        r.apply_trade_price(1, 100);
        // dt=5000 < WINDOW_MS=15000: (100*(15000-5000) + 200*5000) / 15000 = (1_000_000+1_000_000)/15000=133
        r.apply_trade_price(5_001, 200);
        assert_eq!(r.last_price, 133);
        assert_eq!(r.last_price_ts, 5_001);
    }

    #[test]
    fn apply_trade_price_dt_at_or_beyond_window_adopts_price_directly() {
        let mut r = LastPriceCacheRecord::new();
        r.apply_trade_price(1, 100);
        r.apply_trade_price(1 + WINDOW_MS, 300); // dt == WINDOW_MS
        assert_eq!(r.last_price, 300);

        let mut r2 = LastPriceCacheRecord::new();
        r2.apply_trade_price(1, 100);
        r2.apply_trade_price(1 + WINDOW_MS + 1, 300); // dt > WINDOW_MS
        assert_eq!(r2.last_price, 300);
    }

    #[test]
    fn state_hash_deterministic_and_sensitive_to_each_field() {
        let a = LastPriceCacheRecord::with_prices(1, 2, 3);
        let b = LastPriceCacheRecord::with_prices(1, 2, 3);
        assert_eq!(a.state_hash(), b.state_hash());

        let diff_ask = LastPriceCacheRecord::with_prices(9, 2, 3);
        assert_ne!(a.state_hash(), diff_ask.state_hash());

        let diff_bid = LastPriceCacheRecord::with_prices(1, 9, 3);
        assert_ne!(a.state_hash(), diff_bid.state_hash());

        let diff_last = LastPriceCacheRecord::with_prices(1, 2, 9);
        assert_ne!(a.state_hash(), diff_last.state_hash());

        let mut diff_ts = LastPriceCacheRecord::with_prices(1, 2, 3);
        diff_ts.last_price_ts = 9;
        assert_ne!(a.state_hash(), diff_ts.state_hash());
    }
}
