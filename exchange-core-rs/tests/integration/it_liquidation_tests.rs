//! 翻译自 Java `exchange.core2.tests.integration.ITLiquidationIntegration`（13 个 @Test）——期货强平
//! 集成测试。Java 用多分片（`riskEnginesNum=8`）+ `LiquidationEngine::stop`/`triggerLiquidation`/
//! `updateCurrentPriceTo`/`groupingControl` 驱动跨 shard 强平；Rust `ExchangeApi` 是**单分片**门面，改用
//! `enable_liquidation()` + `set_mark_price_at(sym, adverse, ts)` 触发定向扫描 + FORCE→IF→ADL 自动排空级联
//! （见 `src/core/liquidation_e2e_tests.rs`）。
//!
//! 强平的逐笔 fund event 走内部排空命令、`last_fund_events()` 不捕获，故断言最终**状态**：被强平者仓位
//! 移除 / 减仓、保证金模式差异（ISOLATED 被平、CROSS 存活）、流动性提供者挂单被消耗、全局守恒
//! `total_balance().is_global_zero()`。跨 shard 负载分布、分片计数等**多分片专属断言无法在单分片复刻**，
//! 相应测试要么按单分片语义翻译核心行为、要么跳过（见下）。
//!
//! 期货 symbol 复刻 Java `initFutureSymbols()`：BTC(10000, XBT/USD, maker10/taker20 固定费) +
//! ETH(10001, ETH/USD, maker1/taker2 比例费 feeScaleK100)，均 MM{1000:5,100000:10}@scaleK1000,
//! maxLeverage{2000:5,100000:10}, initMargin1/scaleK100。货币 digit0→scaleK1。leverage=1 == Java
//! createBid/Ask 默认（base initMargin 率）。
//!
//! **未翻译的 @Test（及原因）——共 5 个跳过**：
//!   - `testShardingLoadBalance`：纯多分片负载均衡分布断言（shard0/shard1 强平数差值 ≤ 阈值），单分片无意义。
//!   - `testUsersServiceSharding`：仅验证用户跨 shard 存在（`riskEnginesNum=8` 分片配置），单分片无意义。
//!   - `testSymbolIndexMaintainedThroughOpenAndClose` / `testSymbolIndexRebuiltByUpdateProvider`：断言
//!     `LiquidationEngine.symbolToUsers` 索引内部结构 / `updateProvider` 重建，`ExchangeApi` 未暴露引擎索引内省。
//!   - `testCrossShardCounterpartyLiquidation`：HEDGE 流动性提供者 + 跨 shard 双 symbol 对手单强平，依赖多分片
//!     独立性 + HEDGE 双腿 + report `pendingBuy/SellSize` 组合断言，单分片 + `user_position` 单键无法完整复刻。
//!   其余 8 个已翻译（多分片场景降级为单分片核心行为 + 守恒）。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest};

    const XBT: i32 = 3762;
    const USD: i32 = 840; // QUOTE_ID
    const ETH: i32 = 3928;
    const BTC_SYM: i32 = 10000;
    const ETH_SYM: i32 = 10001;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn leverage_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    fn btc_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: BTC_SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: XBT,
            quote_currency: USD,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 1_000,
            max_leverage: leverage_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    fn eth_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: ETH_SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: ETH,
            quote_currency: USD,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 1,
            taker_fee: 2,
            fee_scale_k: 100,
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 1_000,
            max_leverage: leverage_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    /// 单 BTC symbol + mark=entry。
    fn setup_btc(entry: i64) -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(btc_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, entry), CommandResultCode::Success);
        api
    }

    /// BTC + ETH 双 symbol + 两 mark=entry。
    fn setup_two(entry: i64) -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(USD, 1);
        api.add_currency(ETH, 1);
        assert_eq!(api.add_futures_symbol(btc_spec()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, entry), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, entry), CommandResultCode::Success);
        api
    }

    fn seed_user(api: &mut ExchangeApi, uid: i64, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, USD, amount, txid), CommandResultCode::Success);
    }

    #[allow(clippy::too_many_arguments)]
    fn place(
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
            leverage: 1,
            margin_mode,
            reduce_only: false,
        })
    }

    fn assert_conserved(api: &ExchangeApi) {
        let tcb = api.total_balance();
        assert!(tcb.is_global_zero(), "全局守恒被打破: {:?}", tcb.global_balances_sum());
    }

    fn open_volume(api: &ExchangeApi, uid: i64, sym: i32) -> i64 {
        api.user_position(uid, sym).map(|p| p.open_volume).unwrap_or(0)
    }

    // ================================================================================================
    // 1. testBasicLiquidationIsolatedMode —— 逐仓多头，价格暴跌被全平。
    // ================================================================================================

    #[test]
    fn basic_liquidation_isolated_long() {
        let (trader, lp) = (1001i64, 2001i64);
        let position_size = 10i64;
        let entry = 10_000i64;
        let bp_fill = 9_920i64;
        let liq_price = 500i64;

        let mut api = setup_btc(entry);
        seed_user(&mut api, trader, 3_000, 1);
        seed_user(&mut api, lp, 100_000, 2);

        // 逐仓多头开仓：trader BID(ISOLATED)、lp ASK(CROSS) 对手。
        assert_eq!(place(&mut api, 10001, trader, BTC_SYM, entry, position_size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 10002, lp, BTC_SYM, entry, position_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, trader, BTC_SYM), position_size);

        // 强平接单流动性：lp 在破产价上方挂大额 BID。
        let liquidity = position_size + 15;
        assert_eq!(place(&mut api, 10003, lp, BTC_SYM, bp_fill, liquidity, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        // 价格暴跌触发定向强平。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(BTC_SYM, liq_price, 2_000), CommandResultCode::Success);

        assert!(api.user_position(trader, BTC_SYM).is_none(), "交易者应被全平");
        // 金额精确锚点（不止守恒）：
        //   trader = 3000 − 100(开仓 maker 费 10×10) − 1000(逐仓保证金 notional×initMargin/scaleK=100000/100 全损) = 1900。
        assert_eq!(api.user_account(trader, USD), 1_900, "被强平方亏掉逐仓保证金+已付费，保留 1900");
        //   fees = 开仓(trader maker 100 + lp taker 200) + 强平成交(被平方 taker 200 + lp maker 100) = 600。
        assert_eq!(api.fees(USD), 600, "开仓+强平两笔成交的 maker/taker 费全入池");
        //   未配 liquidation_fee → IF 无进项。
        let if_available: i64 = api.insurance_fund().futures.values().map(|e| e.available).sum();
        assert_eq!(if_available, 0, "无 liquidation_fee → IF available 不增");
        //   lp 对手方净结算金额（cross 平空 + 收付费）——回归锚点。
        assert_eq!(api.user_account(lp, USD), 99_700, "LP 对手方净结算");
        // lp 的接单流动性被强平卖单消耗 10（25→15）。
        assert_eq!(api.user_position(lp, BTC_SYM).unwrap().pending_buy_size, liquidity - position_size, "lp 流动性被消耗 10");
        assert_conserved(&api);
    }

    // ================================================================================================
    // 2. testTargetedLiquidationByMarkPriceDrop_isolated —— 仅靠 mark price 下跌驱动 targeted 强平（无全量扫）。
    // ================================================================================================

    #[test]
    fn targeted_liquidation_by_mark_price_drop_isolated() {
        let (trader, lp) = (1101i64, 2101i64);
        let size = 10i64;
        let entry = 10_000i64;
        let bp_fill = 9_920i64;
        let liq_price = 500i64;

        let mut api = setup_btc(entry);
        seed_user(&mut api, trader, 3_000, 1);
        seed_user(&mut api, lp, 100_000, 2);

        assert_eq!(place(&mut api, 110001, trader, BTC_SYM, entry, size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 110002, lp, BTC_SYM, entry, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, trader, BTC_SYM), size);

        assert_eq!(place(&mut api, 110003, lp, BTC_SYM, bp_fill, size + 15, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        // 开启引擎后仅靠 MARKPRICE 下跌命令 apply 时的 targeted checkPositions 完成强平（核心收益路径）。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(BTC_SYM, liq_price, 2_000), CommandResultCode::Success);

        assert!(api.user_position(trader, BTC_SYM).is_none(), "targeted 路径应完成逐仓强平");
        assert_conserved(&api);
    }

    // ================================================================================================
    // 3. testShortPositionLiquidation —— 逐仓空头，价格暴涨被全平。
    // ================================================================================================

    #[test]
    fn short_position_liquidation() {
        let (trader, lp) = (1003i64, 2003i64);
        let size = 6i64;
        let entry = 5_000i64;
        let bp_fill = 5_030i64;
        let liq_price = 12_000i64;

        let mut api = setup_btc(entry);
        seed_user(&mut api, trader, 8_000, 1);
        seed_user(&mut api, lp, 100_000, 2);

        // 逐仓空头：trader ASK(ISOLATED)、lp BID(CROSS) 对手。
        assert_eq!(place(&mut api, 30001, trader, BTC_SYM, entry, size, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 30002, lp, BTC_SYM, entry, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, trader, BTC_SYM), size);

        // 强平接单流动性：lp 在破产价下方挂 ASK（接收强平买单）。
        let liquidity = size + 13;
        assert_eq!(place(&mut api, 30003, lp, BTC_SYM, bp_fill, liquidity, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        // 价格暴涨触发空头强平。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(BTC_SYM, liq_price, 2_000), CommandResultCode::Success);

        assert!(api.user_position(trader, BTC_SYM).is_none(), "空头交易者应被全平");
        assert!(api.user_position(lp, BTC_SYM).unwrap().pending_sell_size < liquidity, "lp 卖单流动性应被消耗");
        assert_conserved(&api);
    }

    // ================================================================================================
    // 4. testPartialLiquidation —— 全仓大额多头 + 有限流动性 → 部分强平（减仓不必清零）。
    // ================================================================================================

    #[test]
    fn partial_liquidation_reduces_position() {
        let (trader, lp) = (1004i64, 2004i64);
        let position_size = 20i64;
        let entry = 8_000i64;
        let partial_liq_price = 6_000i64;

        let mut api = setup_btc(entry);
        seed_user(&mut api, trader, 15_000, 1);
        seed_user(&mut api, lp, 100_000, 2);

        assert_eq!(place(&mut api, 40001, trader, BTC_SYM, entry, position_size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 40002, lp, BTC_SYM, entry, position_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(open_volume(&api, trader, BTC_SYM), position_size);

        // 有限流动性：只挂一半（模拟部分强平）。
        let liquidity = position_size / 2;
        assert_eq!(place(&mut api, 40003, lp, BTC_SYM, partial_liq_price, liquidity, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(BTC_SYM, partial_liq_price, 2_000), CommandResultCode::Success);

        // 持仓应减少（部分或全部强平均可，Java 亦允许两者）。
        assert!(open_volume(&api, trader, BTC_SYM) < position_size, "强平后持仓应减少");
        assert_conserved(&api);
    }

    // ================================================================================================
    // 5. testMultipleUsersLiquidation —— 多用户同时逐仓多头被强平。
    // ================================================================================================

    #[test]
    fn multiple_users_liquidation() {
        let traders = [1005i64, 1006, 1007, 1008, 1009];
        let lp = 2005i64;
        let position_size = 4i64;
        let entry = 9_000i64;
        let liq_price = 2_500i64;

        let mut api = setup_btc(entry);
        for (i, &t) in traders.iter().enumerate() {
            seed_user(&mut api, t, 6_000, (i + 1) as i64);
        }
        seed_user(&mut api, lp, 200_000, 100);

        let mut oid = 50001i64;
        for &t in &traders {
            assert_eq!(place(&mut api, oid, t, BTC_SYM, entry, position_size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
            oid += 1;
            assert_eq!(place(&mut api, oid, lp, BTC_SYM, entry, position_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
            oid += 1;
            assert_eq!(open_volume(&api, t, BTC_SYM), position_size);
        }

        // 足够流动性接收全部强平卖单。
        let liquidity = traders.len() as i64 * position_size + 5;
        assert_eq!(place(&mut api, oid, lp, BTC_SYM, liq_price, liquidity, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(BTC_SYM, liq_price, 2_000), CommandResultCode::Success);

        for &t in &traders {
            assert!(api.user_position(t, BTC_SYM).is_none(), "交易者 {t} 应被全平");
        }
        assert_conserved(&api);
    }

    // ================================================================================================
    // 6. testMultiUserCrossShardLiquidation —— 单分片降级：6 逐仓多头全平，lp2 流动性被消耗。
    // ================================================================================================

    #[test]
    fn multi_user_liquidation_liquidity_consumed() {
        let users = [1001i64, 1002, 1003, 1004, 1005, 1006];
        let (lp1, lp2) = (2001i64, 2002i64);
        let size = 5i64;
        let entry = 10_000i64;
        let bp_fill = 9_920i64;
        let liq_price = 2_000i64;

        let mut api = setup_btc(entry);
        for (i, &u) in users.iter().enumerate() {
            seed_user(&mut api, u, 5_000, (i + 1) as i64);
        }
        seed_user(&mut api, lp1, 1_000_000, 100);
        seed_user(&mut api, lp2, 1_000_000, 101);

        let mut oid = 10001i64;
        for &u in &users {
            assert_eq!(place(&mut api, oid, u, BTC_SYM, entry, size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
            oid += 1;
            assert_eq!(place(&mut api, oid, lp1, BTC_SYM, entry, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
            oid += 1;
        }
        for &u in &users {
            assert_eq!(open_volume(&api, u, BTC_SYM), size, "用户 {u} 应有持仓");
        }

        // lp2 大额接单流动性。
        let liquidity = 50i64;
        assert_eq!(place(&mut api, oid, lp2, BTC_SYM, bp_fill, liquidity, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(BTC_SYM, liq_price, 2_000), CommandResultCode::Success);

        // 6 用户各 5 手全平。
        for &u in &users {
            assert!(api.user_position(u, BTC_SYM).is_none(), "用户 {u} 应被全平");
        }
        // lp2 接到强平卖单（流动性被消耗）。
        assert!(api.user_position(lp2, BTC_SYM).unwrap().pending_buy_size < liquidity, "lp2 流动性应被消耗");
        assert_conserved(&api);
    }

    // ================================================================================================
    // 7. testMixedMarginModeAcrossShards —— 单分片降级：ISOLATED 被强平、CROSS 存活（模式差异化行为）。
    // ================================================================================================

    #[test]
    fn mixed_margin_mode_isolated_liquidated_cross_survives() {
        let (cross1, cross2, iso1, iso2) = (4001i64, 4002i64, 4003i64, 4004i64);
        let lp = 9001i64;
        let size = 3i64;
        let entry = 10_000i64;
        let bp_fill = 9_920i64;
        let liq_price = 8_000i64; // 20% 下跌

        let mut api = setup_btc(entry);
        // 全仓用户资金充足（不被强平）。
        seed_user(&mut api, cross1, 50_000, 1);
        seed_user(&mut api, cross2, 50_000, 2);
        seed_user(&mut api, iso1, 50_000, 3);
        seed_user(&mut api, iso2, 50_000, 4);
        seed_user(&mut api, lp, 1_000_000, 5);

        let mut oid = 70001i64;
        for (u, mm) in [(cross1, MarginMode::Cross), (cross2, MarginMode::Cross), (iso1, MarginMode::Isolated), (iso2, MarginMode::Isolated)] {
            assert_eq!(place(&mut api, oid, u, BTC_SYM, entry, size, OrderAction::Bid, mm), CommandResultCode::Success);
            oid += 1;
            assert_eq!(place(&mut api, oid, lp, BTC_SYM, entry, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
            oid += 1;
            assert_eq!(open_volume(&api, u, BTC_SYM), size);
        }

        // 接单流动性。
        let liquidity = 4 * size + 10;
        assert_eq!(place(&mut api, oid, lp, BTC_SYM, bp_fill, liquidity, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(BTC_SYM, liq_price, 2_000), CommandResultCode::Success);

        // 逐仓（隔离保证金仅 1%）被强平；全仓（账户总余额充足）存活。
        assert!(api.user_position(iso1, BTC_SYM).is_none(), "逐仓 iso1 应被强平");
        assert!(api.user_position(iso2, BTC_SYM).is_none(), "逐仓 iso2 应被强平");
        assert_eq!(open_volume(&api, cross1, BTC_SYM), size, "全仓 cross1 不应被强平");
        assert_eq!(open_volume(&api, cross2, BTC_SYM), size, "全仓 cross2 不应被强平");
        assert_conserved(&api);
    }

    // ================================================================================================
    // 8. testCrossMarginLiquidation —— 全仓双 symbol 多头，两价均跌 → 至少减仓。
    // ================================================================================================

    #[test]
    fn cross_margin_liquidation_reduces_total_volume() {
        let (trader, lp) = (1002i64, 2002i64);
        let (size1, size2) = (5i64, 8i64);
        let entry = 10_000i64;
        let bp_fill = 10_000i64;
        let liq_price = 8_000i64;

        let mut api = setup_two(entry);
        seed_user(&mut api, trader, 20_000, 1);
        seed_user(&mut api, lp, 100_000, 2);

        // 两 symbol 上全仓多头。
        assert_eq!(place(&mut api, 20001, trader, BTC_SYM, entry, size1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 20002, lp, BTC_SYM, entry, size1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 20003, trader, ETH_SYM, entry, size2, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 20004, lp, ETH_SYM, entry, size2, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        let initial_total = open_volume(&api, trader, BTC_SYM) + open_volume(&api, trader, ETH_SYM);
        assert_eq!(initial_total, size1 + size2);

        // 两 symbol 接单流动性。
        assert_eq!(place(&mut api, 20005, lp, BTC_SYM, bp_fill, size1 + 15, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 20006, lp, ETH_SYM, bp_fill, size2 + 17, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        // 两价均跌触发全仓强平。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(BTC_SYM, liq_price, 2_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price_at(ETH_SYM, liq_price, 2_000), CommandResultCode::Success);

        let remaining = open_volume(&api, trader, BTC_SYM) + open_volume(&api, trader, ETH_SYM);
        assert!(remaining < initial_total, "全仓强平后总仓位应小于初始（至少一个仓位被减）");
        assert_conserved(&api);
    }
}
