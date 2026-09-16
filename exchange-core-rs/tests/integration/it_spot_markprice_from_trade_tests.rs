//! 验证"现货 markPrice 引擎自维护"设计：期货 markPrice 由外部 `MARKPRICE_ADJUSTMENT` 喂价，
//! 现货**不喂价**，而是在成交时由 R2 尾部 `apply_trade_price`（EMA）从成交价自维护——供现货抵押 loan 估值。
//!
//! 走生产路径（`submit(OrderCommand{ 带真 timestamp })`，非 facade `place_order`，后者 ts=0 是测试局限）：
//! 无任何外部喂价，一笔现货成交后 `mark_price` 自动变成成交价；并反证 ts 不单调（=0）时不推进（EMA 守卫）。

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
        api.add_user(1); // seller (base)
        api.add_user(2); // buyer (quote)
        api.balance_adjustment(1, BASE, 1_000, 1);
        api.balance_adjustment(2, QUOTE, 10_000_000, 2);
        api
    }

    /// 现货挂单（GTC），`timestamp` 由调用方（生产=Raft apply 层）显式设。
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

    #[test]
    fn spot_markprice_self_maintained_from_trade_no_external_feed() {
        let mut api = setup();

        // 未成交前：现货从未喂价 → 无 markPrice。
        assert_eq!(api.risk().mark_price(SPOT), None, "现货未成交前无 markPrice（不外部喂价）");

        // seller 挂 ASK（resting，无成交）→ 不产生成交价，markPrice 仍无。
        assert_eq!(api.submit(spot_order(1, 1, OrderAction::Ask, 1_000)), CommandResultCode::Success);
        assert_eq!(api.risk().mark_price(SPOT), None, "resting 挂单不产生成交，markPrice 仍无");

        // buyer 吃单 BID @PX（ts=2000 单调）→ 成交 → R2 尾部 apply_trade_price 从成交价自维护 markPrice。
        assert_eq!(api.submit(spot_order(2, 2, OrderAction::Bid, 2_000)), CommandResultCode::Success);
        assert_eq!(
            api.risk().mark_price(SPOT),
            Some(PX),
            "现货成交后 markPrice 应由引擎从成交价自维护（首 tick 直接采纳），全程无任何外部喂价"
        );
    }

    #[test]
    fn spot_markprice_not_advanced_when_timestamp_not_monotonic() {
        let mut api = setup();
        // 两笔挂单都用 ts=0：EMA 守卫 `ts <= mark_price_ts(0)` 命中 → markPrice 不推进（生产须传单调真 ts）。
        assert_eq!(api.submit(spot_order(1, 1, OrderAction::Ask, 0)), CommandResultCode::Success);
        assert_eq!(api.submit(spot_order(2, 2, OrderAction::Bid, 0)), CommandResultCode::Success);
        assert_eq!(
            api.risk().mark_price(SPOT),
            None,
            "ts=0 非单调 → apply_trade_price no-op → markPrice 不推进（印证现货 markPrice 依赖命令层单调 timestamp）"
        );
    }
}
