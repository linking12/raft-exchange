//! P4 Task 8：期货 e2e 场景 + 守恒 proptest（任务简报 §"What to produce" / 参考文档 §7）。核心断言（[`assert_futures_conservation`]）：Σ_users accounts+adjustments+fees + Σ_open_positions(estimate_pnl(mark)+extra_margin) == 0——比简报的 naive 公式多出的两项（estimate_pnl/extra_margin）对应两类"资金已离开 accounts、尚未转回"的时间差场景（isolated MARGIN_ADJUSTMENT 递延退款、平仓对手方与开仓对手方不同导致的未实现浮亏递延），均非生产代码 bug，详见 [`characterization_naive_formula_misses_fresh_counterparty_unrealized_pnl`] 与场景 D。
use proptest::prelude::*;

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::common::position_direction::PositionDirection;
use crate::core::common::symbol_type::SymbolType;

use super::exchange_api::{
    ClosePositionRequest, ExchangeApi, MarginAdjustmentRequest, PlaceFuturesOrderRequest,
};

const BASE: i32 = 1;
const QUOTE: i32 = 2;
const FUT_SYMBOL: i32 = 300;

// ================================================================================================
// 守恒 / 非负断言 helper
// ================================================================================================

/// 全局守恒断言，见文件头文档——本文件唯一使用的守恒 helper；本文件 scale_k 恒为 1，故 estimate_pnl/extra_margin 可直接求和，无需 `size_price_to_currency_scale` 换算（生产代码非 1 缩放下需换算）。
fn assert_futures_conservation(api: &ExchangeApi) {
    for &cur in api.ssp().currencies.keys() {
        let mut total: i64 = api.ups().users.values().map(|p| p.account(cur)).sum();
        total += api.adjustments(cur);
        total += api.fees(cur);
        for p in api.ups().users.values() {
            for pos in p.positions.values() {
                if pos.currency != cur {
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
        assert_eq!(total, 0, "期货全局守恒被打破：currency={cur} total={total}");
    }
}

/// `accounts` 恒非负——期货 NSF 校验（`can_place_margin_order`/`margin_adjustment`）应保证此点。
fn assert_accounts_non_negative(api: &ExchangeApi) {
    for p in api.ups().users.values() {
        for (&cur, &bal) in &p.accounts {
            assert!(bal >= 0, "用户 {} 的 accounts[{cur}] 为负: {bal}", p.uid);
        }
    }
}

/// 仓位记录内部不变式：`open_volume`/`open_init_margin_sum` 恒非负（双保险，触发即代表 Task 1-7 仓位原语有真实 bug）。
fn assert_positions_non_negative(api: &ExchangeApi) {
    for p in api.ups().users.values() {
        for pos in p.positions.values() {
            assert!(
                pos.open_volume >= 0,
                "用户 {} symbol {} 的 open_volume 为负: {}",
                p.uid,
                pos.symbol,
                pos.open_volume
            );
            assert!(
                pos.open_init_margin_sum >= 0,
                "用户 {} symbol {} 的 open_init_margin_sum 为负: {}",
                p.uid,
                pos.symbol,
                pos.open_init_margin_sum
            );
        }
    }
}

/// 复合 helper：每条命令后调用一次——守恒 + 两类非负 + （隐含）无 panic。
fn assert_futures_invariants(api: &ExchangeApi) {
    assert_futures_conservation(api);
    assert_accounts_non_negative(api);
    assert_positions_non_negative(api);
}

// ================================================================================================
// spec / api 构造 helper
// ================================================================================================

/// 固定费期货 spec：scale_k=1，init/maintenance_margin/max_leverage 留空，任意非负杠杆放行（简化场景/proptest 合式性）。
fn futures_spec_fixed_fee(taker_fee: i64, maker_fee: i64) -> CoreSymbolSpecification {
    CoreSymbolSpecification {
        symbol_id: FUT_SYMBOL,
        symbol_type: SymbolType::FuturesContractPerpetual,
        base_currency: BASE,
        quote_currency: QUOTE,
        base_scale_k: 1,
        quote_scale_k: 1,
        taker_fee,
        maker_fee,
        fee_scale_k: 0,
        ..Default::default()
    }
}

/// 比例费期货 spec（同上，fee_scale_k 非零）。期货比例费按 Task 4 结论精确守恒（不同于 spot 的 ceiling 超可加性缺陷），两费率均可任意非零，无需像 e2e_tests.rs 那样钉 maker_fee=0。
fn futures_spec_proportional_fee(taker_fee: i64, maker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
    CoreSymbolSpecification {
        symbol_id: FUT_SYMBOL,
        symbol_type: SymbolType::FuturesContractPerpetual,
        base_currency: BASE,
        quote_currency: QUOTE,
        base_scale_k: 1,
        quote_scale_k: 1,
        taker_fee,
        maker_fee,
        fee_scale_k,
        ..Default::default()
    }
}

fn new_seeded_futures_api(spec: CoreSymbolSpecification) -> ExchangeApi {
    let mut api = ExchangeApi::new();
    api.add_currency(BASE, 1);
    api.add_currency(QUOTE, 1);
    assert_eq!(api.add_futures_symbol(spec), CommandResultCode::Success);
    api
}

// ================================================================================================
// 场景 A：多用户开多/开空对敲 → mark 变动后互相平仓 → PnL 结算 → 记录拆除；全程唯一对手方，额外满足 naive 公式，顺带验证。
// ================================================================================================

fn naive_conservation(api: &ExchangeApi, currency: i32) -> i64 {
    let user_sum: i64 = api.ups().users.values().map(|p| p.account(currency)).sum();
    user_sum + api.adjustments(currency) + api.fees(currency)
}

#[test]
fn scenario_a_long_short_cross_then_mutual_close_settles_pnl() {
    const LONG_USER: i64 = 1;
    const SHORT_USER: i64 = 2;

    let mut api = new_seeded_futures_api(futures_spec_fixed_fee(10, 5));
    assert_eq!(api.add_user(LONG_USER), CommandResultCode::Success);
    assert_eq!(api.add_user(SHORT_USER), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(api.balance_adjustment(LONG_USER, QUOTE, 100_000, 1), CommandResultCode::Success);
    assert_futures_invariants(&api);
    assert_eq!(api.balance_adjustment(SHORT_USER, QUOTE, 100_000, 2), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);
    assert_futures_invariants(&api);

    // SHORT_USER 先挂 ASK@100 size6（maker，开空）。
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: SHORT_USER, symbol: FUT_SYMBOL, price: 100, size: 6,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    // LONG_USER 吃单 BID@100 size6（taker，开多），与 SHORT_USER 完全撮合。
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: LONG_USER, symbol: FUT_SYMBOL, price: 100, size: 6,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    let long_pos = api.user_position(LONG_USER, FUT_SYMBOL).expect("多头开仓后必有仓位");
    assert_eq!(long_pos.direction, PositionDirection::Long);
    assert_eq!(long_pos.open_volume, 6);
    let short_pos = api.user_position(SHORT_USER, FUT_SYMBOL).expect("空头开仓后必有仓位");
    assert_eq!(short_pos.direction, PositionDirection::Short);
    assert_eq!(short_pos.open_volume, 6);

    // mark 价推高到 150，双方互相平仓。
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 150), CommandResultCode::Success);
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 3, uid: SHORT_USER, symbol: FUT_SYMBOL, action: OrderAction::Bid,
            price: 150, size: 6, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 4, uid: LONG_USER, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 150, size: 6, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0, "同一对手方全程对敲，naive 公式也应精确成立");

    assert!(api.user_position(LONG_USER, FUT_SYMBOL).is_none(), "多头全平后仓位记录应拆除");
    assert!(api.user_position(SHORT_USER, FUT_SYMBOL).is_none(), "空头全平后仓位记录应拆除");
    assert!(api.user_account(LONG_USER, QUOTE) > 100_000 - 100, "多头应实现正 PnL（mark 上涨）");
    assert!(api.user_account(SHORT_USER, QUOTE) < 100_000, "空头应实现负 PnL");
}

// ================================================================================================
// 场景 B：同向加仓 → 部分减仓 → 完全平仓，唯一对手方，用手算结果交叉验证 `assert_futures_conservation` 本身没算错。
// ================================================================================================

#[test]
fn scenario_b_increase_then_partial_reduce_then_full_close() {
    const TRADER: i64 = 1;
    const COUNTER: i64 = 2;

    let mut api = new_seeded_futures_api(futures_spec_fixed_fee(10, 5));
    assert_eq!(api.add_user(TRADER), CommandResultCode::Success);
    assert_eq!(api.add_user(COUNTER), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(TRADER, QUOTE, 100_000, 1), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(COUNTER, QUOTE, 100_000, 2), CommandResultCode::Success);
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);
    assert_futures_invariants(&api);

    // 开仓：COUNTER ASK@100 size5（maker，开空），TRADER BID@100 size5（taker，开多）。
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: COUNTER, symbol: FUT_SYMBOL, price: 100, size: 5,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: TRADER, symbol: FUT_SYMBOL, price: 100, size: 5,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    // 加仓（同向）：@110 size5，mark 仍是 100（保证金按 mark 算）。
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 3, uid: COUNTER, symbol: FUT_SYMBOL, price: 110, size: 5,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 4, uid: TRADER, symbol: FUT_SYMBOL, price: 110, size: 5,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    let trader_pos = api.user_position(TRADER, FUT_SYMBOL).expect("加仓后仍持仓");
    assert_eq!(trader_pos.open_volume, 10, "5+5=10，同向加仓累加");
    assert_eq!(trader_pos.open_price_sum, 1_050, "500(5*100)+550(5*110)");

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 130), CommandResultCode::Success);
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    // 部分减仓 4/10：COUNTER 先挂 BID@130 size4（maker，减空），TRADER 吃单 ASK@130 size4（taker，减多）。
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 5, uid: COUNTER, symbol: FUT_SYMBOL, action: OrderAction::Bid,
            price: 130, size: 4, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 6, uid: TRADER, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 130, size: 4, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    let trader_pos = api.user_position(TRADER, FUT_SYMBOL).expect("部分减仓后仍非空");
    assert_eq!(trader_pos.open_volume, 6, "10-4=6");
    assert_eq!(trader_pos.profit, 0, "部分平不实现盈亏（递延进剩余成本基）");

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 140), CommandResultCode::Success);
    assert_futures_invariants(&api);

    // 完全平仓剩余 6：mirror pair 收尾。
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 7, uid: COUNTER, symbol: FUT_SYMBOL, action: OrderAction::Bid,
            price: 140, size: 6, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 8, uid: TRADER, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 140, size: 6, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0, "全程对敲，naive 公式全程精确成立");

    assert!(api.user_position(TRADER, FUT_SYMBOL).is_none());
    assert!(api.user_position(COUNTER, FUT_SYMBOL).is_none());

    // 手算：TRADER taker fee=200，COUNTER maker fee=100，最终 pnl_raw=310（TRADER +310，COUNTER -310）。
    assert_eq!(api.user_account(TRADER, QUOTE), 100_000 - 200 + 310);
    assert_eq!(api.user_account(COUNTER, QUOTE), 100_000 - 100 - 310);
}

