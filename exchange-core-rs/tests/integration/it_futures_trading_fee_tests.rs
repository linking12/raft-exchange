//! 翻译自 Java `ITFuturesTradingFeeCalculationTest`（27 个 @Test），验证期货 taker/maker 手续费的精确金额。
//!
//! Java 版通过 `SimpleEventsProcessor4Test` 捕获逐笔 `FuturesExecutionReport.fee`；Rust `ExchangeApi`
//! 没有逐笔执行报告事件，改为断言等价的**累计** `api.fees(cur)` + 逐用户 `api.user_account(uid,cur)` +
//! `api.user_position(uid,symbol)`——两者反映同一组 golden fee/PnL/balance 值（费用最终去向就是 fees 池 +
//! 从 accounts 扣除）。
//!
//! 期货 symbol 逐字复刻 Java `ExchangeTestContainer.initFutureSymbols()`：
//!   BTC(id 10000): base=XBT quote=USD, maker_fee=10 taker_fee=20, fee_scale_k=0（固定费）,
//!                  init_margin=1/init_margin_scale_k=100, maintenance/leverage 分档表。
//!   ETH(id 10001): maker_fee=1 taker_fee=2 fee_scale_k=100（比例费）, 其余同 BTC。
//! 货币 digit(0) → scale_k=1（恒等缩放，无 size_price 换算）。
//!
//! HEDGE 双向持仓类现已翻译（harness 提供 `api.adjust_position_mode(uid, hedge)`；双腿经
//! `api.ups().get(uid).positions.values()` 按 `direction` 过滤读取 ±symbol 双键，见本文件 `hedge_leg` helper）：
//!   `testFuturesHedgeFullLifecycleWithDepositWithdraw`, `testFuturesIsolatedHedgeFullLifecycleWithDepositWithdraw`,
//!   `testHedgeModePositionOpeningFeeEvents`, `testHedgeModePartialClosingFeeEvents`,
//!   `testHedgeModePositionReversalFeeEvents`, `testHedgeModeMixedOrderTypesFeeEvents`,
//!   `testHedgeModeFeeConsistencyWithGlobalBalance`。逐笔 `FuturesExecutionReport.fee` 仍用等价的累计
//!   `api.fees(USD)` + 逐用户 `api.user_account` 断言（同 ONEWAY 版）。
//!
//! **仍跳过的 @Test（及原因）**——强平类：`testFuturesLiquidationFullLifecycleConservation`,
//!   `testFuturesHedgeLiquidationFullLifecycleConservation`：harness 无 triggerLiquidation /
//!   LiquidationEngine 控制 / updateCurrentPriceTo / groupingControl，无法驱动 FORCE→IF→ADL 强平流程（genuinely N/A）。
//!   共 2 个跳过，其余 25 个已翻译。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
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
    use exchange_core_rs::core::utils::core_arithmetic_utils::{calculate_maker_fee, calculate_taker_fee};

// ================================================================================================
// 常量：逐字对齐 Java TestConstants / initFutureSymbols
// ================================================================================================

const XBT: i32 = 3762; // CURRENECY_XBT
const USD: i32 = 840; // CURRENECY_USD
const ETH: i32 = 3928; // CURRENECY_ETH
const BTC_SYM: i32 = 10000;
const ETH_SYM: i32 = 10001;

// BTC 固定费费率（== Java BTC_SYMBOL 常量：makerFee=10, takerFee=20, feeScaleK=0）
const BTC_MAKER_FEE: i64 = 10;
const BTC_TAKER_FEE: i64 = 20;
const BTC_FEE_SCALE_K: i64 = 0;

// ETH 比例费费率（== Java ETH_SYMBOL：makerFee=1, takerFee=2, feeScaleK=100）
const ETH_MAKER_FEE: i64 = 1;
const ETH_TAKER_FEE: i64 = 2;
const ETH_FEE_SCALE_K: i64 = 100;

// ================================================================================================
// spec / api 构造 helper
// ================================================================================================

/// 逐字复刻 Java `initFutureSymbols()` 的 BTC futures spec（`symbols.get(0)`）。
fn btc_futures_spec() -> CoreSymbolSpecification {
    CoreSymbolSpecification {
        symbol_id: BTC_SYM,
        symbol_type: SymbolType::FuturesContractPerpetual,
        base_currency: XBT,
        quote_currency: USD,
        base_scale_k: 1,
        quote_scale_k: 1,
        maker_fee: BTC_MAKER_FEE,
        taker_fee: BTC_TAKER_FEE,
        fee_scale_k: BTC_FEE_SCALE_K,
        init_margin: 1,
        init_margin_scale_k: 100,
        maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
        maintenance_margin_scale_k: 1000,
        max_leverage: BTreeMap::from([(2000, 5), (100_000, 10)]),
        ..Default::default()
    }
}

/// 逐字复刻 Java `initFutureSymbols()` 的 ETH futures spec（`symbols.get(1)`，比例费）。
fn eth_futures_spec() -> CoreSymbolSpecification {
    CoreSymbolSpecification {
        symbol_id: ETH_SYM,
        symbol_type: SymbolType::FuturesContractPerpetual,
        base_currency: ETH,
        quote_currency: USD,
        base_scale_k: 1,
        quote_scale_k: 1,
        maker_fee: ETH_MAKER_FEE,
        taker_fee: ETH_TAKER_FEE,
        fee_scale_k: ETH_FEE_SCALE_K,
        init_margin: 1,
        init_margin_scale_k: 100,
        maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
        maintenance_margin_scale_k: 1000,
        max_leverage: BTreeMap::from([(2000, 5), (100_000, 10)]),
        ..Default::default()
    }
}

/// 注册 XBT/USD 货币 + BTC futures symbol，并把 mark 价设成 `mark`。
fn seed_btc(mark: i64) -> ExchangeApi {
    let mut api = ExchangeApi::new();
    api.add_currency(XBT, 1);
    api.add_currency(USD, 1);
    assert_eq!(api.add_futures_symbol(btc_futures_spec()), CommandResultCode::Success);
    assert_eq!(api.set_mark_price(BTC_SYM, mark, 0), CommandResultCode::Success);
    api
}

/// 建用户 + 充值 USD（对应 Java `createUserWithSpecificMoney`：ADD_USER + BALANCE_ADJUSTMENT）。
fn seed_user(api: &mut ExchangeApi, uid: i64, usd: i64, txid: i64) {
    assert_eq!(api.add_user(uid), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(uid, USD, usd, txid), CommandResultCode::Success);
}

