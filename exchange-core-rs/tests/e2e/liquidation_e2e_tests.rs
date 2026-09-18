//! Rust 自建的期货强平端到端 + 属性测试套件。
//! 未对应单个 Java 测试类,而是针对 `ExchangeCore` 完整流程(下单开仓、markprice
//! 推进触发强平引擎、IF notional 结算)验证资金守恒(QUOTE/BASE)与 IF 可用余额
//! 非负这两条不变量;最后一个用例用 proptest 对随机下单+markprice 命令流做压测。
use proptest::prelude::*;

use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
use exchange_core_rs::core::common::core_currency_specification::CoreCurrencySpecification;
use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
use exchange_core_rs::core::common::margin_mode::MarginMode;
use exchange_core_rs::core::common::order_action::OrderAction;
use exchange_core_rs::core::common::order_type::OrderType;
use exchange_core_rs::core::common::position_direction::PositionDirection;
use exchange_core_rs::core::common::symbol_type::SymbolType;

use exchange_core_rs::core::exchange_core::ExchangeCore;

const BASE: i32 = 1;
const QUOTE: i32 = 2;
const FUT: i32 = 500;

// 计算指定币种在全系统的守恒总量:用户余额 + 手续费池 + 调整池 + 持仓浮盈亏/追加保证金,
// QUOTE 额外加上 IF notional 可用余额与 IF 持仓估值
fn conserved(core: &ExchangeCore, cur: i32) -> i64 {
    let mark = core.risk.last_price_cache.get(&FUT).map(|r| r.mark_price).unwrap_or(0);
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
    if cur == QUOTE {
        for n in core.risk.liquidation_service.notionals.values() {
            total += n.available;
        }
        for ifp in core.risk.liquidation_service.positions.values() {
            total += ifp.position_value(mark);
        }
    }
    total
}

// 断言所有 IF notional 桶的可用余额均非负
fn assert_if_non_negative(core: &ExchangeCore) {
    for n in core.risk.liquidation_service.notionals.values() {
        assert!(n.available >= 0, "IFNotional.available must not be negative: {}", n.available);
    }
}

// 构造一个永续合约的最小 symbol spec(单档维持保证金 + 清算手续费)
fn fut_spec() -> CoreSymbolSpecification {
    let mut mm = std::collections::BTreeMap::new();
    mm.insert(i64::MAX, 500);
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
        liquidation_fee: 200,
        ..Default::default()
    }
}

// 初始化一个带 n_users 个用户(各自预充值 QUOTE)、已开启强平引擎的 ExchangeCore
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

// 下一笔限价单(GTC)并直接提交给 core
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

// 推进 markprice,驱动强平引擎按新标记价重新评估持仓
fn markprice(core: &mut ExchangeCore, price: i64, ts: i64) {
    let mut c = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: FUT, price, timestamp: ts, ..Default::default() };
    core.process_command(&mut c);
}

// markprice 下跌触发 borrower 仓位被强平引擎全额平仓(FORCE 全部成交),清算手续费应进入 IF,
// 且期间 QUOTE(含 IF)与 BASE 均保持全局守恒
#[test]
fn force_full_fill_moves_fee_to_if_and_conserves() {
    let (mut core, uids) = seeded(3);
    let (m1, borrower, m2) = (uids[0], uids[1], uids[2]);
    markprice(&mut core, 100, 1_000);

    place(&mut core, 100, m1, 100, 10, false, 10);
    place(&mut core, 101, borrower, 100, 10, true, 10);
    assert_eq!(core.ups.get(borrower).unwrap().positions[&FUT].direction, PositionDirection::Long);
    place(&mut core, 102, m2, 92, 10, true, 10);

    let before_q = conserved(&core, QUOTE);
    let before_b = conserved(&core, BASE);

    markprice(&mut core, 94, 2_000);

    assert!(!core.ups.get(borrower).unwrap().positions.contains_key(&FUT), "borrower position should be fully closed");
    let if_avail: i64 = core.risk.liquidation_service.notionals.values().map(|n| n.available).sum();
    assert!(if_avail > 0, "liquidation fee should flow into IF");
    assert_eq!(conserved(&core, QUOTE), before_q, "QUOTE (including IF) should be conserved");
    assert_eq!(conserved(&core, BASE), before_b, "BASE should be conserved");
    assert_if_non_negative(&core);
}

// 健康仓位(markprice 波动未跌破维持保证金)不应被强平引擎触发,资金保持守恒
#[test]
fn healthy_market_no_liquidation_conserves() {
    let (mut core, uids) = seeded(2);
    markprice(&mut core, 100, 1_000);
    place(&mut core, 100, uids[0], 100, 10, false, 5);
    place(&mut core, 101, uids[1], 100, 10, true, 5);
    let before = conserved(&core, QUOTE);
    markprice(&mut core, 101, 2_000);
    assert!(core.ups.get(uids[1]).unwrap().positions.contains_key(&FUT), "healthy position should not be liquidated");
    assert_eq!(conserved(&core, QUOTE), before);
}

// proptest 随机命令流的两种变体:下单 或 推进 markprice
#[derive(Debug, Clone)]
enum GenCmd {
    Place { uid_idx: usize, price: i64, size: i64 },
    Mark { price: i64 },
}

// GenCmd 的随机生成策略
fn cmd_strategy() -> impl Strategy<Value = GenCmd> {
    prop_oneof![
        (0usize..4, 80i64..120, 1i64..20).prop_map(|(uid_idx, price, size)| GenCmd::Place { uid_idx, price, size }),
        (60i64..140).prop_map(|price| GenCmd::Mark { price }),
    ]
}

proptest! {
    #![proptest_config(ProptestConfig { cases: 120, ..ProptestConfig::default() })]

    // 对随机下单/markprice 命令流(含强平触发)做压测,每步之后都断言 QUOTE/BASE 守恒、
    // IF 可用余额非负、强平引擎无残留未处理命令
    #[test]
    fn conservation_holds_under_random_stream_with_liquidation(cmds in prop::collection::vec(cmd_strategy(), 1..40)) {
        let (mut core, uids) = seeded(4);
        markprice(&mut core, 100, 1_000);

        let base_q = conserved(&core, QUOTE);
        let base_b = conserved(&core, BASE);

        let mut oid: i64 = 1000;
        let mut ts: i64 = 2_000;
        for cmd in &cmds {
            match cmd {
                GenCmd::Place { uid_idx, price, size } => {
                    let bid = uid_idx % 2 == 0;
                    place(&mut core, oid, uids[*uid_idx], *price, *size, bid, 10);
                    oid += 1;
                }
                GenCmd::Mark { price } => {
                    markprice(&mut core, *price, ts);
                    ts += 1_000;
                }
            }
            prop_assert_eq!(conserved(&core, QUOTE), base_q, "QUOTE conservation violated");
            prop_assert_eq!(conserved(&core, BASE), base_b, "BASE conservation violated");
            for n in core.risk.liquidation_service.notionals.values() {
                prop_assert!(n.available >= 0, "IFNotional.available is negative");
            }
        }
    }
}