// ================================================================================================
// 场景 C：FLIP——大额反向单先平满仓再反手开新仓，profit 递延到新仓非空为止；唯一对手方对称翻仓，额外满足 naive 公式。
// ================================================================================================

#[test]
fn scenario_c_flip_via_oversized_opposite_order_defers_then_pays_profit() {
    const FLIPPER: i64 = 1;
    const COUNTER: i64 = 2;

    let mut api = new_seeded_futures_api(futures_spec_fixed_fee(10, 5));
    assert_eq!(api.add_user(FLIPPER), CommandResultCode::Success);
    assert_eq!(api.add_user(COUNTER), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(FLIPPER, QUOTE, 100_000, 1), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(COUNTER, QUOTE, 100_000, 2), CommandResultCode::Success);
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);
    assert_futures_invariants(&api);

    // 开仓：COUNTER ASK@100 size10（maker，开空），FLIPPER BID@100 size10（taker，开多）。
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: COUNTER, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: FLIPPER, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 120), CommandResultCode::Success);
    assert_futures_invariants(&api);

    // 翻仓：COUNTER 先挂 BID@120 size15（maker，超过其现有 Short10，平满 10 再反手开多 5）；
    // FLIPPER 吃单 ASK@120 size15（taker，非 reduce-only，超过其现有 Long10，平满 10 再反手开空 5）。
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 3, uid: COUNTER, symbol: FUT_SYMBOL, price: 120, size: 15,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 4, uid: FLIPPER, symbol: FUT_SYMBOL, price: 120, size: 15,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0, "双方在同一笔成交里对称翻仓，naive 公式仍精确成立");

    let flipper_pos = api.user_position(FLIPPER, FUT_SYMBOL).expect("翻仓后新方向仓位非空");
    assert_eq!(flipper_pos.direction, PositionDirection::Short, "多头翻空");
    assert_eq!(flipper_pos.open_volume, 5);
    assert_eq!(flipper_pos.profit, 200, "平仓腿已实现盈亏累进但未支付（新仓非空）");

    let counter_pos = api.user_position(COUNTER, FUT_SYMBOL).expect("翻仓后新方向仓位非空");
    assert_eq!(counter_pos.direction, PositionDirection::Long, "空头翻多");
    assert_eq!(counter_pos.open_volume, 5);
    assert_eq!(counter_pos.profit, -200, "对侧对称亏损，同样递延未支付");

    // 最终收尾：mark 再变动，双方对敲平掉翻仓后的剩余 5，profit 一次性结清支付。
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 90), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 5, uid: COUNTER, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 90, size: 5, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 6, uid: FLIPPER, symbol: FUT_SYMBOL, action: OrderAction::Bid,
            price: 90, size: 5, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert!(api.user_position(FLIPPER, FUT_SYMBOL).is_none(), "最终全平，记录拆除");
    assert!(api.user_position(COUNTER, FUT_SYMBOL).is_none());
    // 这里只做方向性 sanity check，精确手算已在场景 B 交叉验证过 `assert_futures_conservation`。
    assert!(api.user_account(FLIPPER, QUOTE) > 100_000, "净盈利为正（先赚 200 后又赚 150，扣费仍为正）");
    assert!(api.user_account(COUNTER, QUOTE) < 100_000, "净亏损");
}

