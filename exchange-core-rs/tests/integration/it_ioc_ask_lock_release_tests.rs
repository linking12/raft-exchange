// Java ITIocAskLockRelease 的 Rust 对拍翻译(防线①)。
//
// Java 侧定性:未成交(空簿)IOC 现货 ASK 的 base 锁在管线 settle 之后确实完整释放(逻辑正确),
// 但两条 IOC ASK 之间不 flush 直接连提,第二条会读到第一条尚未生效(R2 滞后)的 exchangeLocked
// -> spurious RISK_NSF(Disruptor 批处理已知时序 hazard,用 barrier 规避,非引擎逻辑错)。
// Rust 移植是单线程同步管线(R2 恒先于下条 R1),无此 hazard —— 两条连提都成功。
// 详见 CONSISTENCY.md §7.2。
#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceOrderRequest};

    const CUR_BASE: i32 = 1;
    const CUR_QUOTE: i32 = 2;
    const SYM: i32 = 100;
    const UID: i64 = 2;

    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(CUR_BASE, 1);
        api.add_currency(CUR_QUOTE, 1);
        let spec = CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: CUR_BASE,
            quote_currency: CUR_QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            ..Default::default()
        };
        assert_eq!(api.add_symbol(spec), CommandResultCode::Success);
        assert_eq!(api.add_user(UID), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID, CUR_BASE, 1000, 1), CommandResultCode::Success);
        api
    }

    fn ioc_ask(order_id: i64, size: i64) -> PlaceOrderRequest {
        PlaceOrderRequest {
            order_id,
            uid: UID,
            symbol: SYM,
            price: 10000,
            size,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Ioc,
        }
    }

    // 对齐 Java unfilledIocAskReleasesBaseLock:空簿 IOC ASK 未成交,settle 后 base 锁完整释放。
    #[test]
    fn unfilled_ioc_ask_releases_base_lock() {
        let mut api = setup();
        assert_eq!(api.place_order(ioc_ask(1, 600)), CommandResultCode::Success);
        // Rust 单管线:process_command 内联跑完 R2,锁已释放,无需 report barrier。
        assert_eq!(api.user_locked(UID, CUR_BASE), 0, "base lock released after settle");
        assert_eq!(
            api.user_account(UID, CUR_BASE) - api.user_locked(UID, CUR_BASE),
            1000,
            "available base fully restored"
        );
        assert_eq!(api.place_order(ioc_ask(2, 600)), CommandResultCode::Success);
    }

    // 对齐 Java consecutiveIocAskWithoutFlushHazard,但断言 Rust 无 hazard:
    // Java 批处理 R1/R2 lag -> 第二条 spurious RISK_NSF;Rust 单管线两条连提都成功。
    #[test]
    fn consecutive_ioc_ask_no_r1_r2_hazard() {
        let mut api = setup();
        assert_eq!(api.place_order(ioc_ask(1, 600)), CommandResultCode::Success);
        assert_eq!(
            api.place_order(ioc_ask(2, 600)),
            CommandResultCode::Success,
            "Rust single-pipeline settles R2 before next R1: consecutive IOC ASK must NOT spurious-NSF (Java hazard, CONSISTENCY §7.2)"
        );
    }
}
