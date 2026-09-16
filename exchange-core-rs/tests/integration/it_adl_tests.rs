//! 翻译自 Java `exchange.core2.tests.integration.ITExchangeCoreADL`（5 个 @Test）——期货强平的
//! ADL（自动减仓）/ IF（保险基金）接管路径集成测试。
//!
//! Java 用多分片 `LiquidationEngine::stop` + `triggerLiquidation` + `ApiInsuranceFundDeposit(shardId)`
//! 驱动 FORCE→IF→ADL 级联；Rust `ExchangeApi` 是**单分片**门面，改用：
//!   - `enable_liquidation()` + `set_mark_price_at(sym, adverse, ts)` 触发定向扫描 + 自动排空级联；
//!   - IF 充值走通用 `submit(IF_DEPOSIT{symbol, price=currencyAmount, order_id=txid})`（单分片，无 shardId）。
//!
//! 强平/ADL/IF 的逐笔 fund event 走内部排空命令、`last_fund_events()` 不捕获，故断言最终**状态**：
//!   - loser 仓位移除；ADL 对手方（winner）被减仓到 golden 剩余量；
//!   - IF 接管量经 `total_balance().if_open_interest_long/short[sym]` 读取；IF `reserved` 经
//!     `insurance_fund().futures[sym].reserved` 读取；
//!   - 全局守恒 `total_balance().is_global_zero()`（含 IF 项）。
//!
//! ADL symbol 逐字复刻 Java 字段：id 10001, base=11 quote=12, takerFee=2 feeScaleK=1000,
//! initMargin=1/scaleK100, MM{1000:5,100000:10}@scaleK1000, maxLeverage{1000:75,100000:40}。货币 digit0→scaleK1。
//!
//! **未翻译的 @Test（及原因）——共 2 个跳过**：
//!   - `testIFMultiShardBoundary`：断言 "单一 shard 无法接满 5（一个接 3 一个接 2）"，本质是多分片 IF 边界
//!     切分；`ExchangeApi` 单分片，无法复刻跨 shard 承接切分。
//!   - `testPersistenceAndRecovery`：依赖 `ApiPersistState`/`ApiRecoverState`/`requestStateHash` 快照-恢复，
//!     `ExchangeApi` 未暴露持久化/恢复入口。
//!   其余 3 个（testADL / testIFTakeover / testLiquidationReopenAndReliquidate）已翻译（单分片语义等价）。

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
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest};

    const BASE_ID: i32 = 11;
    const QUOTE_ID: i32 = 12;
    const SYM: i32 = 10001;

    const UID_1: i64 = 1; // LOSER
    const UID_2: i64 = 2; // WINNER / MAKER
    const UID_3: i64 = 3; // MAKER
    const MAX_VALUE: i64 = 4_000_000;

    fn adl_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 2,
            fee_scale_k: 1_000,
            init_margin: 1,
            init_margin_scale_k: 100,
            maintenance_margin: BTreeMap::from([(1_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(1_000, 75), (100_000, 40)]),
            ..Default::default()
        }
    }

    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(adl_spec()), CommandResultCode::Success);
        api
    }

    fn seed_user(api: &mut ExchangeApi, uid: i64, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE_ID, amount, txid), CommandResultCode::Success);
    }

    fn place(api: &mut ExchangeApi, order_id: i64, uid: i64, price: i64, size: i64, action: OrderAction, margin_mode: MarginMode) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: SYM,
            price,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage: 1,
            margin_mode,
            reduce_only: false,
        })
    }

    /// IF_DEPOSIT（单分片）：price=currencyAmount。
    fn if_deposit(api: &mut ExchangeApi, amount: i64, txid: i64) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::IfDeposit,
            symbol: SYM,
            price: amount,
            order_id: txid,
            ..Default::default()
        })
    }

    fn assert_conserved(api: &ExchangeApi) {
        let tcb = api.total_balance();
        assert!(tcb.is_global_zero(), "全局守恒被打破: {:?}", tcb.global_balances_sum());
    }

    // ================================================================================================
    // 1. testADL —— loser 巨亏被强平，无 IF 无书面流动性 → ADL 减 winner 盈利空仓 10→5。
    // ================================================================================================

    #[test]
    fn adl_deleverages_winning_counterparty() {
        let mut api = setup();
        let (loser, winner, maker) = (UID_1, UID_2, UID_3);
        seed_user(&mut api, loser, 5_000, 1);
        seed_user(&mut api, winner, 50_000, 2);
        seed_user(&mut api, maker, MAX_VALUE, 3);

        assert_eq!(api.set_mark_price(SYM, 1_000), CommandResultCode::Success);

        // 1. LOSER 开高杠杆多仓 5 @1000（ISOLATED），MAKER 对手 ASK（CROSS）。
        assert_eq!(place(&mut api, 1, loser, 1_000, 5, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, maker, 1_000, 5, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_position(loser, SYM).unwrap().open_volume, 5);

        // 2. WINNER 开空仓 10 @1000（ISOLATED，ADL 资格 100），MAKER 对手 BID（CROSS）。
        assert_eq!(place(&mut api, 3, winner, 1_000, 10, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 4, maker, 1_000, 10, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_position(winner, SYM).unwrap().open_volume, 10);

        // 3. 价格暴跌 1000→600：LOSER 多头巨亏被强平；无 IF、无书面对手 → ADL 减 WINNER 盈利空仓。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(SYM, 600, 2_000), CommandResultCode::Success);

        // 4. LOSER 清仓；WINNER 被 ADL 减到 5。
        assert!(api.user_position(loser, SYM).is_none(), "LOSER 应被清仓");
        assert_eq!(api.user_position(winner, SYM).unwrap().open_volume, 5, "WINNER 被 ADL 减仓 10→5");
        // 金额精确锚点：ADL 只减仓、不动 winner 账户；winner 剩余仓 profit=0（被减部分按破产价实现，无浮盈残留）。
        assert_eq!(api.user_account(winner, QUOTE_ID), 50_000, "ADL 不动 winner 账户余额");
        assert_eq!(api.user_position(winner, SYM).unwrap().profit, 0, "winner 剩余仓无浮盈残留");
        assert_eq!(api.user_account(loser, QUOTE_ID), 4_960, "loser 亏逐仓保证金（破产价结算残留）");
        assert_eq!(api.user_account(maker, QUOTE_ID), 3_999_970, "maker 对手方净结算");
        assert_eq!(api.fees(QUOTE_ID), 30, "开仓+减仓成交 taker 费入池");
        assert_eq!(api.insurance_fund().futures.values().map(|e| e.available).sum::<i64>(), 0, "无 liquidation_fee → IF 不增");
        assert_conserved(&api);
    }

    // ================================================================================================
    // 2. testIFTakeover —— IF 已充值 → IF 接管 loser 仓位（无 ADL，MAKER 保持 5）。
    // ================================================================================================

    #[test]
    fn if_takeover_absorbs_loser_position_no_adl() {
        let mut api = setup();
        let (loser, maker) = (UID_1, UID_2);
        seed_user(&mut api, loser, 5_000, 1);
        seed_user(&mut api, maker, MAX_VALUE, 2);

        assert_eq!(api.set_mark_price(SYM, 1_000), CommandResultCode::Success);

        // LOSER 多头 5，MAKER 空头 5（CROSS，ADL 资格 0）。
        assert_eq!(place(&mut api, 1, loser, 1_000, 5, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, maker, 1_000, 5, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_position(loser, SYM).unwrap().open_volume, 5);

        // IF 充值 5*1000（单分片）→ 有足够资金承接 takeover。
        assert_eq!(if_deposit(&mut api, 5 * 1_000, 1), CommandResultCode::Success);

        // 价格暴跌触发强平：MAKER 不可 ADL（CROSS 资格 0）→ IF 接管。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(SYM, 600, 2_000), CommandResultCode::Success);

        // LOSER 清仓；MAKER 保持 5（无 ADL）；IF 接管 LONG 5。
        assert!(api.user_position(loser, SYM).is_none(), "LOSER 应清仓");
        assert_eq!(api.user_position(maker, SYM).unwrap().open_volume, 5, "MAKER 无 ADL，保持 5");
        assert_eq!(
            api.total_balance().if_open_interest_long.get(&SYM).copied().unwrap_or(0),
            5,
            "IF 接管 LONG 5"
        );
        // 金额精确锚点：IF 接管而非 ADL —— loser 亏同额 40；IF 用存入的 5000 承接 LONG 5（available 余 40）；
        // 无减仓成交，fees 只有开仓两笔（=10，少于 ADL 的 30）。
        assert_eq!(api.user_account(loser, QUOTE_ID), 4_960, "loser 亏逐仓保证金");
        assert_eq!(api.user_account(maker, QUOTE_ID), 3_999_990, "maker 对手方净结算（无 ADL 减仓）");
        assert_eq!(api.fees(QUOTE_ID), 10, "仅开仓成交费（无减仓）");
        assert_eq!(api.insurance_fund().futures.values().map(|e| e.available).sum::<i64>(), 40, "IF 承接持仓后 available 余额");
        assert_eq!(api.insurance_fund().futures.values().map(|e| e.reserved).sum::<i64>(), 0, "IF reserved 无泄漏");
        assert_conserved(&api);
    }

    // ================================================================================================
    // 3. testLiquidationReopenAndReliquidate —— 两轮强平（IF 接管），断言两轮都清仓、reserved 无泄漏。
    // ================================================================================================

    #[test]
    fn liquidation_reopen_and_reliquidate_no_reserved_leak() {
        let mut api = setup();
        let (loser, maker) = (UID_1, UID_2);
        seed_user(&mut api, loser, 20_000, 1);
        seed_user(&mut api, maker, MAX_VALUE, 2);

        assert_eq!(api.set_mark_price(SYM, 1_000), CommandResultCode::Success);

        // ===== 第一次 =====
        assert_eq!(if_deposit(&mut api, 3 * 1_000, 1), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1, loser, 1_000, 5, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 2, maker, 1_000, 5, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price_at(SYM, 600, 2_000), CommandResultCode::Success);
        assert!(api.user_position(loser, SYM).is_none(), "第一次应清仓");

        // ===== 第二次 =====
        assert_eq!(if_deposit(&mut api, 2 * 1_000, 100), CommandResultCode::Success);
        assert_eq!(place(&mut api, 3, loser, 700, 4, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 4, maker, 700, 4, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.set_mark_price_at(SYM, 400, 3_000), CommandResultCode::Success);
        assert!(api.user_position(loser, SYM).is_none(), "第二次也应清仓");

        // reserved 全部释放，无 pending 泄漏。
        if let Some(entry) = api.insurance_fund().futures.get(&SYM) {
            assert_eq!(entry.reserved, 0, "IF reserved 不得残留");
        }
        assert_conserved(&api);
    }
}