// ================================================================================================
// 场景 D：MARGIN_ADJUSTMENT 追加保证金 → 平仓退还（ISOLATED 专属）；追加那一步 naive 公式不成立（文件头文档第一类偏差），显式断言为预期行为，完整公式全程精确成立。
// ================================================================================================

#[test]
fn scenario_d_margin_adjustment_add_then_close_refunds_extra_margin() {
    const MARGIN_USER: i64 = 1;
    const COUNTER: i64 = 2;

    let mut api = new_seeded_futures_api(futures_spec_fixed_fee(10, 5));
    assert_eq!(api.add_user(MARGIN_USER), CommandResultCode::Success);
    assert_eq!(api.add_user(COUNTER), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(MARGIN_USER, QUOTE, 100_000, 1), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(COUNTER, QUOTE, 100_000, 2), CommandResultCode::Success);
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: COUNTER, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: MARGIN_USER, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0, "开仓阶段尚未追加保证金，naive 公式仍成立");

    let acct_before_margin = api.user_account(MARGIN_USER, QUOTE);

    // 追加 500 isolated 保证金：action 字段在 ONEWAY 下被忽略，故随意传 Bid。
    assert_eq!(
        api.margin_adjustment(MarginAdjustmentRequest {
            uid: MARGIN_USER, symbol: FUT_SYMBOL, action: OrderAction::Bid, amount: 500,
            margin_mode: MarginMode::Isolated, order_id: 100,
        }),
        CommandResultCode::Success
    );
    // 完整公式全程精确成立：extra_margin 被显式计入。
    assert_futures_invariants(&api);
    // naive 公式在这一步**不**成立——文件头文档解释的第一类偏差，显式验证（不是遗漏）。
    assert_eq!(
        naive_conservation(&api, QUOTE),
        -500,
        "MARGIN_ADJUSTMENT 后 accounts 真实减少 500，尚未计入 extra_margin 前 naive 公式应偏差 -500"
    );
    assert_eq!(api.user_account(MARGIN_USER, QUOTE), acct_before_margin - 500, "accounts 真实扣款 500");
    let pos = api.user_position(MARGIN_USER, FUT_SYMBOL).expect("追加保证金前必须已有仓位");
    assert_eq!(pos.extra_margin, 500, "500 转入仓内 extra_margin（scale_k=1 恒等换算）");

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success); // 价格不变，聚焦保证金退款
    assert_futures_invariants(&api);

    // 平仓：mirror pair 全平，extra_margin 应整额退回 accounts。
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 3, uid: COUNTER, symbol: FUT_SYMBOL, action: OrderAction::Bid,
            price: 100, size: 10, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 4, uid: MARGIN_USER, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 100, size: 10, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    // 价格未变（100 -> 100），pnl=0，全部资金已回流 accounts：naive 公式在终态重新精确成立。
    assert_eq!(naive_conservation(&api, QUOTE), 0, "平仓后 extra_margin 已全额退款，naive 公式重新成立");

    assert!(api.user_position(MARGIN_USER, FUT_SYMBOL).is_none());
    assert!(api.user_position(COUNTER, FUT_SYMBOL).is_none());
    // 价格未变（pnl=0），accounts 最终只反映 fee 支出；extra_margin 整额 500 已退回。
    assert_eq!(
        api.user_account(MARGIN_USER, QUOTE),
        100_000 - 100 /* 开仓 taker fee */ - 500 /* margin add */ - 100 /* 平仓 taker fee */ + 500, /* 退款 */
    );
}

