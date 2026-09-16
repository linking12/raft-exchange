//! 翻译自 Java `exchange.core2.tests.integration.ITFeeAuditRegression`（2 个 @Test）——锁定 audit 报告
//! H1 / H2 两处 fee 多扣 bug 的回归测试。逐字复刻 Java 黄金账户值 + 全局守恒。
//!
//!   H1: 动态费率下强平 fee 被 takerSize 倍放大（RiskEngine.collectLiquidationFee）。
//!       修复前 notional = ceil(takerSize × Σ(size×price) × liqFee / feeScaleK)；
//!       修复后 notional = ceil(Σ(size×price) × liqFee / feeScaleK)。差异 = takerSize 倍。
//!   H2: FOK_BUDGET 在 actualMatched < budget 时按 budget 估算的 takerFee 扣账，差额蒸发。
//!       修复后按实际成交均价算 takerFee，多扣差额退还用户。
//!
//! Java 用 `triggerLiquidation` + `LiquidationEngine::stop` 手动驱动强平；Rust `ExchangeApi` 改用
//! `enable_liquidation()` + `set_mark_price_at(...)` 触发定向扫描 + FORCE→IF→ADL 自动排空级联
//! （见 `src/core/liquidation_e2e_tests.rs` 的强平驱动约定）。强平的逐笔 fund event 走内部排空命令，
//! 不进 `last_fund_events()`，故断言最终**状态**（loser 全平 + loser 账户闭式值 + 全局守恒），
//! 不断言事件流。
//!
//! **未跳过任何 @Test**（2/2 已翻译）。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest};

    const USD: i32 = 840; // CURRENECY_USD
    const UID_1: i64 = 1;
    const UID_2: i64 = 2;
    const UID_3: i64 = 3;

    /// 全局守恒（== Java `totalBalanceReport().isGlobalBalancesAllZero()`）。
    fn assert_conserved(api: &ExchangeApi) {
        let tcb = api.total_balance();
        assert!(tcb.is_global_zero(), "全局守恒被打破: {:?}", tcb.global_balances_sum());
    }

    fn seed_user(api: &mut ExchangeApi, uid: i64, currency: i32, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, currency, amount, txid), CommandResultCode::Success);
    }

    #[allow(clippy::too_many_arguments)]
    fn place_fut(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        symbol: i32,
        price: i64,
        size: i64,
        action: OrderAction,
        margin_mode: MarginMode,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol,
            price,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage: 1, // == Java createBid/Ask 默认（base initMargin 率）
            margin_mode,
            reduce_only: false,
        })
    }

    // ================================================================================================
    // H1 — 动态费率强平 fee 不能被 takerSize 倍放大
    // ================================================================================================

    #[test]
    fn h1_liquidation_fee_dynamic_rate_not_amplified_by_taker_size() {
        const BASE_ID: i32 = 999;
        const SYM: i32 = 60001;
        // takerFee=100(1%) makerFee=50(0.5%) liquidationFee=100(1%)，feeScaleK=10000（>0 → 动态费率分支）。
        let spec = CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_ID,
            quote_currency: USD,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 100,
            maker_fee: 50,
            liquidation_fee: 100,
            fee_scale_k: 10_000,
            init_margin: 1,
            init_margin_scale_k: 100,
            maintenance_margin: BTreeMap::from([(1_000, 5), (1_000_000, 10)]),
            maintenance_margin_scale_k: 10,
            max_leverage: BTreeMap::from([(2_000, 5), (1_000_000, 10)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(spec), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM, 10_000), CommandResultCode::Success);

        let user_size = 10i64;
        let open_price = 10_000i64;
        let liquidation_price = 9_900i64;
        // 动态费率破产价 BP = ceil((openPriceSum − marginBase) × feeScaleK / (Q × (feeScaleK − takerFee − liqFee)))
        //                  = (100000 − 1000) × 10000 / (10 × (10000 − 100 − 100)) → ceil 10103。
        let bp_fill_price = 10_103i64;

        // 押金给足避免 NSF 干扰断言。
        seed_user(&mut api, UID_1, USD, 200_000, 1);
        seed_user(&mut api, UID_2, USD, 2_000_000, 2);
        seed_user(&mut api, UID_3, USD, 2_000_000, 3);

        // UID_1 多头开仓（BID 先挂 → loser 是 maker），UID_2 ASK 吃单。
        assert_eq!(place_fut(&mut api, 60101, UID_1, SYM, open_price, user_size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 60102, UID_2, SYM, open_price, user_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        // 强平接单流动性：UID_3 在 BP 处挂大额 BID（承接 loser 的 FORCE ASK）。价格仍 10000 时挂，静止在簿。
        assert_eq!(place_fut(&mut api, 60103, UID_3, SYM, bp_fill_price, user_size + 15, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        // 开启强平引擎 + 跌价触发定向扫描 → FORCE 全平 loser（对应 Java updateCurrentPriceTo + triggerLiquidation）。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price(SYM, liquidation_price), CommandResultCode::Success);

        // 算账（BP=10103，fill 均价 10103）：
        //   开仓 makerFee    = ceil(10 × 10000 × 50 / 10000)  = 500
        //   强平 PnL         = (10103 − 10000) × 10           = 1030（LONG close 在 BP 处小幅正 PnL）
        //   强平 closeFee    = ceil(10 × 10103 × 100 / 10000) = 1011
        //   强平 liquidationFee（H1 关注点，不得被 takerSize 放大）= ceil(10 × 10103 × 100 / 10000) = 1011
        //   final accounts   = 200000 − 500 + 1030 − 1011 − 1011 = 198508
        let expected_final = 200_000i64 - 500 + 1030 - 1011 - 1011;

        assert!(api.user_position(UID_1, SYM).is_none(), "loser 持仓应被全平");
        assert_eq!(api.user_account(UID_1, USD), expected_final, "强平 fee 不应被 takerSize 倍放大");
        assert_conserved(&api);
    }

    // ================================================================================================
    // H2 — FOK_BUDGET 在 actualMatched < budget 时 fee 差额必须退还
    // ================================================================================================

    #[test]
    fn h2_fok_budget_actual_matched_below_budget_refunds_fee_delta() {
        const BASE_ID: i32 = 998;
        const SYM: i32 = 60201;
        // 现货对，动态费率 takerFee=100(1%) makerFee=50(0.5%) feeScaleK=10000。
        let spec = CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE_ID,
            quote_currency: USD,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 100,
            maker_fee: 50,
            fee_scale_k: 10_000,
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_symbol(spec), CommandResultCode::Success);

        let user_quote_deposit = 1_000_000i64;
        let maker_base_deposit = 100i64;
        seed_user(&mut api, UID_1, USD, user_quote_deposit, 1);
        seed_user(&mut api, UID_2, BASE_ID, maker_base_deposit, 2);

        // Maker ASK 10 @ 120（单价 120，低于 taker 隐含均价 150）。
        let maker_size = 10i64;
        let maker_price = 120i64;
        assert_eq!(
            api.place_order(PlaceOrderRequest {
                order_id: 60301,
                uid: UID_2,
                symbol: SYM,
                price: maker_price,
                size: maker_size,
                reserve_bid_price: maker_price,
                action: OrderAction::Ask,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::Success
        );

        // Taker FOK_BUDGET：要 10 件，budget = 1500（price 字段 = 总预算，隐含均价 150 > maker 实际 120）。
        let taker_size = 10i64;
        let budget = 1_500i64;
        assert_eq!(
            api.place_order(PlaceOrderRequest {
                order_id: 60302,
                uid: UID_1,
                symbol: SYM,
                price: budget,
                size: taker_size,
                reserve_bid_price: budget,
                action: OrderAction::Bid,
                order_type: OrderType::FokBudget,
            }),
            CommandResultCode::Success
        );

        // 数学：actualMatched = 10 × 120 = 1200；actualFee = ceil(1200 × 100 / 10000) = 12；
        //       fee_held = ceil(1500 × 100 / 10000) = 15；差额 = 3 必须退。
        //       修复后用户实付 = 1200 + 12 = 1212。
        let expected_paid = 1_200i64 + 12;
        let expected_accounts = user_quote_deposit - expected_paid;

        assert_eq!(api.user_locked(UID_1, USD), 0, "FOK_BUDGET 全成后 exchangeLocked 必须归零");
        assert_eq!(api.user_account(UID_1, USD), expected_accounts, "用户实付按成交均价算 fee，不按 budget 估算");
        assert_eq!(api.user_account(UID_1, BASE_ID), taker_size, "base 收到 takerSize");
        assert_conserved(&api);
    }
}