/// 期货下单便捷封装（leverage 默认字段由调用方给；ONEWAY，reduce_only=false）。
#[allow(clippy::too_many_arguments)]
fn place(
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

/// 固定费 BTC maker/taker 费（== Java `CoreArithmeticUtils.calculate*Fee(size, price, BTC_SYMBOL)`）。
fn btc_maker(size: i64, price: i64) -> i64 {
    calculate_maker_fee(size, price, BTC_MAKER_FEE, BTC_FEE_SCALE_K)
}
fn btc_taker(size: i64, price: i64) -> i64 {
    calculate_taker_fee(size, price, BTC_TAKER_FEE, BTC_FEE_SCALE_K)
}

/// 全局守恒断言（== Java `TotalCurrencyBalanceReportResult.isGlobalBalancesAllZero()` 的 USD 分量）：
/// Σ_users accounts(USD) + adjustments(USD) + fees(USD) + Σ_open_positions(estimate_pnl(mark)+extra_margin) == 0。
fn assert_conserved_usd(api: &ExchangeApi) {
    let mut total: i64 = api.ups().users.values().map(|p| p.account(USD)).sum();
    total += api.adjustments(USD);
    total += api.fees(USD);
    for p in api.ups().users.values() {
        for pos in p.positions.values() {
            if pos.currency != USD {
                continue;
            }
            let mark = api
                .risk()
                .mark_price(pos.symbol)
                .unwrap_or_else(|| panic!("open position on symbol {} missing mark price", pos.symbol));
            total += pos.estimate_pnl(mark);
            total += pos.extra_margin;
        }
    }
    assert_eq!(total, 0, "期货全局守恒被打破：USD total={total}");
}

/// HEDGE 双腿读取：`ups().get(uid).positions.values()` 按 `direction` 过滤（±symbol 双键，两腿 `.symbol==symbol`）。
fn hedge_leg(api: &ExchangeApi, uid: i64, symbol: i32, dir: PositionDirection) -> Option<&SymbolPositionRecord> {
    api.ups()
        .get(uid)?
        .positions
        .values()
        .find(|p| p.symbol == symbol && p.direction == dir)
}

/// 某 symbol 下 openVolume 总和（跨双腿）。用于平仓后断言全部归零。
fn hedge_open_volume_sum(api: &ExchangeApi, uid: i64, symbol: i32) -> i64 {
    api.ups()
        .get(uid)
        .map(|p| p.positions.values().filter(|r| r.symbol == symbol).map(|r| r.open_volume).sum())
        .unwrap_or(0)
}

// ================================================================================================
// 1. testFuturesGtcMakerTakerFeeCalculation —— GTC maker BID / taker ASK 开仓固定费
// ================================================================================================

#[test]
fn futures_gtc_maker_taker_fee_calculation() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let size = 10i64;
    let price = 50_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    // maker GTC BID（resting）→ LONG；taker GTC ASK 吃单 → SHORT。
    assert_eq!(place(&mut api, 1001, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 1002, TAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let maker_pos = api.user_position(MAKER, BTC_SYM).expect("maker 应有仓位");
    assert_eq!(maker_pos.direction, PositionDirection::Long);
    assert_eq!(maker_pos.open_volume, size);
    let taker_pos = api.user_position(TAKER, BTC_SYM).expect("taker 应有仓位");
    assert_eq!(taker_pos.direction, PositionDirection::Short);
    assert_eq!(taker_pos.open_volume, size);

    // golden fee：固定费 maker=size*10=100, taker=size*20=200。
    let expected_maker_fee = btc_maker(size, price);
    let expected_taker_fee = btc_taker(size, price);
    assert_eq!(expected_maker_fee, 100);
    assert_eq!(expected_taker_fee, 200);

    assert_eq!(api.user_account(MAKER, USD), deposit - expected_maker_fee);
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_taker_fee);
    assert_eq!(api.fees(USD), expected_maker_fee + expected_taker_fee);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 2. testFuturesIocTakerFeeCalculation —— IOC taker 吃单固定费
// ================================================================================================

#[test]
fn futures_ioc_taker_fee_calculation() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let size = 5i64;
    let price = 45_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    // maker GTC BID → LONG；taker IOC ASK → SHORT，完全成交。
    assert_eq!(place(&mut api, 2001, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 2002, TAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Ioc, MarginMode::Cross, 1), CommandResultCode::Success);

    let expected_maker_fee = btc_maker(size, price); // 50
    let expected_taker_fee = btc_taker(size, price); // 100
    assert_eq!(expected_taker_fee, 100);

    assert_eq!(api.user_account(MAKER, USD), deposit - expected_maker_fee);
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_taker_fee);
    assert_eq!(api.fees(USD), expected_maker_fee + expected_taker_fee);
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, size);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 3. testFuturesPerUserBalanceAcrossOrderTypes —— 每种 taker OrderType 下逐用户闭式余额
// ================================================================================================