// ================================================================================================
// 场景 E：多用户 maker/taker（一个 maker 挂单被两个 taker 分两次吃完）+ 比例费 symbol，全程同价位（pnl恒0）naive 公式依旧精确成立；验证 Task 4 结论：期货比例费精确配对，不像 spot 那样因 ceiling 超可加性漂移。
// ================================================================================================

#[test]
fn scenario_e_multi_user_maker_taker_proportional_fee_conserves_exactly() {
    const MAKER: i64 = 1;
    const TAKER1: i64 = 2;
    const TAKER2: i64 = 3;

    // taker_fee=1000/1_000_000=0.1%，maker_fee=500/1_000_000=0.05%。
    let mut api = new_seeded_futures_api(futures_spec_proportional_fee(1_000, 500, 1_000_000));
    for uid in [MAKER, TAKER1, TAKER2] {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE, 10_000_000, uid), CommandResultCode::Success);
    }
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 1_000), CommandResultCode::Success);
    assert_futures_invariants(&api);

    // MAKER 挂 ASK@1000 size20（resting，尚无对手盘，纯 pending，不动 accounts）。
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: MAKER, symbol: FUT_SYMBOL, price: 1_000, size: 20,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    // TAKER1 吃 8。
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: TAKER1, symbol: FUT_SYMBOL, price: 1_000, size: 8,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    // TAKER2 吃剩下的 12。
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 3, uid: TAKER2, symbol: FUT_SYMBOL, price: 1_000, size: 12,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(api.user_position(MAKER, FUT_SYMBOL).unwrap().open_volume, 20);
    assert_eq!(api.user_position(TAKER1, FUT_SYMBOL).unwrap().open_volume, 8);
    assert_eq!(api.user_position(TAKER2, FUT_SYMBOL).unwrap().open_volume, 12);
    assert!(api.fees(QUOTE) > 0, "比例费应有非零手续费入账");

    // 收尾：MAKER 挂 BID@1000 size20（同价位，pnl 恒 0），TAKER1/TAKER2 分别 ASK 平仓对敲。
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 4, uid: MAKER, symbol: FUT_SYMBOL, price: 1_000, size: 20,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 5, uid: TAKER1, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 1_000, size: 8, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 6, uid: TAKER2, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 1_000, size: 12, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0, "同价位对敲（pnl恒0），naive 公式全程精确成立");

    assert!(api.user_position(MAKER, FUT_SYMBOL).is_none());
    assert!(api.user_position(TAKER1, FUT_SYMBOL).is_none());
    assert!(api.user_position(TAKER2, FUT_SYMBOL).is_none());
}

