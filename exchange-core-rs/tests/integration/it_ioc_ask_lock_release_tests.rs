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

    #[test]
    fn unfilled_ioc_ask_releases_base_lock() {
        let mut api = setup();
        assert_eq!(api.place_order(ioc_ask(1, 600)), CommandResultCode::Success);

        assert_eq!(api.user_locked(UID, CUR_BASE), 0, "base lock released after settle");
        assert_eq!(
            api.user_account(UID, CUR_BASE) - api.user_locked(UID, CUR_BASE),
            1000,
            "available base fully restored"
        );
        assert_eq!(api.place_order(ioc_ask(2, 600)), CommandResultCode::Success);
    }

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