fn run_per_user_balance(taker_type: OrderType) {
    const MAKER: i64 = 9001;
    const TAKER: i64 = 9002;
    let size = 4i64;
    let price = 50_000i64;
    let deposit = 1_000_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    // ===== 开仓：maker GTC ASK 开 SHORT；taker <type> BID 开 LONG =====
    assert_eq!(place(&mut api, 9101, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    // BUDGET 单 price 字段是总预算 notional。
    let taker_price = match taker_type {
        OrderType::FokBudget | OrderType::IocBudget => size * price,
        _ => price,
    };
    assert_eq!(place(&mut api, 9102, TAKER, BTC_SYM, taker_price, size, OrderAction::Bid, taker_type, MarginMode::Cross, 1), CommandResultCode::Success);

    // ===== 平仓（反向撮合，maker/taker 换边）=====
    // 原 taker 挂 ASK 变 maker；原 maker 吃 BID 变 taker。
    assert_eq!(place(&mut api, 9103, TAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9104, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    // 仓位清空。
    assert!(api.user_position(MAKER, BTC_SYM).is_none(), "[{taker_type:?}] maker 仓位应全平");
    assert!(api.user_position(TAKER, BTC_SYM).is_none(), "[{taker_type:?}] taker 仓位应全平");

    let maker_fee = btc_maker(size, price); // 40
    let taker_fee = btc_taker(size, price); // 80
    // 原 maker：开仓 maker fee + 平仓 taker fee；原 taker：开仓 taker fee + 平仓 maker fee。
    assert_eq!(api.user_account(MAKER, USD), deposit - maker_fee - taker_fee, "[{taker_type:?}] maker account");
    assert_eq!(api.user_account(TAKER, USD), deposit - taker_fee - maker_fee, "[{taker_type:?}] taker account");
    assert_eq!(api.fees(USD), 2 * (maker_fee + taker_fee));
    assert_conserved_usd(&api);
}

#[test]
fn futures_per_user_balance_across_order_types() {
    run_per_user_balance(OrderType::Gtc);
    run_per_user_balance(OrderType::Ioc);
    run_per_user_balance(OrderType::FokBudget);
    run_per_user_balance(OrderType::IocBudget);
}

// ================================================================================================
// 4. testFuturesFullLifecycleWithDepositWithdraw —— 充值→开→平→提现→对账（adjustments）
// ================================================================================================

fn run_full_lifecycle(taker_type: OrderType) {
    const MAKER: i64 = 9201;
    const TAKER: i64 = 9202;
    let size = 4i64;
    let price = 50_000i64;
    let deposit = 1_000_000i64;

    let mut api = seed_btc(price);
    // 阶段 1：充值。
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);
    assert_eq!(api.user_account(MAKER, USD), deposit);
    assert_eq!(api.user_account(TAKER, USD), deposit);
    assert_eq!(api.adjustments(USD), -2 * deposit, "[{taker_type:?}] 充值后 adjustments = -2*deposit");
    assert_conserved_usd(&api);

    // 阶段 2：开仓。
    assert_eq!(place(&mut api, 9301, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    let taker_price = match taker_type {
        OrderType::FokBudget | OrderType::IocBudget => size * price,
        _ => price,
    };
    assert_eq!(place(&mut api, 9302, TAKER, BTC_SYM, taker_price, size, OrderAction::Bid, taker_type, MarginMode::Cross, 1), CommandResultCode::Success);

    // 阶段 3：平仓（换边）。
    assert_eq!(place(&mut api, 9303, TAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9304, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let maker_fee = btc_maker(size, price);
    let taker_fee = btc_taker(size, price);
    let maker_bal = deposit - maker_fee - taker_fee;
    let taker_bal = deposit - taker_fee - maker_fee;
    assert_eq!(api.user_account(MAKER, USD), maker_bal);
    assert_eq!(api.user_account(TAKER, USD), taker_bal);
    assert_conserved_usd(&api);

    // 阶段 4：提现（负 balance_adjustment）。
    assert_eq!(api.balance_adjustment(MAKER, USD, -maker_bal, 3), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(TAKER, USD, -taker_bal, 4), CommandResultCode::Success);

    // 阶段 5：终态对账。
    assert_eq!(api.user_account(MAKER, USD), 0);
    assert_eq!(api.user_account(TAKER, USD), 0);
    // adjustments 净额 = -(开+平 fee 总和)。
    let expected_adjustments = -2 * (maker_fee + taker_fee);
    assert_eq!(api.adjustments(USD), expected_adjustments, "[{taker_type:?}] adjustments 净额");
    assert_eq!(api.fees(USD), 2 * (maker_fee + taker_fee), "[{taker_type:?}] fees 总额");
    assert_conserved_usd(&api);
}

#[test]
fn futures_full_lifecycle_with_deposit_withdraw() {
    run_full_lifecycle(OrderType::Gtc);
    run_full_lifecycle(OrderType::Ioc);
    run_full_lifecycle(OrderType::FokBudget);
    run_full_lifecycle(OrderType::IocBudget);
}

// ================================================================================================
// 8. testFuturesExtraMarginFullLifecycleConservation —— ISOLATED 追加 extraMargin 全周期守恒
// ================================================================================================

#[test]
fn futures_extra_margin_full_lifecycle_conservation() {
    const TAKER: i64 = 9701;
    const MAKER: i64 = 9702;
    let deposit = 1_000_000i64;
    let extra_margin_amount = 200_000i64;
    let price = 50_000i64;
    let size = 4i64;
    let leverage = 10i32;

    let mut api = seed_btc(price);
    // 阶段 1：充值。
    seed_user(&mut api, TAKER, deposit, 1);
    seed_user(&mut api, MAKER, deposit * 10, 2);
    assert_conserved_usd(&api);

    // 阶段 2：开 ISOLATED LONG（maker ASK + taker BID）。
    assert_eq!(place(&mut api, 9801, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9802, TAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_conserved_usd(&api);

    // 阶段 3：追加 extraMargin —— account 减 extraMarginAmount，position.extra_margin 增 extraMarginAmount。
    let account_before = api.user_account(TAKER, USD);
    assert_eq!(
        api.margin_adjustment(MarginAdjustmentRequest {
            uid: TAKER,
            symbol: BTC_SYM,
            action: OrderAction::Bid, // ONEWAY 下被忽略
            amount: extra_margin_amount,
            margin_mode: MarginMode::Isolated,
            order_id: 9999,
        }),
        CommandResultCode::Success
    );
    assert_eq!(api.user_account(TAKER, USD), account_before - extra_margin_amount, "追加后 account 减 extraMarginAmount");
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().extra_margin, extra_margin_amount, "position.extra_margin == extraMarginAmount");
    assert_conserved_usd(&api);

    // 阶段 4：平仓（taker ASK + maker BID，价格不变 → pnl=0，extra_margin 整额退回）。
    assert_eq!(place(&mut api, 9803, TAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9804, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert!(api.user_position(TAKER, BTC_SYM).is_none(), "平仓后仓位拆除，extra_margin 退回");
    assert_conserved_usd(&api);

    // 阶段 5：提现全部余额。
    let taker_bal = api.user_account(TAKER, USD);
    let maker_bal = api.user_account(MAKER, USD);
    if taker_bal != 0 {
        assert_eq!(api.balance_adjustment(TAKER, USD, -taker_bal, 3), CommandResultCode::Success);
    }
    if maker_bal != 0 {
        assert_eq!(api.balance_adjustment(MAKER, USD, -maker_bal, 4), CommandResultCode::Success);
    }

    // 阶段 6：终态对账。
    assert_eq!(api.user_account(TAKER, USD), 0);
    assert_eq!(api.user_account(MAKER, USD), 0);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 10. testFuturesPositionClosingFeeCalculation —— 部分平仓也按 maker/taker 全量收费
// ================================================================================================

#[test]
fn futures_position_closing_fee_calculation() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    const U3: i64 = 3;
    let open_size = 20i64;
    let close_size = 10i64;
    let price = 48_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);
    seed_user(&mut api, U3, deposit, 3);

    // 开仓：maker BID(LONG maker) + taker ASK(SHORT taker)。
    assert_eq!(place(&mut api, 3001, MAKER, BTC_SYM, price, open_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 3002, TAKER, BTC_SYM, price, open_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    let fees_after_open = api.fees(USD); // 20*10 + 20*20 = 600

    // 部分平仓：taker BID(reverse，减 SHORT→10，resting maker) + U3 ASK(taker，开 SHORT10)。
    assert_eq!(place(&mut api, 3003, TAKER, BTC_SYM, price, close_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 3004, U3, BTC_SYM, price, close_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    // 平仓单：taker 挂 BID 是 maker（按 maker 率全量收），U3 吃单是 taker。
    let expected_maker_close = btc_maker(close_size, price); // 100
    let expected_taker_close = btc_taker(close_size, price); // 200
    assert_eq!(api.fees(USD) - fees_after_open, expected_maker_close + expected_taker_close);

    // 逐用户账户闭式值（mark==price → 部分平递延 pnl 为 0）。
    assert_eq!(api.user_account(MAKER, USD), deposit - btc_maker(open_size, price)); // -200
    assert_eq!(api.user_account(TAKER, USD), deposit - btc_taker(open_size, price) - expected_maker_close); // -400-100
    assert_eq!(api.user_account(U3, USD), deposit - expected_taker_close); // -200

    // 仓位状态。
    assert_eq!(api.user_position(MAKER, BTC_SYM).unwrap().open_volume, open_size);
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, open_size - close_size);
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().direction, PositionDirection::Short);
    assert_eq!(api.user_position(U3, BTC_SYM).unwrap().open_volume, close_size);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 11. testDynamicFeeCalculationAccuracy —— CoreArithmeticUtils 比例费公式直算（纯计算）
// ================================================================================================

#[test]
fn dynamic_fee_calculation_accuracy() {
    // SYMBOLSPEC_DYNAMIC_FEE_XBT_USD：makerFee=1, takerFee=2, feeScaleK=100。
    let price = 50_000i64;
    for size in [15i64, 1, 5, 10, 100, 1000] {
        // price=50000, feeScaleK=100 → size*price*fee 整除 100（ceil==trunc）。
        let expected_maker = size * price * ETH_MAKER_FEE / ETH_FEE_SCALE_K;
        let expected_taker = size * price * ETH_TAKER_FEE / ETH_FEE_SCALE_K;
        assert_eq!(calculate_maker_fee(size, price, ETH_MAKER_FEE, ETH_FEE_SCALE_K), expected_maker, "maker fee size={size}");
        assert_eq!(calculate_taker_fee(size, price, ETH_TAKER_FEE, ETH_FEE_SCALE_K), expected_taker, "taker fee size={size}");
    }
}

// ================================================================================================
// 12. testDynamicFeeTradingWithEthSymbol —— ETH 比例费实际撮合
// ================================================================================================

#[test]
fn dynamic_fee_trading_with_eth_symbol() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let size = 5i64;
    let price = 3_000i64; // ETH 价
    let deposit = 100_000i64;

    let mut api = ExchangeApi::new();
    api.add_currency(XBT, 1);
    api.add_currency(USD, 1);
    api.add_currency(ETH, 1);
    assert_eq!(api.add_futures_symbol(eth_futures_spec()), CommandResultCode::Success);
    assert_eq!(api.set_mark_price(ETH_SYM, price, 0), CommandResultCode::Success);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    // maker BID(LONG) + taker ASK(SHORT)。
    assert_eq!(place(&mut api, 7001, MAKER, ETH_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 7002, TAKER, ETH_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    // ETH 比例费：maker=ceil(5*3000*1/100)=150, taker=ceil(5*3000*2/100)=300。
    let expected_maker_fee = calculate_maker_fee(size, price, ETH_MAKER_FEE, ETH_FEE_SCALE_K);
    let expected_taker_fee = calculate_taker_fee(size, price, ETH_TAKER_FEE, ETH_FEE_SCALE_K);
    assert_eq!(expected_maker_fee, 150);
    assert_eq!(expected_taker_fee, 300);

    assert_eq!(api.user_account(MAKER, USD), deposit - expected_maker_fee);
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_taker_fee);
    assert_eq!(api.fees(USD), expected_maker_fee + expected_taker_fee);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 13. testFuturesMakerOneToManyFeeCalculation —— 1 maker 被 3 taker 吃满
// ================================================================================================

#[test]
fn futures_maker_one_to_many_fee_calculation() {
    const MAKER: i64 = 1;
    const T1: i64 = 2;
    const T2: i64 = 3;
    const T3: i64 = 4;
    let total = 30i64;
    let (s1, s2, s3) = (10i64, 12i64, 8i64);
    let price = 52_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, T1, deposit, 2);
    seed_user(&mut api, T2, deposit, 3);
    seed_user(&mut api, T3, deposit, 4);

    // maker 大 BID（LONG maker resting）。
    assert_eq!(place(&mut api, 4001, MAKER, BTC_SYM, price, total, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    // 三 taker ASK 依次吃（含一个 IOC）。
    assert_eq!(place(&mut api, 4002, T1, BTC_SYM, price, s1, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 4003, T2, BTC_SYM, price, s2, OrderAction::Ask, OrderType::Ioc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 4004, T3, BTC_SYM, price, s3, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    // maker LONG 全量 30，逐笔 maker 费求和。
    assert_eq!(api.user_position(MAKER, BTC_SYM).unwrap().open_volume, total);
    let expected_total_maker_fee = btc_maker(s1, price) + btc_maker(s2, price) + btc_maker(s3, price); // 10*30
    let expected_total_taker_fee = btc_taker(s1, price) + btc_taker(s2, price) + btc_taker(s3, price); // 20*30
    assert_eq!(api.user_account(MAKER, USD), deposit - expected_total_maker_fee);
    assert_eq!(api.fees(USD), expected_total_maker_fee + expected_total_taker_fee);
    assert_eq!(api.user_position(T1, BTC_SYM).unwrap().open_volume, s1);
    assert_eq!(api.user_position(T2, BTC_SYM).unwrap().open_volume, s2);
    assert_eq!(api.user_position(T3, BTC_SYM).unwrap().open_volume, s3);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 14. testFuturesTakerOneToManyFeeCalculation —— 1 taker 吃 3 个不同价 maker
// ================================================================================================

#[test]
fn futures_taker_one_to_many_fee_calculation() {
    const M1: i64 = 1;
    const M2: i64 = 2;
    const M3: i64 = 3;
    const TAKER: i64 = 4;
    let (s1, s2, s3) = (8i64, 9i64, 7i64);
    let total = s1 + s2 + s3;
    let (p1, p2, p3) = (49_000i64, 49_100i64, 49_200i64);
    let deposit = 100_000i64;

    let mut api = seed_btc(p1);
    seed_user(&mut api, M1, deposit, 1);
    seed_user(&mut api, M2, deposit, 2);
    seed_user(&mut api, M3, deposit, 3);
    seed_user(&mut api, TAKER, deposit, 4);

    // 三 maker ASK 不同价（SHORT maker）。
    assert_eq!(place(&mut api, 5001, M1, BTC_SYM, p1, s1, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 5002, M2, BTC_SYM, p2, s2, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 5003, M3, BTC_SYM, p3, s3, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    // 大 taker BID @p3 吃满三档（成交价 = 各 maker 挂价）。
    assert_eq!(place(&mut api, 5004, TAKER, BTC_SYM, p3, total, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    // 逐笔按各自成交价算 taker 费（固定费与价格无关）。
    let expected_total_taker_fee = btc_taker(s1, p1) + btc_taker(s2, p2) + btc_taker(s3, p3);
    let expected_total_maker_fee = btc_maker(s1, p1) + btc_maker(s2, p2) + btc_maker(s3, p3);
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, total);
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().direction, PositionDirection::Long);
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_total_taker_fee);
    assert_eq!(api.fees(USD), expected_total_maker_fee + expected_total_taker_fee);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 15. testFuturesMixedOrderTypesFeeCalculation —— GTC + IOC taker 混合
// ================================================================================================

#[test]
fn futures_mixed_order_types_fee_calculation() {
    const M1: i64 = 1;
    const M2: i64 = 2;
    const T1: i64 = 3;
    const T2: i64 = 4;
    let size = 6i64;
    let price = 51_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, M1, deposit, 1);
    seed_user(&mut api, M2, deposit, 2);
    seed_user(&mut api, T1, deposit, 3);
    seed_user(&mut api, T2, deposit, 4);

    // 两 maker BID（LONG）resting。
    assert_eq!(place(&mut api, 6001, M1, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 6002, M2, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    // GTC taker ASK 吃 M1；IOC taker ASK 吃 M2。
    assert_eq!(place(&mut api, 6003, T1, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 6004, T2, BTC_SYM, price, size, OrderAction::Ask, OrderType::Ioc, MarginMode::Cross, 1), CommandResultCode::Success);

    let maker_fee = btc_maker(size, price); // 60 each
    let taker_fee = btc_taker(size, price); // 120 each
    assert_eq!(api.user_account(M1, USD), deposit - maker_fee);
    assert_eq!(api.user_account(M2, USD), deposit - maker_fee);
    assert_eq!(api.user_account(T1, USD), deposit - taker_fee);
    assert_eq!(api.user_account(T2, USD), deposit - taker_fee);
    assert_eq!(api.fees(USD), 2 * (maker_fee + taker_fee));
    assert_eq!(api.user_position(M1, BTC_SYM).unwrap().direction, PositionDirection::Long);
    assert_eq!(api.user_position(T1, BTC_SYM).unwrap().direction, PositionDirection::Short);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 16. testFuturesFokBudgetTakerFeeCalculation —— FOK_BUDGET 全成
// ================================================================================================

#[test]
fn futures_fok_budget_taker_fee_calculation() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let size = 8i64;
    let price = 46_000i64;
    let budget = size * price;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    // maker GTC ASK（SHORT）+ taker FOK_BUDGET BID（price 字段=budget）全成。
    assert_eq!(place(&mut api, 7001, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 7002, TAKER, BTC_SYM, budget, size, OrderAction::Bid, OrderType::FokBudget, MarginMode::Cross, 1), CommandResultCode::Success);

    let expected_taker_fee = btc_taker(size, price); // 160
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, size);
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_taker_fee);
    assert_eq!(api.fees(USD), btc_maker(size, price) + expected_taker_fee);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 17. testFuturesIocBudgetFullFillTakerFeeCalculation —— IOC_BUDGET 全成
// ================================================================================================

#[test]
fn futures_ioc_budget_full_fill_taker_fee_calculation() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let size = 8i64;
    let price = 46_000i64;
    let budget = size * price;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    assert_eq!(place(&mut api, 7101, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 7102, TAKER, BTC_SYM, budget, size, OrderAction::Bid, OrderType::IocBudget, MarginMode::Cross, 1), CommandResultCode::Success);

    let expected_taker_fee = btc_taker(size, price); // 160
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, size, "IOC_BUDGET 全成 lastQty == size");
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_taker_fee);
    assert_eq!(api.fees(USD), btc_maker(size, price) + expected_taker_fee);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 18. testFuturesIocBudgetPartialFillTakerFeeCalculation —— IOC_BUDGET 部分成交，fee 只按已成交计
// ================================================================================================

#[test]
fn futures_ioc_budget_partial_fill_taker_fee_calculation() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let requested = 10i64;
    let filled = 6i64;
    let price = 48_000i64;
    let budget = filled * price; // 预算只够吃 6
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    // maker 挂 10；taker IOC_BUDGET 要 10 但预算只够 6，残 4 reject。
    assert_eq!(place(&mut api, 7201, MAKER, BTC_SYM, price, requested, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 7202, TAKER, BTC_SYM, budget, requested, OrderAction::Bid, OrderType::IocBudget, MarginMode::Cross, 1), CommandResultCode::Success);

    // fee 必须按已成交 6 计，不能按 cmd.size=10 全量计费。
    let expected_taker_fee = btc_taker(filled, price); // 120
    let wrong_taker_fee = btc_taker(requested, price); // 200
    assert_ne!(expected_taker_fee, wrong_taker_fee, "sanity：filled 与 requested 费不同");

    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, filled, "taker 只开出已成交 6");
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_taker_fee);
    // maker 端已成交 6（另 4 仍 resting）。
    assert_eq!(api.user_position(MAKER, BTC_SYM).unwrap().open_volume, filled);
    assert_eq!(api.fees(USD), btc_maker(filled, price) + expected_taker_fee);
    assert!(api.fees(USD) > 0, "应收到 USD fee");
    assert_conserved_usd(&api);
}

// ================================================================================================
// 19. testFuturesFeeCalculationParameters —— 非整 size/price 参数正确性
// ================================================================================================

#[test]
fn futures_fee_calculation_parameters() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let size = 13i64; // 非整
    let price = 47_123i64; // 非整
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    assert_eq!(place(&mut api, 8001, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 8002, TAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let maker_fee = btc_maker(size, price); // 130
    let taker_fee = btc_taker(size, price); // 260
    assert_eq!(api.user_account(MAKER, USD), deposit - maker_fee);
    assert_eq!(api.user_account(TAKER, USD), deposit - taker_fee);
    // 全局 fees 恰等于两笔之和。
    assert_eq!(api.fees(USD), maker_fee + taker_fee);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 20. testFuturesMakerOneToManyPartialFillFeeConsistency —— maker 大单被部分吃，全局 fee 一致
// ================================================================================================

#[test]
fn futures_maker_one_to_many_partial_fill_fee_consistency() {
    const MAKER: i64 = 1;
    const T1: i64 = 2;
    const T2: i64 = 3;
    const T3: i64 = 4;
    let maker_total = 50i64;
    let (s1, s2, s3) = (8i64, 12i64, 15i64); // 合计 35 < 50，部分成交
    let price = 48_500i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, T1, deposit, 2);
    seed_user(&mut api, T2, deposit, 3);
    seed_user(&mut api, T3, deposit, 4);
    let fees_before = api.fees(USD);

    assert_eq!(place(&mut api, 9001, MAKER, BTC_SYM, price, maker_total, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9002, T1, BTC_SYM, price, s1, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9003, T2, BTC_SYM, price, s2, OrderAction::Ask, OrderType::Ioc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9004, T3, BTC_SYM, price, s3, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let filled = s1 + s2 + s3; // 35
    // maker LONG 35（+ pending_buy 15 残单）。
    assert_eq!(api.user_position(MAKER, BTC_SYM).unwrap().open_volume, filled);
    let expected_maker_fees = btc_maker(s1, price) + btc_maker(s2, price) + btc_maker(s3, price);
    let expected_taker_fees = btc_taker(s1, price) + btc_taker(s2, price) + btc_taker(s3, price);
    let global_fees_collected = api.fees(USD) - fees_before;
    assert_eq!(global_fees_collected, expected_maker_fees + expected_taker_fees, "全局 fee == 逐笔 maker+taker 之和");
    assert!(global_fees_collected > 0);
    // fee 相对成交量合理（< 10%）。
    assert!(global_fees_collected < filled * price / 10);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 21. testFuturesTakerOneToManyPartialFillFeeConsistency —— taker 大单被部分成交，全局 fee 一致
// ================================================================================================

#[test]
fn futures_taker_one_to_many_partial_fill_fee_consistency() {
    const M1: i64 = 1;
    const M2: i64 = 2;
    const M3: i64 = 3;
    const TAKER: i64 = 4;
    let (s1, s2, s3) = (6i64, 8i64, 9i64); // 合计 23
    let taker_total = 30i64; // > 23，部分成交
    let (p1, p2, p3) = (45_000i64, 45_100i64, 45_200i64);
    let deposit = 100_000i64;

    let mut api = seed_btc(p1);
    seed_user(&mut api, M1, deposit, 1);
    seed_user(&mut api, M2, deposit, 2);
    seed_user(&mut api, M3, deposit, 3);
    seed_user(&mut api, TAKER, deposit, 4);
    let fees_before = api.fees(USD);

    assert_eq!(place(&mut api, 10001, M1, BTC_SYM, p1, s1, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 10002, M2, BTC_SYM, p2, s2, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 10003, M3, BTC_SYM, p3, s3, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    // 大 taker BID @p3 部分成交（吃满 23，残 7 resting）。
    assert_eq!(place(&mut api, 10004, TAKER, BTC_SYM, p3, taker_total, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let filled = s1 + s2 + s3; // 23
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, filled);
    let expected_maker_fees = btc_maker(s1, p1) + btc_maker(s2, p2) + btc_maker(s3, p3);
    let expected_taker_fees = btc_taker(s1, p1) + btc_taker(s2, p2) + btc_taker(s3, p3);
    let global_fees_collected = api.fees(USD) - fees_before;
    assert_eq!(global_fees_collected, expected_maker_fees + expected_taker_fees);
    assert!(global_fees_collected > 0);
    let total_volume = s1 * p1 + s2 * p2 + s3 * p3;
    assert!(global_fees_collected < total_volume / 10);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 22. testFuturesReverseOpeningFeeCalculation —— 反向单 size > openVolume 翻仓
// ================================================================================================

#[test]
fn futures_reverse_opening_fee_calculation() {
    const U1: i64 = 1;
    const U2: i64 = 2;
    const U3: i64 = 3;
    let initial_size = 10i64;
    let reverse_size = 12i64; // > openVolume → 翻仓
    let initial_price = 50_000i64;
    let reverse_price = 52_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(initial_price);
    seed_user(&mut api, U1, deposit, 1);
    seed_user(&mut api, U2, deposit, 2);
    seed_user(&mut api, U3, deposit, 3);

    // 开仓：U1 BID(LONG maker) + U2 ASK(SHORT taker)。
    assert_eq!(place(&mut api, 11001, U1, BTC_SYM, initial_price, initial_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 11002, U2, BTC_SYM, initial_price, initial_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    let fees_after_open = api.fees(USD);

    // mark 推到 reverse_price，聚焦翻仓 fee。
    assert_eq!(api.set_mark_price(BTC_SYM, reverse_price, 0), CommandResultCode::Success);

    // 翻仓：U1 ASK 12（reverse，平满 LONG10 再开 SHORT2，resting maker）+ U3 BID 12（taker，开 LONG12）。
    assert_eq!(place(&mut api, 11003, U1, BTC_SYM, reverse_price, reverse_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 11004, U3, BTC_SYM, reverse_price, reverse_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    // U1 翻空：SHORT，openVolume=2；已实现盈利 profit=(52000-50000)*10=20000 递延（新仓非空，未入 account）。
    let u1_pos = api.user_position(U1, BTC_SYM).expect("翻仓后 U1 仍持仓");
    assert_eq!(u1_pos.direction, PositionDirection::Short);
    assert_eq!(u1_pos.open_volume, reverse_size - initial_size);
    assert_eq!(u1_pos.profit, 20_000, "平腿已实现盈亏累进但未支付（新仓非空）");

    // U3 开 LONG 12。
    assert_eq!(api.user_position(U3, BTC_SYM).unwrap().direction, PositionDirection::Long);
    assert_eq!(api.user_position(U3, BTC_SYM).unwrap().open_volume, reverse_size);

    // taker(U3) fee 按全 size 12 计；maker(U1) 也按全 size 收（fixed maker=12*10）。
    let expected_taker_fee = btc_taker(reverse_size, reverse_price); // 240
    let expected_maker_fee = btc_maker(reverse_size, reverse_price); // 120
    assert_eq!(api.user_account(U3, USD), deposit - expected_taker_fee);
    // U1 account：开仓 maker fee(100) + 翻仓 maker fee(120)，profit 未入账。
    assert_eq!(api.user_account(U1, USD), deposit - btc_maker(initial_size, initial_price) - expected_maker_fee);
    let global_fees_collected = api.fees(USD) - fees_after_open;
    assert_eq!(global_fees_collected, expected_maker_fee + expected_taker_fee);
    assert!(global_fees_collected > 0);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 23. testHedgeModePositionOpeningFeeEvents —— HEDGE 开 LONG+SHORT，用户两腿都是 maker
// ================================================================================================

#[test]
fn hedge_mode_position_opening_fee_events() {
    const USER: i64 = 1;
    const CP1: i64 = 2;
    const CP2: i64 = 3;
    let long_size = 15i64;
    let short_size = 10i64;
    let price = 50_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, USER, deposit, 1);
    seed_user(&mut api, CP1, deposit, 2);
    seed_user(&mut api, CP2, deposit, 3);

    // 切 HEDGE。
    assert_eq!(api.adjust_position_mode(USER, true), CommandResultCode::Success);

    // 开 LONG：user BID（maker，resting）+ CP1 ASK（taker）。
    assert_eq!(place(&mut api, 12001, USER, BTC_SYM, price, long_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 12002, CP1, BTC_SYM, price, long_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    // 开 SHORT：user ASK（maker，resting）+ CP2 BID（taker）。
    assert_eq!(place(&mut api, 12003, USER, BTC_SYM, price, short_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 12004, CP2, BTC_SYM, price, short_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    // 双腿到位。
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Long).unwrap().open_volume, long_size);
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Short).unwrap().open_volume, short_size);

    // user 两腿都是 maker：付 makerFee(long)+makerFee(short)。
    let expected_long_maker = btc_maker(long_size, price); // 150
    let expected_short_maker = btc_maker(short_size, price); // 100
    assert_eq!(api.user_account(USER, USD), deposit - expected_long_maker - expected_short_maker);
    // 对手是 taker。
    assert_eq!(api.user_account(CP1, USD), deposit - btc_taker(long_size, price));
    assert_eq!(api.user_account(CP2, USD), deposit - btc_taker(short_size, price));
    // 全局 fees = 两腿 maker + 两腿 taker。
    assert_eq!(api.fees(USD), expected_long_maker + expected_short_maker + btc_taker(long_size, price) + btc_taker(short_size, price));
    assert_conserved_usd(&api);
}

// ================================================================================================
// 24. testHedgeModePartialClosingFeeEvents —— HEDGE 部分平多仓，close 挂单是 maker 全量收费
// ================================================================================================

#[test]
fn hedge_mode_partial_closing_fee_events() {
    const USER: i64 = 1;
    const CP1: i64 = 2;
    const CP2: i64 = 3;
    const CP3: i64 = 4;
    let long_size = 20i64;
    let short_size = 15i64;
    let partial_close = 8i64;
    let price = 48_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, USER, deposit, 1);
    seed_user(&mut api, CP1, deposit, 2);
    seed_user(&mut api, CP2, deposit, 3);
    seed_user(&mut api, CP3, deposit, 4);

    assert_eq!(api.adjust_position_mode(USER, true), CommandResultCode::Success);

    // 建双向持仓（user 两腿 maker）。
    assert_eq!(place(&mut api, 13001, USER, BTC_SYM, price, long_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 13002, CP1, BTC_SYM, price, long_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 13003, USER, BTC_SYM, price, short_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 13004, CP2, BTC_SYM, price, short_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Long).unwrap().open_volume, long_size);
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Short).unwrap().open_volume, short_size);
    let fees_after_open = api.fees(USD);
    let account_after_open = api.user_account(USER, USD);

    // 部分平多：ApiClosePosition ASK 8（user maker，resting）+ CP3 BID 8（taker）。
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 13005,
            uid: USER,
            symbol: BTC_SYM,
            action: OrderAction::Ask,
            price,
            size: partial_close,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_eq!(place(&mut api, 13006, CP3, BTC_SYM, price, partial_close, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    // 平仓后：LONG 12、SHORT 15（双腿都在）。
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Long).unwrap().open_volume, long_size - partial_close);
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Short).unwrap().open_volume, short_size);

    // close 挂单按 maker 率全量收；mark==price → 递延 pnl=0，account 只减 close maker fee。
    let expected_close_maker = btc_maker(partial_close, price); // 80
    let expected_close_taker = btc_taker(partial_close, price); // 160
    assert_eq!(api.user_account(USER, USD), account_after_open - expected_close_maker);
    assert_eq!(api.user_account(CP3, USD), deposit - expected_close_taker);
    assert_eq!(api.fees(USD) - fees_after_open, expected_close_maker + expected_close_taker);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 25. testHedgeModePositionReversalFeeEvents —— 平满多仓 + 反向开空，close/open 各按 maker 收
// ================================================================================================

#[test]
fn hedge_mode_position_reversal_fee_events() {
    const USER: i64 = 1;
    const CP1: i64 = 2;
    const CP2: i64 = 3;
    let initial_long = 12i64;
    let reversal = 18i64;
    let new_short = reversal - initial_long; // 6
    let price = 51_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, USER, deposit, 1);
    seed_user(&mut api, CP1, deposit, 2);
    seed_user(&mut api, CP2, deposit, 3);

    assert_eq!(api.adjust_position_mode(USER, true), CommandResultCode::Success);

    // 建 LONG 12（user maker BID + CP1 taker ASK）。
    assert_eq!(place(&mut api, 14001, USER, BTC_SYM, price, initial_long, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 14002, CP1, BTC_SYM, price, initial_long, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Long).unwrap().open_volume, initial_long);
    let fees_after_open = api.fees(USD);

    // 平满 LONG（ApiClosePosition ASK 12，resting maker）。
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 14003,
            uid: USER,
            symbol: BTC_SYM,
            action: OrderAction::Ask,
            price,
            size: initial_long,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    // 反向开新 SHORT 6（user ASK，resting maker）。
    assert_eq!(place(&mut api, 14004, USER, BTC_SYM, price, new_short, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    // CP2 BID 18 一次吃满 close(12)+short(6)。
    assert_eq!(place(&mut api, 14005, CP2, BTC_SYM, price, reversal, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    // 反向后：LONG 平掉（拆除）、SHORT openVolume=6。
    assert!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Long).is_none(), "LONG 全平后应拆除");
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Short).unwrap().open_volume, new_short);

    // user：close maker(12) + short-open maker(6)；mark==price → pnl=0。
    let expected_close_maker = btc_maker(initial_long, price); // 120
    let expected_short_maker = btc_maker(new_short, price); // 60
    assert_eq!(api.user_account(USER, USD), deposit - btc_maker(initial_long, price) - expected_close_maker - expected_short_maker);
    // CP2 taker 全量 18。
    assert_eq!(api.user_account(CP2, USD), deposit - btc_taker(reversal, price));
    let global_delta = api.fees(USD) - fees_after_open;
    assert_eq!(global_delta, expected_close_maker + expected_short_maker + btc_taker(reversal, price));
    assert!(global_delta > 0);
    assert_conserved_usd(&api);
}

// ================================================================================================
// 26. testHedgeModeMixedOrderTypesFeeEvents —— GTC 双腿 + IOC taker（无对手盘 → 不成交）
// ================================================================================================

#[test]
fn hedge_mode_mixed_order_types_fee_events() {
    const USER: i64 = 1;
    const CP1: i64 = 2;
    const CP2: i64 = 3;
    const CP3: i64 = 4;
    let long_size = 10i64;
    let short_size = 8i64;
    let ioc_size = 5i64;
    let price = 49_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, USER, deposit, 1);
    seed_user(&mut api, CP1, deposit, 2);
    seed_user(&mut api, CP2, deposit, 3);
    seed_user(&mut api, CP3, deposit, 4);

    assert_eq!(api.adjust_position_mode(USER, true), CommandResultCode::Success);

    // GTC 开 LONG（user maker BID + CP1 taker ASK）。
    assert_eq!(place(&mut api, 15001, USER, BTC_SYM, price, long_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 15002, CP1, BTC_SYM, price, long_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    // GTC 开 SHORT（user maker ASK + CP2 taker BID）。
    assert_eq!(place(&mut api, 15003, USER, BTC_SYM, price, short_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 15004, CP2, BTC_SYM, price, short_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    let fees_after_gtc = api.fees(USD);

    // IOC BID：此刻盘口无 resting ASK（CP3 的 ASK 在其后才下）→ IOC 不成交（faithful：Java 同序，断言宽松）。
    assert_eq!(place(&mut api, 15005, USER, BTC_SYM, price, ioc_size, OrderAction::Bid, OrderType::Ioc, MarginMode::Cross, 0), CommandResultCode::Success);
    // CP3 ASK 挂在 IOC 之后 → 无 resting BID 可吃，自身 resting（不成交）。
    assert_eq!(place(&mut api, 15006, CP3, BTC_SYM, price, ioc_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    // 双腿 GTC 成交定格；IOC 未成交不改双腿。
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Long).unwrap().open_volume, long_size);
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Short).unwrap().open_volume, short_size);
    // IOC 无成交 → user 只付两腿 maker fee，fees 池自 GTC 后无增量。
    assert_eq!(api.fees(USD), fees_after_gtc);
    assert_eq!(api.user_account(USER, USD), deposit - btc_maker(long_size, price) - btc_maker(short_size, price));
    assert_eq!(api.fees(USD), btc_maker(long_size, price) + btc_maker(short_size, price) + btc_taker(long_size, price) + btc_taker(short_size, price));
    assert_conserved_usd(&api);
}

// ================================================================================================
// 27. testHedgeModeFeeConsistencyWithGlobalBalance —— HEDGE 开双腿，全局 fees 与逐笔一致 + 守恒
// ================================================================================================

#[test]
fn hedge_mode_fee_consistency_with_global_balance() {
    const USER: i64 = 1;
    const CP1: i64 = 2;
    const CP2: i64 = 3;
    let long_size = 25i64;
    let short_size = 20i64;
    let price = 47_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, USER, deposit, 1);
    seed_user(&mut api, CP1, deposit, 2);
    seed_user(&mut api, CP2, deposit, 3);

    let initial_fees = api.fees(USD);
    assert_eq!(api.adjust_position_mode(USER, true), CommandResultCode::Success);

    // 开 LONG（user maker BID + CP1 taker ASK）+ 开 SHORT（user maker ASK + CP2 taker BID）。
    assert_eq!(place(&mut api, 16001, USER, BTC_SYM, price, long_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 16002, CP1, BTC_SYM, price, long_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 16003, USER, BTC_SYM, price, short_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 16004, CP2, BTC_SYM, price, short_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    let global_fees_collected = api.fees(USD) - initial_fees;
    let expected = btc_maker(long_size, price) + btc_taker(long_size, price)
        + btc_maker(short_size, price) + btc_taker(short_size, price);
    assert_eq!(global_fees_collected, expected, "全局 fees == 四笔 maker+taker 之和");
    assert!(global_fees_collected > 0);
    // 全局守恒（对应 Java finalBalance.isGlobalBalancesAllZero()）。
    assert!(api.total_balance().is_global_zero(), "HEDGE 双向开仓后全局守恒");
    assert_conserved_usd(&api);
}

// ================================================================================================
// 28. testFuturesHedgeFullLifecycleWithDepositWithdraw —— CROSS + HEDGE 全生命周期 × 4 种 taker 类型
// ================================================================================================

fn run_hedge_full_lifecycle(taker_type: OrderType) {
    const MAKER: i64 = 9401;
    const TAKER: i64 = 9402;
    let size = 4i64;
    let price = 50_000i64;
    // HEDGE 同时锁多空两腿保证金，deposit 留足余量。
    let deposit = 100_000_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);
    assert_conserved_usd(&api);

    // taker 切 HEDGE。
    assert_eq!(api.adjust_position_mode(TAKER, true), CommandResultCode::Success);

    let taker_price = match taker_type {
        OrderType::FokBudget | OrderType::IocBudget => size * price,
        _ => price,
    };
    // IOC_BUDGET ASK 引擎不支持 → 开空腿退化 GTC（同 Java）。
    let ask_taker_type = if taker_type == OrderType::IocBudget { OrderType::Gtc } else { taker_type };
    let ask_taker_price = match ask_taker_type {
        OrderType::FokBudget | OrderType::IocBudget => size * price,
        _ => price,
    };

    // 开多：maker GTC ASK + taker <type> BID。
    assert_eq!(place(&mut api, 9501, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9502, TAKER, BTC_SYM, taker_price, size, OrderAction::Bid, taker_type, MarginMode::Cross, 0), CommandResultCode::Success);
    // 开空：maker GTC BID + taker <askType> ASK（HEDGE 下 ASK 不抵消多仓，建新空仓）。
    assert_eq!(place(&mut api, 9503, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9504, TAKER, BTC_SYM, ask_taker_price, size, OrderAction::Ask, ask_taker_type, MarginMode::Cross, 0), CommandResultCode::Success);

    assert_eq!(hedge_leg(&api, TAKER, BTC_SYM, PositionDirection::Long).unwrap().open_volume, size, "[{taker_type:?}] taker LONG openVolume");
    assert_eq!(hedge_leg(&api, TAKER, BTC_SYM, PositionDirection::Short).unwrap().open_volume, size, "[{taker_type:?}] taker SHORT openVolume");
    assert_conserved_usd(&api);

    // 平多：taker ApiClosePosition ASK + maker GTC BID。
    assert_eq!(api.close_position(ClosePositionRequest { order_id: 9505, uid: TAKER, symbol: BTC_SYM, action: OrderAction::Ask, price, size, order_type: OrderType::Gtc }), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9506, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    // 平空：taker ApiClosePosition BID + maker GTC ASK。
    assert_eq!(api.close_position(ClosePositionRequest { order_id: 9507, uid: TAKER, symbol: BTC_SYM, action: OrderAction::Bid, price, size, order_type: OrderType::Gtc }), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9508, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    assert_eq!(hedge_open_volume_sum(&api, TAKER, BTC_SYM), 0, "[{taker_type:?}] 平仓后 openVolume 总和为 0");
    assert_conserved_usd(&api);

    // 提现全部余额。
    let maker_bal = api.user_account(MAKER, USD);
    let taker_bal = api.user_account(TAKER, USD);
    if maker_bal != 0 { assert_eq!(api.balance_adjustment(MAKER, USD, -maker_bal, 3), CommandResultCode::Success); }
    if taker_bal != 0 { assert_eq!(api.balance_adjustment(TAKER, USD, -taker_bal, 4), CommandResultCode::Success); }

    // 终态：账户清零，adjustments + fees == 0，全局守恒。
    assert_eq!(api.user_account(MAKER, USD), 0, "[{taker_type:?}] maker account");
    assert_eq!(api.user_account(TAKER, USD), 0, "[{taker_type:?}] taker account");
    assert_eq!(api.adjustments(USD) + api.fees(USD), 0, "[{taker_type:?}] adjustments + fees == 0");
    assert_conserved_usd(&api);
}

#[test]
fn futures_hedge_full_lifecycle_with_deposit_withdraw() {
    run_hedge_full_lifecycle(OrderType::Gtc);
    run_hedge_full_lifecycle(OrderType::Ioc);
    run_hedge_full_lifecycle(OrderType::FokBudget);
    run_hedge_full_lifecycle(OrderType::IocBudget);
}

// ================================================================================================
// 29. testFuturesIsolatedHedgeFullLifecycleWithDepositWithdraw —— ISOLATED + HEDGE 全生命周期 × 4
//     额外校验：多空两腿各自独立锁逐仓保证金（openInitMarginSum > 0），leverage 正确写入两腿。
// ================================================================================================

fn run_isolated_hedge_full_lifecycle(taker_type: OrderType) {
    const MAKER: i64 = 9601;
    const TAKER: i64 = 9602;
    let size = 4i64;
    let price = 50_000i64;
    let leverage = 10i32;
    let deposit = 100_000_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);
    assert_conserved_usd(&api);

    assert_eq!(api.adjust_position_mode(TAKER, true), CommandResultCode::Success);

    let taker_price = match taker_type {
        OrderType::FokBudget | OrderType::IocBudget => size * price,
        _ => price,
    };
    let ask_taker_type = if taker_type == OrderType::IocBudget { OrderType::Gtc } else { taker_type };
    let ask_taker_price = match ask_taker_type {
        OrderType::FokBudget | OrderType::IocBudget => size * price,
        _ => price,
    };

    // 开多（ISOLATED leverage=10）。
    assert_eq!(place(&mut api, 9701, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9702, TAKER, BTC_SYM, taker_price, size, OrderAction::Bid, taker_type, MarginMode::Isolated, leverage), CommandResultCode::Success);
    // 开空（ISOLATED leverage=10）。
    assert_eq!(place(&mut api, 9703, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9704, TAKER, BTC_SYM, ask_taker_price, size, OrderAction::Ask, ask_taker_type, MarginMode::Isolated, leverage), CommandResultCode::Success);

    // 两腿独立锁逐仓保证金。
    let long_leg = hedge_leg(&api, TAKER, BTC_SYM, PositionDirection::Long).expect("LONG leg");
    assert_eq!(long_leg.open_volume, size, "[{taker_type:?}] LONG openVolume");
    assert_eq!(long_leg.margin_mode, MarginMode::Isolated, "[{taker_type:?}] LONG marginMode");
    assert!(long_leg.open_init_margin_sum > 0, "[{taker_type:?}] LONG openInitMarginSum > 0");
    let short_leg = hedge_leg(&api, TAKER, BTC_SYM, PositionDirection::Short).expect("SHORT leg");
    assert_eq!(short_leg.open_volume, size, "[{taker_type:?}] SHORT openVolume");
    assert_eq!(short_leg.margin_mode, MarginMode::Isolated, "[{taker_type:?}] SHORT marginMode");
    assert!(short_leg.open_init_margin_sum > 0, "[{taker_type:?}] SHORT openInitMarginSum > 0");
    assert_conserved_usd(&api);

    // 平多 + 平空。
    assert_eq!(api.close_position(ClosePositionRequest { order_id: 9705, uid: TAKER, symbol: BTC_SYM, action: OrderAction::Ask, price, size, order_type: OrderType::Gtc }), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9706, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_eq!(api.close_position(ClosePositionRequest { order_id: 9707, uid: TAKER, symbol: BTC_SYM, action: OrderAction::Bid, price, size, order_type: OrderType::Gtc }), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9708, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);

    assert_eq!(hedge_open_volume_sum(&api, TAKER, BTC_SYM), 0, "[{taker_type:?}] 平仓后 openVolume 总和为 0");
    assert_conserved_usd(&api);

    // 提现全部余额。
    let maker_bal = api.user_account(MAKER, USD);
    let taker_bal = api.user_account(TAKER, USD);
    if maker_bal != 0 { assert_eq!(api.balance_adjustment(MAKER, USD, -maker_bal, 3), CommandResultCode::Success); }
    if taker_bal != 0 { assert_eq!(api.balance_adjustment(TAKER, USD, -taker_bal, 4), CommandResultCode::Success); }

    assert_eq!(api.user_account(MAKER, USD), 0, "[{taker_type:?}] maker account");
    assert_eq!(api.user_account(TAKER, USD), 0, "[{taker_type:?}] taker account");
    assert_eq!(api.adjustments(USD) + api.fees(USD), 0, "[{taker_type:?}] adjustments + fees == 0");
    assert_conserved_usd(&api);
}

#[test]
fn futures_isolated_hedge_full_lifecycle_with_deposit_withdraw() {
    run_isolated_hedge_full_lifecycle(OrderType::Gtc);
    run_isolated_hedge_full_lifecycle(OrderType::Ioc);
    run_isolated_hedge_full_lifecycle(OrderType::FokBudget);
    run_isolated_hedge_full_lifecycle(OrderType::IocBudget);
}
}