// ================================================================================================
// 场景 F：CROSS 保证金模式开仓/平仓。
// ================================================================================================

#[test]
fn scenario_f_cross_margin_mode_open_and_close_conserves() {
    const CROSS_USER: i64 = 1;
    const COUNTER: i64 = 2;

    let mut api = new_seeded_futures_api(futures_spec_fixed_fee(10, 5));
    assert_eq!(api.add_user(CROSS_USER), CommandResultCode::Success);
    assert_eq!(api.add_user(COUNTER), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(CROSS_USER, QUOTE, 100_000, 1), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(COUNTER, QUOTE, 100_000, 2), CommandResultCode::Success);
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: COUNTER, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Cross, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: CROSS_USER, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Cross, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    let pos = api.user_position(CROSS_USER, FUT_SYMBOL).expect("CROSS 开仓后必有仓位");
    assert_eq!(pos.margin_mode, MarginMode::Cross);
    assert_eq!(pos.direction, PositionDirection::Long);

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 130), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 3, uid: COUNTER, symbol: FUT_SYMBOL, action: OrderAction::Bid,
            price: 130, size: 10, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 4, uid: CROSS_USER, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 130, size: 10, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert!(api.user_position(CROSS_USER, FUT_SYMBOL).is_none());
    assert!(api.user_position(COUNTER, FUT_SYMBOL).is_none());
    assert!(api.user_account(CROSS_USER, QUOTE) > 100_000 - 100, "CROSS 多头应实现正 PnL");
}

