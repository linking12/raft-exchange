//! 翻译自 Java `exchange.core2.tests.integration.ITExchangeCoreHedgeMode`（17 个 @Test）。
//!
//! harness 现已提供 position-mode 支持：`api.adjust_position_mode(uid, hedge)` 切 HEDGE(true)/ONEWAY(false)；
//! HEDGE 双腿仓位通过 `api.ups().get(uid).unwrap().positions.values()` 按 `direction` 过滤读取（±symbol 双键，
//! 见本文件 `leg_dir` helper）；全局守恒用 `api.total_balance().is_global_zero()`；funding / settle-pnl 走
//! 通用 `api.submit(OrderCommand{..})`。因此 14 个 @Test 已翻译（含原 ONEWAY 的 `testDefaultSingleDirection`）。
//!
//! **仍跳过（3 个）** —— 强平回路：`testLiquidationLoop` / `testLiquidationLoop2` / `testLiquidationLoop3`。
//! Java 版依赖「停引擎 → 挂单 → 观察触发前中间态 → `triggerLiquidation()`」的分步驱动，且强平后各腿的
//! golden 持仓取决于 FORCE→IF→ADL 级联的涌现排空顺序；Rust harness 的强平在 `set_mark_price_at` 内同步扫描，
//! 无法复刻「先观察中间态再手动触发」的时序，golden 多腿级联结果不可稳定断言 → 保持跳过（genuinely N/A）。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::position_direction::PositionDirection;
    use exchange_core_rs::core::common::symbol_position_record::SymbolPositionRecord;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{
        ClosePositionRequest, ExchangeApi, MarginAdjustmentRequest, PlaceFuturesOrderRequest,
    };
    use exchange_core_rs::core::utils::core_arithmetic_utils::currency_to_size_price_scale;

    // 对应 Java TestConstants。
    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;
    const UID_3: i64 = 1_440_003;
    const UID_4: i64 = 1_440_004;
    const UID_5: i64 = 1_440_005;

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
        assert_eq!(api.set_mark_price(SYMBOL_ID, mark_price, 0), CommandResultCode::Success);

        let deposit = 10_000 * 1_000_000; // 10000 * USDT currencyScaleK
        for (i, uid) in [UID_1, UID_2, UID_3, UID_4, UID_5].into_iter().enumerate() {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(
                api.balance_adjustment(uid, USDT_ID, deposit, 100 + i as i64),
                CommandResultCode::Success
            );
        }
        api
    }

    /// 通用期货下单（可指定 symbol / order_type / margin_mode / leverage）。对应 Java `ApiPlaceOrder.builder()`
    /// 手工拼字段。`leverage=0` 由引擎归一为 1（Java 未指定 leverage 即默认 1x），逐字对齐。
    #[allow(clippy::too_many_arguments)]
    fn place_on(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        symbol: i32,
        price: i64,
        size: i64,
        action: OrderAction,
        order_type: OrderType,
        margin_mode: MarginMode,
        leverage: i32,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol,
            price,
            size,
            action,
            order_type,
            leverage,
            margin_mode,
            reduce_only: false,
        })
    }

    /// HEDGE 双腿读取：`ups().get(uid).positions.values()` 按 `direction` 过滤（±symbol 双键，两腿 `.symbol==symbol`）。
    /// 对应 Java `profile.getPositions().get(symbol).stream().filter(p -> p.direction == dir)`。
    fn leg_dir(
        api: &ExchangeApi,
        uid: i64,
        symbol: i32,
        dir: PositionDirection,
    ) -> Option<&SymbolPositionRecord> {
        api.ups()
            .get(uid)?
            .positions
            .values()
            .find(|p| p.symbol == symbol && p.direction == dir)
    }

    /// 某 symbol 下的仓位记录数（ONEWAY 至多 1，HEDGE 至多 2）。对应 Java `getPositions().get(symbol).size()`。
    fn sym_position_count(api: &ExchangeApi, uid: i64, symbol: i32) -> usize {
        api.ups()
            .get(uid)
            .map(|p| p.positions.values().filter(|r| r.symbol == symbol).count())
            .unwrap_or(0)
    }

    /// 对应 Java `initHedgeOrders`：UID_1（HEDGE）开 LONG 100 @750 + SHORT 50 @800，对手 UID_2/UID_3 吃单。
    fn init_hedge_orders(api: &mut ExchangeApi) {
        // 开多 100（UID_1 BID + UID_2 ASK 撮合）。
        assert_eq!(place_on(api, 10001, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(api, 10002, UID_2, SYMBOL_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(leg_dir(api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);

        // 开空 50（UID_1 ASK + UID_3 BID 撮合）。
        assert_eq!(place_on(api, 10003, UID_1, SYMBOL_ID, 80_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(api, 10004, UID_3, SYMBOL_ID, 80_000_000, 50, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
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

    // 测试2: 可通过命令改持仓类型（ONEWAY→HEDGE），改后可同时持多空；双向持仓 margin 模式必须一致。
    #[test]
    fn test_change_position_mode() {
        let mut api = setup();

        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        let size = 2 * BASE_SCALE_K; // 2000
        let price = 750 * QUOTE_SCALE_K; // 75_000_000
        // LONG 方向 BID（无对手 → pending）。
        assert_eq!(place_on(&mut api, 10001, UID_1, SYMBOL_ID, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        let size2 = 10 * BASE_SCALE_K; // 10000
        let price2 = 800 * QUOTE_SCALE_K; // 80_000_000
        // 双向持仓 margin 模式必须一致：LONG 腿已是 ISOLATED，用 CROSS 开 SHORT → 拒绝。
        assert_eq!(place_on(&mut api, 10002, UID_1, SYMBOL_ID, price2, size2, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 10), CommandResultCode::RiskMarginModeMismatch);
        // 同为 ISOLATED → SHORT 腿 pending 成功。
        assert_eq!(place_on(&mut api, 10003, UID_1, SYMBOL_ID, price2, size2, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().pending_buy_size, size);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().pending_sell_size, size2);

        // 对手成交：UID_3 ASK size/2 吃 LONG pending 一半；UID_3 BID size2 吃满 SHORT pending。
        assert_eq!(place_on(&mut api, 10004, UID_3, SYMBOL_ID, price, size / 2, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10005, UID_3, SYMBOL_ID, price2, size2, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().pending_buy_size, size / 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().pending_sell_size, 0);
    }

    // 测试3: 有仓位时不允许改持仓类型。
    #[test]
    fn test_cannot_change_mode_with_position() {
        let mut api = setup();

        let size = 2 * BASE_SCALE_K; // 2000
        let price = 750 * QUOTE_SCALE_K; // 75_000_000
        // 开一个 ONEWAY 仓位（UID_1 BID + UID_2 ASK 撮合）。
        assert_eq!(place_on(&mut api, 10001, UID_1, SYMBOL_ID, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10002, UID_2, SYMBOL_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 10), CommandResultCode::Success);

        // 已有仓位 → 切 HEDGE 被拒。
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::RiskMarginPositionExists);
        // 仍为单向（1 条记录）。
        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 1);
    }

    /// 逐字复刻 Java `symbol` 字段（id 10001、baseScaleK=quoteScaleK=1、maker/taker=0、分档 maintenance/leverage）。
    /// 与 `bnb_usdt_spec` 同 id，但仅在 `test_margin_hedge_mode` 的独立 api 实例里使用，无冲突。
    fn margin_hedge_symbol_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL_ID,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            base_scale_k: 1,
            quote_currency: USDT_ID,
            quote_scale_k: 1,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(10_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(10_000, 75), (100_000, 40)]),
            ..Default::default()
        }
    }

    // 双向持仓正向：允许同时开多/开空，存到不同 position；对手为单向用户，最终两腿各 openVolume=1。
    #[test]
    fn test_margin_hedge_mode() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1); // digit 0 → currencyScaleK=1
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(margin_hedge_symbol_spec()), CommandResultCode::Success);

        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, USDT_ID, 10_000, 1), CommandResultCode::Success);
        assert_eq!(api.add_user(UID_2), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_2, USDT_ID, 10_000, 2), CommandResultCode::Success);

        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, 650, 0), CommandResultCode::Success);

        // UID_1 BID 1@652（LONG）+ UID_2 ASK 1@652（对手 SHORT）→ 撮合，UID_1 LONG=1。
        assert_eq!(place_on(&mut api, 101, UID_1, SYMBOL_ID, 652, 1, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 102, UID_2, SYMBOL_ID, 652, 1, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);
        // UID_1 ASK 1@655（SHORT）+ UID_2 BID 1@655（平掉自己的 SHORT）→ 撮合，UID_1 SHORT=1。
        assert_eq!(place_on(&mut api, 103, UID_1, SYMBOL_ID, 655, 1, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 104, UID_2, SYMBOL_ID, 655, 1, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);

        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 1);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 1);
        // UID_2 单向：开 SHORT 再 BID 平回 → 仓位清空。
        assert_eq!(sym_position_count(&api, UID_2, SYMBOL_ID), 0);
    }

    // 测试4: 双向持仓 position 信息正确——多空独立记录可不同…但 leverage 必须一致、margin 模式必须一致。
    #[test]
    fn test_dual_position_sign() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        // 开多（LONG 腿 pending，ISOLATED 10x）。
        assert_eq!(place_on(&mut api, 10001, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        // 同腿不同 leverage（11x）→ 拒绝。
        assert_eq!(place_on(&mut api, 10002, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 11), CommandResultCode::RiskLeverageMismatch);
        // 对手 UID_2 ASK 吃满 LONG pending → openVolume 100。
        assert_eq!(place_on(&mut api, 10003, UID_2, SYMBOL_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);

        // 开空腿用 CROSS → margin 模式不一致，拒绝。
        assert_eq!(place_on(&mut api, 10004, UID_1, SYMBOL_ID, 75_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 11), CommandResultCode::RiskMarginModeMismatch);
        // 开空腿 ISOLATED 但 11x → 杠杆不一致，拒绝。
        assert_eq!(place_on(&mut api, 10005, UID_1, SYMBOL_ID, 75_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 11), CommandResultCode::RiskLeverageMismatch);
        // 开空腿 ISOLATED 10x → 成功（SHORT pending）。
        assert_eq!(place_on(&mut api, 10006, UID_1, SYMBOL_ID, 75_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        // 对手 UID_2 BID 吃满 SHORT pending → openVolume 50。
        assert_eq!(place_on(&mut api, 10007, UID_2, SYMBOL_ID, 75_000_000, 50, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);
    }

    // 测试5: 关仓——ApiClosePosition 分别减多仓/空仓，双腿记录保留、openVolume 递减。
    #[test]
    fn test_close_position() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);
        init_hedge_orders(&mut api);

        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);

        // 关多仓 30（ASK 平 LONG，resting maker）+ UID_4 BID 吃单。
        assert_eq!(
            api.close_position(ClosePositionRequest {
                order_id: 10005,
                uid: UID_1,
                symbol: SYMBOL_ID,
                action: OrderAction::Ask,
                price: 81_000_000,
                size: 30,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::Success
        );
        assert_eq!(place_on(&mut api, 10006, UID_4, SYMBOL_ID, 81_000_000, 30, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 70);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);

        // 关空仓 30（BID 平 SHORT，resting maker）+ UID_5 ASK 吃单。
        assert_eq!(
            api.close_position(ClosePositionRequest {
                order_id: 10007,
                uid: UID_1,
                symbol: SYMBOL_ID,
                action: OrderAction::Bid,
                price: 74_000_000,
                size: 30,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::Success
        );
        assert_eq!(place_on(&mut api, 10008, UID_5, SYMBOL_ID, 74_000_000, 30, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 70);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 20);
    }

    // 测试5.2: 双向持仓之间互相 match（两个 HEDGE 用户各持满多空双腿）。
    #[test]
    fn test_hedge_mode_match() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        // UID_1 挂 LONG BID 100@750 + SHORT ASK 50@800（不交叉，均 pending，openVolume=0）。
        assert_eq!(place_on(&mut api, 10001, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10003, UID_1, SYMBOL_ID, 80_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 0);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 0);

        assert_eq!(api.adjust_position_mode(UID_5, true), CommandResultCode::Success);
        // UID_5 ASK 50@750 吃 UID_1 LONG BID → UID_1 LONG=50、UID_5 SHORT=50。
        assert_eq!(place_on(&mut api, 10003, UID_5, SYMBOL_ID, 75_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        // UID_5 BID 50@800 吃 UID_1 SHORT ASK → UID_1 SHORT=50、UID_5 LONG=50。
        assert_eq!(place_on(&mut api, 10004, UID_5, SYMBOL_ID, 80_000_000, 50, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 50);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);
        assert_eq!(sym_position_count(&api, UID_5, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_5, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 50);
        assert_eq!(leg_dir(&api, UID_5, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);
    }

    // T6: HEDGE 开第二个（反向）仓位走 deferred-insert 路径——新 position 校验阶段还未入 map，
    // 不应污染 freeMargin 计算；开仓金额必须对得上，且全局守恒不破。
    #[test]
    fn test_hedge_open_second_direction_does_not_pollute_free_margin_calc() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        // 先开 LONG 100（UID_1 BID + UID_2 ASK）。
        assert_eq!(place_on(&mut api, 30001, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 30002, UID_2, SYMBOL_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        // 再开 SHORT 50（新 positionRecordKey，deferred-insert corner case）。
        assert_eq!(place_on(&mut api, 30003, UID_1, SYMBOL_ID, 75_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 30004, UID_3, SYMBOL_ID, 75_000_000, 50, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);

        assert!(api.total_balance().is_global_zero(), "HEDGE 开第二个方向后全局守恒必须成立");
    }

    // HEDGE 同 uid self-match：BID + ASK 同价自撮合，taker 块与 maker 块 uid 相同、
    // 修改的是同一 takerUp 的另一 position（把 LONG 也开起来）；PnL settle 必须用 fresh calculateLocked，守恒不破。
    #[test]
    fn test_hedge_mode_self_match_conservation() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        // UID_1 挂 BID 100@750（LONG pending）。
        assert_eq!(place_on(&mut api, 20001, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        // UID_1 aggressive ASK 100@750 与自己 BID self-match。
        assert_eq!(place_on(&mut api, 20002, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        // self-match 后同时持 LONG 100 + SHORT 100。
        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 100);

        assert!(api.total_balance().is_global_zero(), "hedge self-match 后全局守恒必须成立");
    }

    // 测试6: 混合多空持仓 + 资金费率命令——action=BID → LONG 付 SHORT：LONG profit 减、SHORT profit 增。
    #[test]
    fn test_mixed_funding_rate() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);
        init_hedge_orders(&mut api);

        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);

        // SETTLE_FUNDINGFEES：price=fundingRate(1)、size=rateScaleK(100)、action=BID。
        assert_eq!(
            api.submit(OrderCommand {
                command: OrderCommandType::SettleFundingfees,
                symbol: SYMBOL_ID,
                action: Some(OrderAction::Bid),
                price: 1,
                size: 100,
                order_id: 500,
                ..Default::default()
            }),
            CommandResultCode::Success
        );

        assert!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().profit < 0, "LONG profit 应减少");
        assert!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().profit > 0, "SHORT profit 应增加");
    }

    /// 逐字复刻 Java `BNB_USDT_DELIVERY`（id 30001、交割合约、baseScaleK=1e3、quoteScaleK=1e5）。
    fn bnb_usdt_delivery_spec() -> CoreSymbolSpecification {
        let tier_notional = 10_000 * 100_000_000; // 10_000 * pow10(3+5)
        CoreSymbolSpecification {
            symbol_id: 30001,
            symbol_type: SymbolType::FuturesContractDelivery,
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

    // 测试7: 交割结算 settlePnl——双向持仓在 settlePrice 处全部平掉，账户按已实现盈亏结算。
    #[test]
    fn test_settle_pnl() {
        const DELIVERY_ID: i32 = 30001;
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 100_000_000);
        api.add_currency(USDT_ID, 1_000_000);
        assert_eq!(api.add_futures_symbol(bnb_usdt_delivery_spec()), CommandResultCode::Success);

        let mark_price = 750 * QUOTE_SCALE_K; // 75_000_000
        assert_eq!(api.set_mark_price(DELIVERY_ID, mark_price, 0), CommandResultCode::Success);

        let deposit = 10_000 * 1_000_000;
        for (i, uid) in [UID_1, UID_2, UID_3].into_iter().enumerate() {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, USDT_ID, deposit, 100 + i as i64), CommandResultCode::Success);
        }

        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        // 开多 100（UID_1 BID + UID_2 ASK @750）。
        assert_eq!(place_on(&mut api, 10001, UID_1, DELIVERY_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10002, UID_2, DELIVERY_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(leg_dir(&api, UID_1, DELIVERY_ID, PositionDirection::Long).unwrap().open_volume, 100);

        // 开空 50（UID_1 ASK + UID_3 BID @800）。
        assert_eq!(place_on(&mut api, 10003, UID_1, DELIVERY_ID, 80_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10004, UID_3, DELIVERY_ID, 80_000_000, 50, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        // mark 涨到 800（尚未结算，account 不变）。
        assert_eq!(api.set_mark_price(DELIVERY_ID, 800 * QUOTE_SCALE_K, 0), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT_ID), deposit);
        assert_eq!(sym_position_count(&api, UID_1, DELIVERY_ID), 2);

        // SETTLE_PNL：symbol=交割合约、price=settlePrice(原 mark 750)。
        assert_eq!(
            api.submit(OrderCommand {
                command: OrderCommandType::SettlePnl,
                symbol: DELIVERY_ID,
                price: mark_price,
                order_id: 600,
                timestamp: 1,
                ..Default::default()
            }),
            CommandResultCode::Success
        );

        // 结算后仓位清空、账户加已实现 pnl（golden 逐字取 Java）。
        assert_eq!(sym_position_count(&api, UID_1, DELIVERY_ID), 0);
        assert_eq!(api.user_account(UID_1, USDT_ID), 10_002_500_000);
    }

    // 测试9: total balance 正确（含 pnl）——HEDGE 双向开仓后全局守恒。
    #[test]
    fn test_total_balance() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);
        init_hedge_orders(&mut api);
        assert!(api.total_balance().is_global_zero());
    }

    // 测试10: 双向持仓追加保证金分别加到对应腿（ISOLATED 进 extra_margin，CROSS 直接回 balance）。
    #[test]
    fn test_add_extra_margin_to_dual_position() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);
        init_hedge_orders(&mut api);

        let base_deposit = 10_000 * 1_000_000;
        assert_eq!(api.user_account(UID_1, USDT_ID), base_deposit);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().extra_margin, 0);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().extra_margin, 0);

        // 加到 LONG（action=BID）。scaled == currencyToSizePriceScale(depositLong, BNB_USDT, USDT)。
        let deposit_long = 100 * 1_000_000;
        let scaled_long = currency_to_size_price_scale(deposit_long, BASE_SCALE_K, QUOTE_SCALE_K, 1_000_000);
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1,
                symbol: SYMBOL_ID,
                action: OrderAction::Bid,
                amount: deposit_long,
                margin_mode: MarginMode::Isolated,
                order_id: 700,
            }),
            CommandResultCode::Success
        );

        // 加到 SHORT（action=ASK）。
        let deposit_short = 200 * 1_000_000;
        let scaled_short = currency_to_size_price_scale(deposit_short, BASE_SCALE_K, QUOTE_SCALE_K, 1_000_000);
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1,
                symbol: SYMBOL_ID,
                action: OrderAction::Ask,
                amount: deposit_short,
                margin_mode: MarginMode::Isolated,
                order_id: 701,
            }),
            CommandResultCode::Success
        );

        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().extra_margin, scaled_long);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().extra_margin, scaled_short);
        assert_eq!(api.user_account(UID_1, USDT_ID), base_deposit - deposit_long - deposit_short);

        // CROSS 追加：symbol 字段传 currency id，直接回 balance。
        let deposit_cross = 500 * 1_000_000;
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1,
                symbol: USDT_ID,
                action: OrderAction::Bid,
                amount: deposit_cross,
                margin_mode: MarginMode::Cross,
                order_id: 702,
            }),
            CommandResultCode::Success
        );

        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().extra_margin, scaled_long);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().extra_margin, scaled_short);
        assert_eq!(api.user_account(UID_1, USDT_ID), base_deposit - deposit_long - deposit_short + deposit_cross);
    }

    // 测试11: 调整杠杆应用到该 symbol 下所有腿（LONG + SHORT 都变）。
    #[test]
    fn test_adjust_leverage_dual() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);
        init_hedge_orders(&mut api);

        assert_eq!(api.leverage_adjustment(UID_1, SYMBOL_ID, 20), CommandResultCode::Success);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().leverage, 20);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().leverage, 20);
    }

    // 测试12（补）: 双向持仓被强平——HEDGE 用户高杠杆双腿，暴跌只清 LONG 腿、SHORT 盈利腿保留。
    //   补上 Java testLiquidationLoop 系列（分步时序不可复刻，见文件头跳过说明）的核心语义：
    //   逐仓 HEDGE 下强平是 per-leg 的，一条腿爆仓不影响同 symbol 另一条腿。
    #[test]
    fn test_hedge_one_leg_liquidated_other_preserved() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success); // HEDGE

        let entry = 750 * QUOTE_SCALE_K; // 75_000_000
        // UID_1 LONG 100 @750 leverage 50（薄保证金→易爆）；对手 UID_2 leverage 1（厚，不爆）。
        assert_eq!(place_on(&mut api, 1, UID_1, SYMBOL_ID, entry, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 50), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 2, UID_2, SYMBOL_ID, entry, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 1), CommandResultCode::Success);
        // UID_1 SHORT 50 @750 leverage 50；对手 UID_3 leverage 1。
        assert_eq!(place_on(&mut api, 3, UID_1, SYMBOL_ID, entry, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 50), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 4, UID_3, SYMBOL_ID, entry, 50, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 1), CommandResultCode::Success);

        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);
        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2, "开仓后双腿并存");

        // IF 充值以承接被强平的 LONG 腿。
        assert_eq!(
            api.submit(OrderCommand { command: OrderCommandType::IfDeposit, symbol: SYMBOL_ID, price: 5_000 * 1_000_000, order_id: 900, ..Default::default() }),
            CommandResultCode::Success
        );

        api.enable_liquidation();
        // 暴跌 750→700：LONG（lev50，~2% 保证金）爆仓；SHORT 盈利、对手 lev1 不爆。
        assert_eq!(api.set_mark_price(SYMBOL_ID, 700 * QUOTE_SCALE_K, 2_000), CommandResultCode::Success);

        // 核心语义：per-leg 强平——LONG 腿被清、SHORT 盈利腿原样保留，双腿互不牵连。
        assert!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).is_none(), "LONG 腿应被强平清仓");
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50, "SHORT 盈利腿保留");
        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 1, "只剩 SHORT 一条腿");
        // 对手方（低杠杆）不被误伤。
        assert_eq!(leg_dir(&api, UID_2, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 100, "UID_2 对手 SHORT 存活");
        assert_eq!(leg_dir(&api, UID_3, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 50, "UID_3 对手 LONG 存活");
        assert!(api.total_balance().is_global_zero(), "全局守恒");
    }
}
