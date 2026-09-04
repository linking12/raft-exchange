//! P6 Task 10（收官）：期货清算/ADL/IF 全局守恒扩展 + e2e 场景 + 守恒 proptest，守恒恒等式含 IF 项（Ruling P6-I，本文件 scale 全 1 恒等缩放）。
use proptest::prelude::*;

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::common::position_direction::PositionDirection;
use crate::core::common::symbol_type::SymbolType;

use super::exchange_core::ExchangeCore;

const BASE: i32 = 1;
const QUOTE: i32 = 2;
const FUT: i32 = 500;

// ================================================================================================
// 守恒 helper（含 IF 项）
// ================================================================================================

/// 全局守恒（含 IF，Ruling P6-I）——见文件头文档。scale 全 1，IF 项直接相加。
fn conserved(core: &ExchangeCore, cur: i32) -> i64 {
    let mark = *core.risk.last_price_cache.get(&FUT).unwrap_or(&0);
    let mut total: i64 = core.ups.users.values().map(|u| u.account(cur)).sum();
    total += *core.risk.fees.get(&cur).unwrap_or(&0);
    total += *core.risk.adjustments.get(&cur).unwrap_or(&0);
    for u in core.ups.users.values() {
        for p in u.positions.values() {
            if p.currency == cur {
                total += p.estimate_pnl(mark) + p.extra_margin;
            }
        }
    }
    // IF：available（quote notional）+ IF 接管仓的 estimate_pnl(mark)。
    if cur == QUOTE {
        for n in core.risk.liquidation_service.notionals.values() {
            total += n.available;
        }
        for ifp in core.risk.liquidation_service.positions.values() {
            let sign = ifp.direction.multiplier() as i64;
            total += sign * (mark * ifp.open_volume - ifp.open_price_sum);
        }
    }
    total
}

/// IF `available` 永不为负（IF 自限，§2.3）。
fn assert_if_non_negative(core: &ExchangeCore) {
    for n in core.risk.liquidation_service.notionals.values() {
        assert!(n.available >= 0, "IFNotional.available 不得为负: {}", n.available);
    }
}

// ================================================================================================
// 治具
// ================================================================================================

fn fut_spec() -> CoreSymbolSpecification {
    let mut mm = std::collections::BTreeMap::new();
    mm.insert(i64::MAX, 500); // MM 单档 5%
    CoreSymbolSpecification {
        symbol_id: FUT,
        symbol_type: SymbolType::FuturesContractPerpetual,
        base_currency: BASE,
        quote_currency: QUOTE,
        base_scale_k: 1,
        quote_scale_k: 1,
        taker_fee: 0,
        maker_fee: 0,
        fee_scale_k: 10_000,
        maintenance_margin: mm,
        maintenance_margin_scale_k: 10_000,
        liquidation_fee: 200, // 2%
        ..Default::default()
    }
}

fn seeded(n_users: i64) -> (ExchangeCore, Vec<i64>) {
    let mut core = ExchangeCore::new();
    core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
    core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
    assert_eq!(core.ssp.add_symbol(fut_spec()), CommandResultCode::Success);
    core.matching.add_symbol(&fut_spec());
    let uids: Vec<i64> = (1..=n_users).collect();
    for &uid in &uids {
        core.ups.add_empty_user_profile(uid);
        core.ups.get_mut(uid).unwrap().add_to_account(QUOTE, 1_000_000);
    }
    core.risk.liquidation_engine.is_running = true;
    (core, uids)
}

fn place(core: &mut ExchangeCore, order_id: i64, uid: i64, price: i64, size: i64, bid: bool, leverage: i32) {
    let mut c = OrderCommand {
        command: OrderCommandType::PlaceOrder,
        order_id,
        uid,
        symbol: FUT,
        price,
        size,
        reserve_bid_price: price,
        action: Some(if bid { OrderAction::Bid } else { OrderAction::Ask }),
        order_type: Some(OrderType::Gtc),
        leverage,
        margin_mode: MarginMode::Isolated,
        timestamp: 1_000,
        ..Default::default()
    };
    core.process_command(&mut c);
}

fn markprice(core: &mut ExchangeCore, price: i64, ts: i64) {
    let mut c = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: FUT, price, timestamp: ts, ..Default::default() };
    core.process_command(&mut c);
}

// ================================================================================================
// 确定性 e2e 场景（每步断言含 IF 的全局守恒）
// ================================================================================================