// ================================================================================================
// Characterization：实证记录 naive 公式在两类场景下不精确成立、完整公式精确成立——非生产代码 bug，见文件头文档。
// ================================================================================================

#[test]
fn characterization_naive_formula_misses_fresh_counterparty_unrealized_pnl() {
    const A: i64 = 1;
    const B: i64 = 2;
    const C: i64 = 3;

    let mut api = new_seeded_futures_api(futures_spec_fixed_fee(0, 0)); // 费率清零，聚焦 PnL 本身
    for uid in [A, B, C] {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE, 10_000, uid), CommandResultCode::Success);
    }
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);

    // B 开空 10，A 开多 10 @100（互为对手方）。
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: B, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: A, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_eq!(naive_conservation(&api, QUOTE), 0, "开仓阶段两边都在 naive 公式范围内");
    assert_futures_conservation(&api);

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 120), CommandResultCode::Success);

    // C 开一笔全新的多头 @120（resting maker），A 拿自己已有的多头 10 去平（taker，对手方是
    // 从未跟 A 打过交道的 C，不是 B）。
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 3, uid: C, symbol: FUT_SYMBOL, price: 120, size: 10,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 4, uid: A, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 120, size: 10, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );

    // A 已全平并把 200 已实现盈利（(120-100)*10）整笔付进 accounts；B 仍持有对称的 -200
    // 未实现浮亏，但那笔浮亏只存在于 B 的仓位字段（open_volume/open_price_sum），不在
    // accounts/adjustments/fees 里——naive 公式因此偏差 +200；完整公式把 B 仓位的
    // `estimate_pnl(mark)` 加回来后精确为 0。
    assert!(api.user_position(A, FUT_SYMBOL).is_none(), "A 已全平");
    assert_eq!(api.user_account(A, QUOTE), 10_000 + 200, "A 已实现盈利 200（费率为 0）");
    assert_eq!(
        naive_conservation(&api, QUOTE),
        200,
        "naive 公式偏差 200——不是 bug，是 B 未平仓的浮亏尚未离开它自己的仓位字段"
    );
    assert_futures_conservation(&api); // 完整公式：把 B 仓位的 estimate_pnl(mark) 加回来后精确为 0
}

