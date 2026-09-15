//! 翻译自 Java `exchange.core2.tests.integration.ITExchangeCoreHedgeMode`。
//!
//! 关键约束：`ExchangeApi` 门面**不提供** `POSITION_MODE_ADJUSTMENT`（切 HEDGE）公开方法，
//! 且只读访问器 `user_position(uid, symbol)` 按 ONEWAY 单键取仓（不处理 HEDGE 的 ±symbol 双腿键）。
//! 因此本文件里所有需要「切双向持仓」的用例均**不可翻译（needs harness extension）**，逐条列在下方。
//! 唯一保留下来的是 `testDefaultSingleDirection`——它刻意停留在默认 ONEWAY 模式，验证反向单只抵消
//! 而非新开仓位，正好落在门面能力范围内。
//!
//! 未翻译清单（原因）：
//! - `testChangePositionMode` / `testCannotChangeModeWithPosition` / `testDualPositionSign`
//!   / `testMarginHedgeMode` / `testHedgeModeMatch` / `testHedgeOpenSecondDirection...`
//!   / `testAddExtraMarginToDualPosition` / `testAdjustLeverageDual`：需 `POSITION_MODE_ADJUSTMENT`
//!   切 HEDGE + HEDGE 双腿仓位读取 → needs harness extension。
//! - `testMixedFundingRate`：额外需 `SETTLE_FUNDINGFEES` 命令 → needs harness extension。
//! - `testSettlePnl`：额外需 `SETTLE_PNL` 命令 → needs harness extension。
//! - `testTotalBalance` / `testHedgeModeSelfMatchConservation` / `testHedgeOpenSecond...`：
//!   额外需 `totalBalanceReport()`（全局守恒报表）→ 门面无访问器。
//! - `testLiquidationLoop` / `testLiquidationLoop2` / `testLiquidationLoop3`：额外需
//!   `triggerLiquidation()`（`LIQUIDATION_SCAN` + 启动强平引擎）→ 门面无对应方法。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::position_direction::PositionDirection;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest};

    // 对应 Java TestConstants。
    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;
    const UID_3: i64 = 1_440_003;

    const BNB_ID: i32 = 11;
    const USDT_ID: i32 = 12;

    // 对应 Java `BNB_USDT`：perpetual、baseScaleK=1e3、quoteScaleK=1e5、maker/taker=0、
    // maintenanceMargin{1e12:5}@scaleK1000、maxLeverage{1e12:75}。
    const SYMBOL_ID: i32 = 10001;
    const BASE_SCALE_K: i64 = 1_000; // pow10(3)
    const QUOTE_SCALE_K: i64 = 100_000; // pow10(5)

    fn bnb_usdt_spec() -> CoreSymbolSpecification {
        let tier_notional = 10_000 * 100_000_000; // 10_000 * pow10(3+5) = 1e12
        CoreSymbolSpecification {
            symbol_id: SYMBOL_ID,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            base_scale_k: BASE_SCALE_K,
            quote_currency: USDT_ID,
            quote_scale_k: QUOTE_SCALE_K,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(tier_notional, 5)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(tier_notional, 75)]),
            ..Default::default()
        }
    }

    fn place_fut(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        price: i64,
        size: i64,
        action: OrderAction,
        leverage: i32,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: SYMBOL_ID,
            price,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        })
    }

    /// 对应 Java `initUsersAndSymbol`：注册 BNB(digit8→1e8)/USDT(digit6→1e6)、BNB_USDT、mark 750、
    /// UID_1..UID_3 各充值 10000*USDT.currencyScaleK(1e6)。
    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 100_000_000); // BNB digit 8
        api.add_currency(USDT_ID, 1_000_000); // USDT digit 6
        assert_eq!(api.add_futures_symbol(bnb_usdt_spec()), CommandResultCode::Success);

        let mark_price = 750 * QUOTE_SCALE_K; // 75_000_000
        assert_eq!(api.set_mark_price(SYMBOL_ID, mark_price), CommandResultCode::Success);

        let deposit = 10_000 * 1_000_000; // 10000 * USDT currencyScaleK
        for (i, uid) in [UID_1, UID_2, UID_3].into_iter().enumerate() {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(
                api.balance_adjustment(uid, USDT_ID, deposit, 100 + i as i64),
                CommandResultCode::Success
            );
        }
        api
    }

    // 测试1: 默认单向持仓（ONEWAY）——反向开单只抵消已有持仓，不新开第二个仓位。
    #[test]
    fn test_default_single_direction() {
        let mut api = setup();

        let size = 2 * BASE_SCALE_K; // 2000
        let price = 750 * QUOTE_SCALE_K; // 75_000_000

        // UID_1 BID + UID_2 ASK 完全撮合 → UID_1 开 LONG。
        assert_eq!(place_fut(&mut api, 10001, UID_1, price, size, OrderAction::Bid, 10), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10002, UID_2, price, size, OrderAction::Ask, 10), CommandResultCode::Success);

        // ONEWAY 下每 symbol 恒一条仓位记录；方向 LONG、openVolume=2000。
        {
            let pos = api.user_position(UID_1, SYMBOL_ID).expect("UID_1 应有一条 LONG 仓位");
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.open_volume, size);
        }

        // UID_1 尝试反向 ASK（无对手盘 → resting pending）；ONEWAY 下仍是同一条记录，方向仍 LONG。
        let size2 = 1 * BASE_SCALE_K; // 1000
        let price2 = 780 * QUOTE_SCALE_K; // 78_000_000
        assert_eq!(place_fut(&mut api, 10003, UID_1, price2, size2, OrderAction::Ask, 10), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, SYMBOL_ID).expect("仍是同一条仓位记录");
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.open_volume, size); // 尚未成交，持仓不变
            assert_eq!(pos.pending_sell_size, size2); // 反向单仅 pending
        }

        // UID_3 BID 吃掉 UID_1 的 resting ASK → 抵消 UID_1 的 LONG（2000-1000=1000），仍 LONG、仍一条记录。
        assert_eq!(place_fut(&mut api, 10004, UID_3, price2, size2, OrderAction::Bid, 10), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, SYMBOL_ID).expect("反向成交后仍持 LONG");
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.open_volume, size - size2); // 1000
            assert_eq!(pos.pending_sell_size, 0);
        }
    }
}