#[test]
fn force_full_fill_moves_fee_to_if_and_conserves() {
    let (mut core, uids) = seeded(3);
    let (m1, borrower, m2) = (uids[0], uids[1], uids[2]);
    markprice(&mut core, 100, 1_000);

    // m1 挂 ASK@100（开 SHORT），borrower BID@100 吃单开 LONG（lev10 -> margin 100）。
    place(&mut core, 100, m1, 100, 10, false, 10);
    place(&mut core, 101, borrower, 100, 10, true, 10);
    assert_eq!(core.ups.get(borrower).unwrap().positions[&FUT].direction, PositionDirection::Long);
    // m2 挂 BID@92（破产价，吸收 FORCE ASK）。
    place(&mut core, 102, m2, 92, 10, true, 10);

    let before_q = conserved(&core, QUOTE);
    let before_b = conserved(&core, BASE);

    markprice(&mut core, 94, 2_000); // borrower LONG 水下 -> FORCE 全平

    assert!(core.risk.liquidation_engine.pending_commands.is_empty());
    assert!(!core.ups.get(borrower).unwrap().positions.contains_key(&FUT), "borrower 被全平");
    let if_avail: i64 = core.risk.liquidation_service.notionals.values().map(|n| n.available).sum();
    assert!(if_avail > 0, "清算费入 IF");
    assert_eq!(conserved(&core, QUOTE), before_q, "含 IF 的 QUOTE 守恒");
    assert_eq!(conserved(&core, BASE), before_b, "BASE 守恒");
    assert_if_non_negative(&core);
}

#[test]
fn healthy_market_no_liquidation_conserves() {
    let (mut core, uids) = seeded(2);
    markprice(&mut core, 100, 1_000);
    place(&mut core, 100, uids[0], 100, 10, false, 5);
    place(&mut core, 101, uids[1], 100, 10, true, 5);
    let before = conserved(&core, QUOTE);
    markprice(&mut core, 101, 2_000); // 小幅波动，健康
    assert!(core.risk.liquidation_engine.pending_commands.is_empty());
    assert!(core.ups.get(uids[1]).unwrap().positions.contains_key(&FUT), "健康仓不被平");
    assert_eq!(conserved(&core, QUOTE), before);
}

// ================================================================================================
// 守恒 proptest：随机命令流 + 强平引擎开启，含 IF 的全局守恒每币 == 0、IF 永不负、无 panic
// ================================================================================================

#[derive(Debug, Clone)]
enum GenCmd {
    Place { uid_idx: usize, price: i64, size: i64 },
    Mark { price: i64 },
}

/// 生成器按 uid 奇偶固定方向（偶=BID/奇=ASK），刻意排除自成交——避免触发 P4 结算 maker `required=true` 不变式 panic（已知 hazard，非本任务范围，非隐藏 bug）。
fn cmd_strategy() -> impl Strategy<Value = GenCmd> {
    prop_oneof![
        (0usize..4, 80i64..120, 1i64..20).prop_map(|(uid_idx, price, size)| GenCmd::Place { uid_idx, price, size }),
        (60i64..140).prop_map(|price| GenCmd::Mark { price }),
    ]
}

proptest! {
    #![proptest_config(ProptestConfig { cases: 120, ..ProptestConfig::default() })]

    /// 4 用户单期货、杠杆 10、强平引擎开启，随机下单+markprice 触发 FORCE→IF→ADL 级联；断言含 IF 的全局守恒（QUOTE+BASE）恒定、IFNotional 非负、不 panic。
    #[test]
    fn conservation_holds_under_random_stream_with_liquidation(cmds in prop::collection::vec(cmd_strategy(), 1..40)) {
        let (mut core, uids) = seeded(4);
        markprice(&mut core, 100, 1_000);

        // 起始守恒基线（此时无仓无 IF）。
        let base_q = conserved(&core, QUOTE);
        let base_b = conserved(&core, BASE);

        let mut oid: i64 = 1000;
        let mut ts: i64 = 2_000;
        for cmd in &cmds {
            match cmd {
                GenCmd::Place { uid_idx, price, size } => {
                    // 方向按 uid 奇偶固定（偶=BID，奇=ASK）——排除自成交，见 `cmd_strategy` 文档。
                    let bid = uid_idx % 2 == 0;
                    place(&mut core, oid, uids[*uid_idx], *price, *size, bid, 10);
                    oid += 1;
                }
                GenCmd::Mark { price } => {
                    markprice(&mut core, *price, ts);
                    ts += 1_000;
                }
            }
            // 每步：含 IF 的全局守恒 + IF 非负（drain 已在 process_command 内跑完级联）。
            prop_assert_eq!(conserved(&core, QUOTE), base_q, "QUOTE 守恒破坏");
            prop_assert_eq!(conserved(&core, BASE), base_b, "BASE 守恒破坏");
            for n in core.risk.liquidation_service.notionals.values() {
                prop_assert!(n.available >= 0, "IFNotional.available 为负");
            }
            // 队列必须每步排空（无残留未处理的强平命令）。
            prop_assert!(core.risk.liquidation_engine.pending_commands.is_empty());
        }
    }
}