// ================================================================================================
// 守恒 proptest：随机期货命令流。
// ================================================================================================

#[derive(Debug, Clone)]
enum FutGenCmd {
    PlaceOpen { uid_idx: usize, is_bid: bool, price: i64, size: i64 },
    ClosePosition { uid_idx: usize, price: i64, size: i64 },
    MarginAdd { uid_idx: usize, amount: i64 },
    SetMarkPrice { price: i64 },
}

/// 单条命令生成策略：price/size 有界避免溢出；杠杆按用户预生成（见 [`fut_scenario_strategy`]）以避免同一用户杠杆不一致、提高有效撮合命中率。
fn gen_fut_cmd(n_users: usize) -> impl Strategy<Value = FutGenCmd> {
    let place = (0..n_users, any::<bool>(), 50i64..=200, 1i64..=50)
        .prop_map(|(uid_idx, is_bid, price, size)| FutGenCmd::PlaceOpen { uid_idx, is_bid, price, size });
    let close = (0..n_users, 50i64..=200, 1i64..=50)
        .prop_map(|(uid_idx, price, size)| FutGenCmd::ClosePosition { uid_idx, price, size });
    let margin_add = (0..n_users, 1i64..=5_000).prop_map(|(uid_idx, amount)| FutGenCmd::MarginAdd { uid_idx, amount });
    let mark = (50i64..=200).prop_map(|price| FutGenCmd::SetMarkPrice { price });
    prop_oneof![5 => place, 3 => close, 1 => margin_add, 1 => mark]
}

