//! 翻译自 Java `exchange.core2.tests.integration.ITExchangeCoreCustomLeverage`。
//! 直连 Rust 引擎（无 raft/Disruptor），逐条对拍 Java 黄金值：自选杠杆校验、杠杆不匹配拒单、
//! 杠杆调整后的保证金/NSF 复检、超限杠杆拒单、带杠杆持仓下的提现/现货下单风控。
//!
//! 强平类 4 个（`testLiquidationTriggeredByHighLeverage` / `testLiquidationOfMaintenanceMargin` /
//! `testLiquidationSendWarn` / `testLiquidationLeverage`）已 backfill——harness 现支持
//! `enable_liquidation()` + `set_mark_price_at(sym, adverse, ts)` 触发定向扫描（FORCE→IF→ADL 同调自动排空）。
//! 强平 fund event 走内部排空命令、`last_fund_events()` 不捕获，故断言最终**状态**：仓位全平/减仓
//! （`user_position`/`open_volume`）+ 全局守恒 `total_balance().is_global_zero()` + 强平前的确定性开仓态。
//! Java 收尾的 BP-fill 逐笔手续费/账户黄金值（partial close fee 等）依赖异步 ADL/IF timing，按 `it_liquidation_tests`
//! 的降级策略不逐值断言（保留 `open_volume` 精确减仓量——引擎 `calculate_size_to_liquidate` 是确定性纯函数）。
//! MARGIN_ALERT/LIQUIDATION_ALERT 在 Rust 外置 no-op，不断言告警事件。
//!
//! 报表/订单簿子断言的适配：`ExchangeApi` 无 `totalBalanceReport`（openInterest）/`getOrders()` 访问器，
//! 相关子断言改用等价的 `user_position`/`user_account`（数值等同，如 openInterest==open_volume），
//! 或整条 order-book 明细断言略去（下方逐处标注）。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::position_direction::PositionDirection;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest};

    // 对应 Java TestConstants（逐字对齐）。
    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    const BNB_ID: i32 = 11; // Java baseCurrency(11)
    const USDT_ID: i32 = 12; // Java quoteCurrency(12)

    // ------------------------------------------------------------------
    // spec / helper 构造
    // ------------------------------------------------------------------

    /// 对应 Java `container.initSymbol()`：10001 perpetual、feeScaleK=100、maker=1/taker=2、
    /// maintenanceMargin{1000:5,100000:10}@scaleK1000、maxLeverage{2000:10,5000:20,10000:50}。
    fn init_symbol_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 10001,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 100,
            maker_fee: 1,
            taker_fee: 2,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(2000, 10), (5000, 20), (10_000, 50)]),
            ..Default::default()
        }
    }

    /// 对应 Java `container.initSymbolExchange()`：30001 现货、base 21/quote 12、feeScaleK=100、maker=1/taker=2。
    fn init_symbol_exchange_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 30001,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: 21,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 100,
            maker_fee: 1,
            taker_fee: 2,
            ..Default::default()
        }
    }

    /// 期货下单（ISOLATED、非 reduce-only），返回 result code。
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

    // 测试初始不设置 leverage → 归一为 1。
    #[test]
    fn test_init_leverage() {
        let spec = CoreSymbolSpecification {
            symbol_id: 10001,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 1_000_000,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            max_leverage: BTreeMap::from([(2000, 5), (5000, 20)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 10_000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 50_000, 20);

        // 未设置 leverage（传 0 → 归一 1）。
        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 10, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().leverage, 1);
    }

    // 下不同 leverage 报 RISK_LEVERAGE_MISMATCH。
    #[test]
    fn test_leverage_mismatch() {
        let spec = CoreSymbolSpecification {
            symbol_id: 10001,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 1_000_000,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            max_leverage: BTreeMap::from([(2000, 5), (5000, 20)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 10_000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 50_000, 20);

        // 10x 开 10 手 → 成功。
        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 10, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::Success
        );
        // 同 symbol 再下 20x → RISK_LEVERAGE_MISMATCH。
        assert_eq!(
            place_fut(&mut api, 10002, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 20),
            CommandResultCode::RiskLeverageMismatch
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_buy_size, 10);
    }

    // 调整 leverage：11x 保证金更省 → 成功；9x 需更多保证金（含手续费）→ RISK_NSF。
    #[test]
    fn test_adjust_leverage() {
        let deposit: i64 = 1_200;
        let spec = init_symbol_spec();

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, deposit, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 12_000, 20);

        // 金额只够开 10x。
        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 10, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_buy_size, 10);

        // 11x 可以（保证金更少）。
        assert_eq!(api.leverage_adjustment(UID_1, spec.symbol_id, 11), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_buy_size, 10);

        // 9x 需更多保证金（手续费也计入）→ RISK_NSF。
        assert_eq!(api.leverage_adjustment(UID_1, spec.symbol_id, 9), CommandResultCode::RiskNsf);

        // UID_2 吃掉挂单（UID_1 为 maker，付 makerFee）。
        assert_eq!(
            place_fut(&mut api, 10002, UID_2, spec.symbol_id, 1000, 10, OrderAction::Ask, OrderType::Gtc, 0),
            CommandResultCode::Success
        );

        // makerFee = price*size*makerFee/feeScaleK = 1000*10*1/100 = 100。
        let fee = 1000 * 10 * 1 / 100;
        assert_eq!(api.user_account(UID_1, USDT_ID), deposit - fee);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_sell_size, 0);
        assert_eq!(api.user_position(UID_2, spec.symbol_id).unwrap().pending_buy_size, 0);
    }

    // 开仓后调整 leverage：51x 超上限 → RISK_INVALID_LEVERAGE；50x/15x 通过保证金复检 → 成功。
    #[test]
    fn test_open_position_then_adjust_leverage() {
        let spec = init_symbol_spec();

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 3_500, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 100_000, 20);

        assert_eq!(
            place_fut(&mut api, 30001, UID_1, spec.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 20),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 30002, UID_2, spec.symbol_id, 1000, 50, OrderAction::Ask, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 50);

        // 最高 50x，超出 → RISK_INVALID_LEVERAGE。
        assert_eq!(api.leverage_adjustment(UID_1, spec.symbol_id, 51), CommandResultCode::RiskInvalidLeverage);
        assert_eq!(api.leverage_adjustment(UID_1, spec.symbol_id, 50), CommandResultCode::Success);
        // 新保证金 check 通过后允许调整。
        assert_eq!(api.leverage_adjustment(UID_1, spec.symbol_id, 15), CommandResultCode::Success);
    }

    // 自选杠杆开仓：10x 开 100 手，IOC 吃 10 手；OI==open_volume（报表无门面，改断持仓）；再开 1 手 → RISK_NSF。
    #[test]
    fn test_custom_leverage_open_position() {
        let spec = CoreSymbolSpecification {
            symbol_id: 10001,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 1_000_000,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            max_leverage: BTreeMap::from([(2000, 5), (5000, 10)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 10_000, 20);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 100, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 10002, UID_2, spec.symbol_id, 1000, 10, OrderAction::Ask, OrderType::Ioc, 0),
            CommandResultCode::Success
        );

        // Java: accountBalances[quote]==20000（fees 0）、openInterestLong/Short==10。
        assert_eq!(api.user_account(UID_1, USDT_ID) + api.user_account(UID_2, USDT_ID), 20_000);
        assert_eq!(api.fees(USDT_ID), 0);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 10);
        assert_eq!(api.user_position(UID_2, spec.symbol_id).unwrap().open_volume, 10);

        // 保证金已耗尽，再开 1 手 → RISK_NSF。
        assert_eq!(
            place_fut(&mut api, 10003, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::RiskNsf
        );
    }

    // 超出最大杠杆 → RISK_INVALID_LEVERAGE。
    #[test]
    fn test_reject_invalid_leverage() {
        let spec = CoreSymbolSpecification {
            symbol_id: 10002,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 1_000_000,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            max_leverage: BTreeMap::from([(2000, 5), (100_000, 10)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 100_000, 10);

        // leverage 100 超出最大 → RISK_INVALID_LEVERAGE。
        assert_eq!(
            place_fut(&mut api, 20001, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 100),
            CommandResultCode::RiskInvalidLeverage
        );
    }

    // 提现要考虑杠杆持仓占用的保证金：保证金已用满，提现 -1 → RISK_NSF，余额不变。
    #[test]
    fn test_custom_leverage_withdraw() {
        let spec = CoreSymbolSpecification {
            symbol_id: 10001,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 100,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            max_leverage: BTreeMap::from([(2000, 5), (10_000, 10), (50_000, 50)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 10_000, 20);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 100, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::Success
        );
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_volume, 0);
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.pending_buy_size, 100);
            assert_eq!(pos.pending_sell_size, 0);
        }

        // 提现 -1（balance_adjustment 负额）→ RISK_NSF。
        assert_eq!(
            api.balance_adjustment(UID_1, USDT_ID, -1, 30),
            CommandResultCode::RiskNsf
        );
        assert_eq!(api.user_account(UID_1, USDT_ID), 10_000);
    }

    // 下两笔不同 leverage 的订单 → 第二笔 RISK_LEVERAGE_MISMATCH，之前订单不受影响。
    // （Java 的 `getOrders()` 明细断言无门面访问器，改断 position pending。）
    #[test]
    fn test_two_leverage_orders() {
        let spec = init_symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 10_000, 20);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::Success
        );
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_volume, 0);
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.pending_buy_size, 1);
            assert_eq!(pos.pending_sell_size, 0);
        }

        assert_eq!(
            place_fut(&mut api, 10002, UID_1, spec.symbol_id, 1100, 5, OrderAction::Bid, OrderType::Gtc, 20),
            CommandResultCode::RiskLeverageMismatch
        );
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_volume, 0);
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.pending_buy_size, 1);
            assert_eq!(pos.pending_sell_size, 0);
        }
    }

    // 1x → 成功；50x → RISK_LEVERAGE_MISMATCH；再下一笔 1x → 成功。
    #[test]
    fn test_two_leverage_orders2() {
        let spec = init_symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 10_000, 20);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 1),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_buy_size, 1);

        assert_eq!(
            place_fut(&mut api, 10002, UID_1, spec.symbol_id, 1100, 5, OrderAction::Bid, OrderType::Gtc, 50),
            CommandResultCode::RiskLeverageMismatch
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_buy_size, 1);

        // 同 1x → 成功。
        assert_eq!(
            place_fut(&mut api, 10003, UID_1, spec.symbol_id, 1200, 1, OrderAction::Bid, OrderType::Gtc, 1),
            CommandResultCode::Success
        );
    }

    // orderId 相同、leverage 不同 → RISK_LEVERAGE_MISMATCH，之前订单不受影响、余额不变。
    #[test]
    fn test_two_leverage_orders_with_same_order_id() {
        let spec = init_symbol_spec();
        let charge: i64 = 10_000;
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, charge, 10);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_buy_size, 1);

        // 相同 orderId + 不同 leverage → process order 阶段 RISK_LEVERAGE_MISMATCH。
        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1100, 5, OrderAction::Bid, OrderType::Gtc, 20),
            CommandResultCode::RiskLeverageMismatch
        );
        {
            assert_eq!(api.user_account(UID_1, USDT_ID), charge);
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_volume, 0);
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.pending_buy_size, 1);
            assert_eq!(pos.pending_sell_size, 0);
        }
    }

    // 下现货单需考虑杠杆持仓保证金：期货保证金用满后，free+freeFuturesMargin<0 → 现货单 RISK_NSF。
    #[test]
    fn test_place_exchange_while_has_leverage() {
        let fut = init_symbol_spec();
        let spot = init_symbol_exchange_spec();

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        api.add_currency(21, 1); // 现货 base currency
        assert_eq!(api.add_futures_symbol(fut.clone()), CommandResultCode::Success);
        assert_eq!(api.add_symbol(spot.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(fut.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 2_000, 10);

        // 50x 开 50 手：initMargin=50000/50=1000 + taker fee 50*1000*2/100=1000 = 2000（恰好用满）。
        assert_eq!(
            place_fut(&mut api, 30000, UID_1, fut.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 50),
            CommandResultCode::Success
        );
        assert!(api.user_position(UID_1, fut.symbol_id).is_some());

        // free + freeFuturesMargin <= 0，现货 BID 需 RISK_NSF。
        assert_eq!(
            api.place_order(PlaceOrderRequest {
                order_id: 30001,
                uid: UID_1,
                symbol: spot.symbol_id,
                price: 1000,
                size: 1,
                reserve_bid_price: 1000,
                action: OrderAction::Bid,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::RiskNsf
        );
        assert_eq!(api.user_account(UID_1, USDT_ID), 2_000);
        assert!(api.user_position(UID_1, fut.symbol_id).is_some());
    }

    // ============================================================================================
    // 强平 backfill（enable_liquidation + set_mark_price_at 触发定向扫描）。
    // ============================================================================================

    const MAX_VALUE: i64 = 4_000_000;

    // testLiquidationTriggeredByHighLeverage —— 50x 高杠杆多头，价格从 1000 跌到 980（跌幅 > 破产阈值）→ 全平。
    // spec 10003：feeScaleK=1_000_000/maker=taker=0，maintenanceMarginScaleK 未设（=0，同 Java builder 默认）。
    #[test]
    fn test_liquidation_triggered_by_high_leverage() {
        let spec = CoreSymbolSpecification {
            symbol_id: 10003,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 1_000_000,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            max_leverage: BTreeMap::from([(2000, 5), (5000, 10), (10_000, 50)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 1_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 100_000, 20);

        // 50x 开 50 手（initMargin=50000/50=1000，恰好用满 UID_1 的 1000）。
        assert_eq!(
            place_fut(&mut api, 30001, UID_1, spec.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 50),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 30002, UID_2, spec.symbol_id, 1000, 50, OrderAction::Ask, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 50);

        // 破产价上方挂 BID 承接 FORCE 卖单（价跌前挂，避免 set_mark_price_at 触发时无流动性）。
        assert_eq!(
            place_fut(&mut api, 30003, UID_2, spec.symbol_id, 980, 50, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::Success
        );

        // 落价 980 触发定向强平：equity 归零 → 全平。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price(spec.symbol_id, 980), CommandResultCode::Success);
        assert!(api.user_position(UID_1, spec.symbol_id).is_none(), "50x 多头应被全平");
        assert!(api.total_balance().is_global_zero());
    }

    // testLiquidationOfMaintenanceMargin —— 50x 多头，价格 1000→980 触发**部分**强平（引擎按 calculate_size_to_liquidate
    // 只平恢复保证金所需的 1 手：openVolume 50→49）。spec=initSymbol（feeScaleK100，maker1%/taker2%）。
    #[test]
    fn test_liquidation_of_maintenance_margin() {
        let spec = init_symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 2_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 100_000, 20);

        // UID_1 maker BID（LONG），UID_2 taker ASK（SHORT），50x 开 50 手。
        assert_eq!(
            place_fut(&mut api, 30001, UID_1, spec.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 50),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 30002, UID_2, spec.symbol_id, 1000, 50, OrderAction::Ask, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
        // 开仓确定性态：UID_1 maker fee=50000*1/100=500，UID_2 taker fee=50000*2/100=1000。
        assert_eq!(api.user_account(UID_1, USDT_ID), 2_000 - 500);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().direction, PositionDirection::Long);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 50);
        assert_eq!(api.user_account(UID_2, USDT_ID), 100_000 - 1_000);
        assert_eq!(api.user_position(UID_2, spec.symbol_id).unwrap().direction, PositionDirection::Short);
        assert_eq!(api.user_position(UID_2, spec.symbol_id).unwrap().open_volume, 50);

        // BP=1000 承接：UID_2 挂 BID 1@1000。
        assert_eq!(
            place_fut(&mut api, 30003, UID_2, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::Success
        );

        // 落价 980 触发定向强平：只平 1 手恢复维持保证金。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price(spec.symbol_id, 980), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 49, "维持保证金强平只平 1 手");
        assert_eq!(api.user_position(UID_2, spec.symbol_id).unwrap().open_volume, 49, "对手 SHORT 相应减 1");
        assert!(api.total_balance().is_global_zero());
    }

    // testLiquidationSendWarn —— 价格 1000→981（跌幅不足强平阈值）→ 仅告警（Rust 外置 no-op），仓位不变。
    #[test]
    fn test_liquidation_send_warn() {
        let spec = init_symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 2_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 100_000, 20);

        assert_eq!(
            place_fut(&mut api, 30001, UID_1, spec.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 50),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 30002, UID_2, spec.symbol_id, 1000, 50, OrderAction::Ask, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 50);

        // 落价 981 仅触发预警：equity 仍 >= 维持保证金 → 不强平。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price(spec.symbol_id, 981), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 50, "981 未达强平阈值，仓位不变");
        assert!(api.total_balance().is_global_zero());
    }

    // testLiquidationLeverage —— 强平用最新 leverage（50x）。价跌到 25 才触发（若按 1x 计算不会强平）→ 全平。
    #[test]
    fn test_liquidation_leverage() {
        let spec = init_symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 50_020, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, MAX_VALUE, 20);

        // 50x 开 50 手。
        assert_eq!(
            place_fut(&mut api, 30000, UID_1, spec.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 50),
            CommandResultCode::Success
        );
        // 同 symbol 再下 1x（不同 leverage）→ RISK_LEVERAGE_MISMATCH（不影响已挂仓位）。
        assert_eq!(
            place_fut(&mut api, 30001, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 1),
            CommandResultCode::RiskLeverageMismatch
        );
        assert_eq!(
            place_fut(&mut api, 30002, UID_2, spec.symbol_id, 1000, 50, OrderAction::Ask, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
        // 开仓确定性态：UID_1 maker fee=500，UID_2 taker fee=1000。
        assert_eq!(api.user_account(UID_1, USDT_ID), 50_020 - 500);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().direction, PositionDirection::Long);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 50);
        assert_eq!(api.user_account(UID_2, USDT_ID), MAX_VALUE - 1_000);
        assert_eq!(api.user_position(UID_2, spec.symbol_id).unwrap().open_volume, 50);

        // BP=1000 承接：UID_2 挂 BID 50@1000。
        assert_eq!(
            place_fut(&mut api, 30003, UID_2, spec.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::Success
        );

        // 落价 25 触发定向强平（按最新 50x）→ 全平。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price(spec.symbol_id, 25), CommandResultCode::Success);
        assert!(api.user_position(UID_1, spec.symbol_id).is_none(), "按最新 50x 应在 25 触发全平");
        assert!(api.total_balance().is_global_zero());
    }
}
