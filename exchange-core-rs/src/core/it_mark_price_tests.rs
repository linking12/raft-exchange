//! 翻译自 Java `exchange.core2.tests.integration.ITExchangeCoreMarkPrice`。
//! 直连 Rust 引擎，逐条对拍 Java 黄金值：无标记价时期货拒单/现货放行、分档杠杆按 notional 校验 +
//! 标记价驱动的 `open_init_margin_sum` 记账。
//!
//! 未翻译（依赖 façade 未暴露的能力，逐条说明）：
//! - `testMarkPrice`：需 `updateCurrentPriceTo` + `triggerLiquidation`，且断言 `unrealizedProfit`/
//!   `liquidationPrice`/`marginRatioScaleK` 三个 report 级派生字段（`SymbolPositionRecord` 上只有
//!   `estimate_*` 方法，report 级 total_margin 无门面访问）→ 跳过。
//! - `testInitMarginAndMaintenanceMargin` / `testTieredMaintenanceMargin`：结尾均以
//!   `updateCurrentPriceTo` + `triggerLiquidation` 收束强平，`ExchangeApi` 无强平触发方法 → 跳过。
//!
//! `testTieredLeverage` 的 `marginRatioScaleK` 子断言略去（report 级 total_margin 未暴露），
//! 保留其杠杆分档校验 + `open_init_margin_sum` 记账（数值与 Java 一致）。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest};

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    const BNB_ID: i32 = 11;
    const USDT_ID: i32 = 12;

    /// 对应 Java 字段 `symbol`：10001 perpetual、maker/taker=0、maintenanceMargin{10000:5,100000:10}@scaleK1000、
    /// maxLeverage{10000:75,100000:40}。
    fn symbol_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 10001,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(10_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(10_000, 75), (100_000, 40)]),
            ..Default::default()
        }
    }

    fn place_fut(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        symbol: i32,
        price: i64,
        size: i64,
        action: OrderAction,
        ot: OrderType,
        leverage: i32,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol,
            price,
            size,
            action,
            order_type: ot,
            leverage,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        })
    }

    fn create_user_with_money(api: &mut ExchangeApi, uid: i64, currency: i32, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, currency, amount, txid), CommandResultCode::Success);
    }

    // 没有标记价时不允许下期货单 → RISK_MARKPRICE_NOT_AVAILABLE；设置标记价后放行。
    #[test]
    fn test_submit_fail_when_no_mark_price() {
        let spec = symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);

        // 未设置 mark price → 拒单。
        assert_eq!(
            place_fut(&mut api, 101, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::RiskMarkpriceNotAvailable
        );
        // 设置 mark price 后重下同一 orderId → 成功。
        assert_eq!(api.set_mark_price(spec.symbol_id, 650), CommandResultCode::Success);
        assert_eq!(
            place_fut(&mut api, 101, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
    }

    // 没有标记价时允许下现货单（现货不依赖 mark price）→ 成功。
    #[test]
    fn test_submit_pass_when_no_mark_price() {
        // 对应 Java `initExchangeSymbols().get(0)`（BTC_USDT 现货）；scale_k=1，费率对本例行为无关。
        let spot = CoreSymbolSpecification {
            symbol_id: 10003,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_symbol(spot.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);

        assert_eq!(
            api.place_order(PlaceOrderRequest {
                order_id: 101,
                uid: UID_1,
                symbol: spot.symbol_id,
                price: 1000,
                size: 1,
                reserve_bid_price: 1000,
                action: OrderAction::Bid,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::Success
        );
    }

    // 分档 maxLeverage：<=10w 可 75x，>10w 只能 40x；开 100 手 @75x → 再开 @75x 触档拒单，
    // 调 40x 后开 1 手 → openInitMarginSum 分档累加 = 1358。
    #[test]
    fn test_tiered_leverage() {
        let spec = symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 500_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 500_000, 20);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);

        // 开 10w（100 手 @1000），75x。
        assert_eq!(
            place_fut(&mut api, 10000, UID_1, spec.symbol_id, 1000, 100, OrderAction::Bid, OrderType::Gtc, 75),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 10001, UID_2, spec.symbol_id, 1000, 100, OrderAction::Ask, OrderType::Gtc, 10),
            CommandResultCode::Success
        );

        // 再挂 1 手：notional 超过 10w，75x 触档 → RISK_INVALID_LEVERAGE。
        assert_eq!(
            place_fut(&mut api, 10002, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 75),
            CommandResultCode::RiskInvalidLeverage
        );

        // 调 40x 后开 1 手。
        assert_eq!(api.leverage_adjustment(UID_1, spec.symbol_id, 40), CommandResultCode::Success);
        assert_eq!(
            place_fut(&mut api, 10003, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 40),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 10004, UID_2, spec.symbol_id, 1000, 1, OrderAction::Ask, OrderType::Gtc, 10),
            CommandResultCode::Success
        );

        // openInitMarginSum = 1000*100/75 + 1000*1/40 = 1333 + 25 = 1358。
        let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
        assert_eq!(pos.open_init_margin_sum, 1358);
        assert_eq!(pos.pending_buy_size, 0);
        // marginRatioScaleK（Java 断 375）依赖 report 级 total_margin，门面未暴露 → 略去。
    }
}