/// 顶层策略：fixed_fee 二选一、n_users∈[2,4]、每用户固定杠杆∈[1,5]、初始充值∈[1e6,1e8]（NSF 摩擦最小化但不排除偶发拒绝）、命令流长度∈[10,80)。
fn fut_scenario_strategy() -> impl Strategy<Value = (bool, usize, Vec<i32>, Vec<i64>, Vec<FutGenCmd>)> {
    (any::<bool>(), 2usize..=4).prop_flat_map(|(fixed_fee, n_users)| {
        let leverages = prop::collection::vec(1i32..=5, n_users);
        let balances = prop::collection::vec(1_000_000i64..=100_000_000i64, n_users);
        let cmds = prop::collection::vec(gen_fut_cmd(n_users), 10..80);
        (Just(fixed_fee), Just(n_users), leverages, balances, cmds)
    })
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(256))]

    /// 任意合式期货命令流跑完（且逐步）都不 panic，[`assert_futures_conservation`] 恒成立，
    /// `accounts` 恒非负，仓位记录内部字段恒非负。**必须**用完整公式（含仓位 `estimate_pnl` +
    /// `extra_margin`）——通用多用户随机撮合无法保证"每次全平都恰好对敲同一原始对手方"，见文件头
    /// 文档；用 naive 公式会在几乎第一次"平仓对手方与开仓对手方不同"时就产生预期内的非零偏差
    /// （不是 bug，见 [`characterization_naive_formula_misses_fresh_counterparty_unrealized_pnl`]）。
    #[test]
    fn conservation_holds_for_random_futures_command_stream(
        (fixed_fee, n_users, leverages, balances, cmds) in fut_scenario_strategy()
    ) {
        let spec = if fixed_fee {
            futures_spec_fixed_fee(10, 5)
        } else {
            futures_spec_proportional_fee(20, 10, 1_000_000)
        };
        let mut api = new_seeded_futures_api(spec);

        let uids: Vec<i64> = (1..=n_users as i64).collect();
        for &uid in &uids {
            prop_assert_eq!(api.add_user(uid), CommandResultCode::Success);
        }
        assert_futures_invariants(&api);

        for (i, &uid) in uids.iter().enumerate() {
            prop_assert_eq!(
                api.balance_adjustment(uid, QUOTE, balances[i], (i as i64) + 1),
                CommandResultCode::Success
            );
            assert_futures_invariants(&api);
        }

        prop_assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);
        assert_futures_invariants(&api);

        // 命令级 order_id 全局单调递增（同时充当 order book id 与 MARGIN_ADJUSTMENT 的幂等 txid
        // 命名空间），从 1000 起跳，确保不与上面播种阶段用过的 1..=n_users 冲突。
        let mut next_order_id: i64 = 1000;

        for cmd in &cmds {
            match cmd {
                FutGenCmd::PlaceOpen { uid_idx, is_bid, price, size } => {
                    let uid = uids[*uid_idx];
                    let action = if *is_bid { OrderAction::Bid } else { OrderAction::Ask };
                    let order_id = next_order_id;
                    next_order_id += 1;
                    let _ = api.place_futures_order(PlaceFuturesOrderRequest {
                        order_id,
                        uid,
                        symbol: FUT_SYMBOL,
                        price: *price,
                        size: *size,
                        action,
                        order_type: OrderType::Gtc,
                        leverage: leverages[*uid_idx],
                        margin_mode: MarginMode::Isolated,
                        reduce_only: false,
                    });
                }
                FutGenCmd::ClosePosition { uid_idx, price, size } => {
                    let uid = uids[*uid_idx];
                    // 按当前仓位方向选反向 action；无仓位/无敞口时随意选 Bid，
                    // `close_position_risk_check` 会因 `max_closable_size==0` 静默 no-op。
                    let action = match api.user_position(uid, FUT_SYMBOL) {
                        Some(pos) if pos.direction == PositionDirection::Long => OrderAction::Ask,
                        Some(pos) if pos.direction == PositionDirection::Short => OrderAction::Bid,
                        _ => OrderAction::Bid,
                    };
                    let order_id = next_order_id;
                    next_order_id += 1;
                    let _ = api.close_position(ClosePositionRequest {
                        order_id,
                        uid,
                        symbol: FUT_SYMBOL,
                        action,
                        price: *price,
                        size: *size,
                        order_type: OrderType::Gtc,
                    });
                }
                FutGenCmd::MarginAdd { uid_idx, amount } => {
                    let uid = uids[*uid_idx];
                    let order_id = next_order_id;
                    next_order_id += 1;
                    let _ = api.margin_adjustment(MarginAdjustmentRequest {
                        uid,
                        symbol: FUT_SYMBOL,
                        action: OrderAction::Bid, // OneWay 下被忽略
                        amount: *amount,
                        margin_mode: MarginMode::Isolated,
                        order_id,
                    });
                }
                FutGenCmd::SetMarkPrice { price } => {
                    let _ = api.set_mark_price(FUT_SYMBOL, *price);
                }
            }
            assert_futures_invariants(&api);
        }
    }
}
