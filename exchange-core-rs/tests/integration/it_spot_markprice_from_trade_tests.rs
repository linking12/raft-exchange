//! 本文件无对应 Java 测试类，验证的是 Rust 引擎特有的现货 markPrice 自维护行为：现货撮合引擎在每次
//! 成交后自动从成交价更新 markPrice（无需外部喂价），而 Java 侧现货撮合不做这个回写，这是刻意的实现差异。
#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::ExchangeApi;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SPOT: i32 = 100;
    const PX: i64 = 20_000;

    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(
            api.add_symbol(CoreSymbolSpecification {
                symbol_id: SPOT,
                symbol_type: SymbolType::CurrencyExchangePair,
                base_currency: BASE,
                quote_currency: QUOTE,
                base_scale_k: 1,
                quote_scale_k: 1,
                taker_fee: 0,
                maker_fee: 0,
                ..Default::default()
            }),
            CommandResultCode::Success
        );
        api.add_user(1);
        api.add_user(2);
        api.balance_adjustment(1, BASE, 1_000, 1);
        api.balance_adjustment(2, QUOTE, 10_000_000, 2);
        api
    }

    fn spot_order(oid: i64, uid: i64, action: OrderAction, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: oid,
            uid,
            symbol: SPOT,
            price: PX,
            size: 1,
            reserve_bid_price: if action == OrderAction::Bid { PX } else { 0 },
            action: Some(action),
            order_type: Some(OrderType::Gtc),
            timestamp: ts,
            ..Default::default()
        }
    }

    // 验证现货 markPrice 完全由撮合引擎从成交价自维护：未成交前无 markPrice，挂单不产生成交时 markPrice
    // 仍为空，首次成交后立即采纳成交价，全程不依赖任何外部喂价命令。
    #[test]
    fn spot_markprice_self_maintained_from_trade_no_external_feed() {
        let mut api = setup();

        assert_eq!(api.risk().mark_price(SPOT), None, "no markPrice before any spot trade (no external feed)");

        assert_eq!(api.submit(spot_order(1, 1, OrderAction::Ask, 1_000)), CommandResultCode::Success);
        assert_eq!(api.risk().mark_price(SPOT), None, "a resting order produces no trade, markPrice still absent");

        assert_eq!(api.submit(spot_order(2, 2, OrderAction::Bid, 2_000)), CommandResultCode::Success);
        assert_eq!(
            api.risk().mark_price(SPOT),
            Some(PX),
            "after a spot trade, markPrice should be self-maintained by the engine from the trade price (adopted on the first tick), with no external feed involved at all"
        );
    }

    // 验证现货 markPrice 的自维护依赖命令层的单调递增 timestamp：当两笔命令的 timestamp 都为 0（非单调）
    // 时，apply_trade_price 应是 no-op，markPrice 不会被推进。
    #[test]
    fn spot_markprice_not_advanced_when_timestamp_not_monotonic() {
        let mut api = setup();
        assert_eq!(api.submit(spot_order(1, 1, OrderAction::Ask, 0)), CommandResultCode::Success);
        assert_eq!(api.submit(spot_order(2, 2, OrderAction::Bid, 0)), CommandResultCode::Success);
        assert_eq!(
            api.risk().mark_price(SPOT),
            None,
            "ts=0 is not monotonic → apply_trade_price is a no-op → markPrice does not advance (confirms spot markPrice relies on monotonic command-layer timestamps)"
        );
    }
}
